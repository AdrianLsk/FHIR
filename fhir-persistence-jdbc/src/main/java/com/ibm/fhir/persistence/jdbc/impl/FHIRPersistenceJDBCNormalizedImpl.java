/*
 * (C) Copyright IBM Corp. 2017,2018,2019
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.ibm.fhir.persistence.jdbc.impl;

import static com.ibm.fhir.config.FHIRConfiguration.PROPERTY_JDBC_ENABLE_CODE_SYSTEMS_CACHE;
import static com.ibm.fhir.config.FHIRConfiguration.PROPERTY_JDBC_ENABLE_PARAMETER_NAMES_CACHE;
import static com.ibm.fhir.config.FHIRConfiguration.PROPERTY_JDBC_ENABLE_RESOURCE_TYPES_CACHE;
import static com.ibm.fhir.config.FHIRConfiguration.PROPERTY_REPL_INTERCEPTOR_ENABLED;
import static com.ibm.fhir.config.FHIRConfiguration.PROPERTY_UPDATE_CREATE_ENABLED;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;

import javax.naming.InitialContext;
import javax.transaction.TransactionSynchronizationRegistry;

import com.ibm.fhir.config.FHIRConfiguration;
import com.ibm.fhir.config.PropertyGroup;
import com.ibm.fhir.core.FHIRUtilities;
import com.ibm.fhir.database.utils.api.IConnectionProvider;
import com.ibm.fhir.exception.FHIRException;
import com.ibm.fhir.model.format.Format;
import com.ibm.fhir.model.generator.FHIRGenerator;
import com.ibm.fhir.model.path.FHIRPathNode;
import com.ibm.fhir.model.path.FHIRPathPrimitiveValue;
import com.ibm.fhir.model.resource.Resource;
import com.ibm.fhir.model.resource.SearchParameter;
import com.ibm.fhir.model.type.Id;
import com.ibm.fhir.model.type.Instant;
import com.ibm.fhir.model.type.Meta;
import com.ibm.fhir.model.util.JsonSupport;
import com.ibm.fhir.persistence.FHIRPersistence;
import com.ibm.fhir.persistence.FHIRPersistenceTransaction;
import com.ibm.fhir.persistence.MultiResourceResult;
import com.ibm.fhir.persistence.SingleResourceResult;
import com.ibm.fhir.persistence.context.FHIRHistoryContext;
import com.ibm.fhir.persistence.context.FHIRPersistenceContext;
import com.ibm.fhir.persistence.exception.FHIRPersistenceException;
import com.ibm.fhir.persistence.exception.FHIRPersistenceResourceDeletedException;
import com.ibm.fhir.persistence.exception.FHIRPersistenceResourceNotFoundException;
import com.ibm.fhir.persistence.jdbc.dao.api.ParameterDAO;
import com.ibm.fhir.persistence.jdbc.dao.api.ParameterNormalizedDAO;
import com.ibm.fhir.persistence.jdbc.dao.api.ResourceNormalizedDAO;
import com.ibm.fhir.persistence.jdbc.dao.impl.FHIRDbDAOBasicImpl;
import com.ibm.fhir.persistence.jdbc.dao.impl.ParameterDAONormalizedImpl;
import com.ibm.fhir.persistence.jdbc.dao.impl.ResourceDAONormalizedImpl;
import com.ibm.fhir.persistence.jdbc.dto.Parameter;
import com.ibm.fhir.persistence.jdbc.exception.FHIRPersistenceDBConnectException;
import com.ibm.fhir.persistence.jdbc.exception.FHIRPersistenceDataAccessException;
import com.ibm.fhir.persistence.jdbc.exception.FHIRPersistenceFKVException;
import com.ibm.fhir.persistence.jdbc.util.CodeSystemsCache;
import com.ibm.fhir.persistence.jdbc.util.JDBCNormalizedQueryBuilder;
import com.ibm.fhir.persistence.jdbc.util.JDBCParameterBuildingVisitor;
import com.ibm.fhir.persistence.jdbc.util.ParameterNamesCache;
import com.ibm.fhir.persistence.jdbc.util.QueryBuilderUtil;
import com.ibm.fhir.persistence.jdbc.util.ResourceTypesCache;
import com.ibm.fhir.persistence.jdbc.util.SqlQueryData;
import com.ibm.fhir.persistence.util.FHIRPersistenceUtil;
import com.ibm.fhir.replication.api.util.ReplicationUtil;
import com.ibm.fhir.search.SearchConstants.Type;
import com.ibm.fhir.search.SummaryValueSet;
import com.ibm.fhir.search.context.FHIRSearchContext;
import com.ibm.fhir.search.util.SearchUtil;

/**
 * This class is the JDBC implementation of the FHIRPersistence interface to support the "normalized" DB schema, 
 * providing implementations for CRUD type APIs and search.
 * @author markd
 *
 */
public class FHIRPersistenceJDBCNormalizedImpl extends FHIRPersistenceJDBCImpl implements FHIRPersistence, FHIRPersistenceTransaction {
    private static final String CLASSNAME = FHIRPersistenceJDBCNormalizedImpl.class.getName();
    private static final Logger log = Logger.getLogger(CLASSNAME);
        
    public static final String TRX_SYNCH_REG_JNDI_NAME = "java:comp/TransactionSynchronizationRegistry";
    
    private ResourceNormalizedDAO resourceDao;
    private ParameterNormalizedDAO parameterDao;
    private TransactionSynchronizationRegistry trxSynchRegistry;

    /**
     * Constructor for use when running as web application in WLP. 
     * @throws Exception 
     */
    public FHIRPersistenceJDBCNormalizedImpl() throws Exception {
        super();
        final String METHODNAME = "FHIRPersistenceJDBCNormalizedImpl()";
        log.entering(CLASSNAME, METHODNAME);
        
        PropertyGroup fhirConfig = FHIRConfiguration.getInstance().loadConfiguration();
        this.updateCreateEnabled = fhirConfig.getBooleanProperty(PROPERTY_UPDATE_CREATE_ENABLED, Boolean.TRUE);
        ParameterNamesCache.setEnabled(fhirConfig.getBooleanProperty(PROPERTY_JDBC_ENABLE_PARAMETER_NAMES_CACHE, 
                                       Boolean.TRUE.booleanValue()));
        CodeSystemsCache.setEnabled(fhirConfig.getBooleanProperty(PROPERTY_JDBC_ENABLE_CODE_SYSTEMS_CACHE, 
                                    Boolean.TRUE.booleanValue()));
        ResourceTypesCache.setEnabled(fhirConfig.getBooleanProperty(PROPERTY_JDBC_ENABLE_RESOURCE_TYPES_CACHE, 
                                      Boolean.TRUE.booleanValue()));
        this.resourceDao = new ResourceDAONormalizedImpl(this.getTrxSynchRegistry());
        this.resourceDao.setRepInfoRequired(fhirConfig.getBooleanProperty(PROPERTY_REPL_INTERCEPTOR_ENABLED, Boolean.FALSE));
        this.parameterDao = new ParameterDAONormalizedImpl(this.getTrxSynchRegistry());
        
        log.exiting(CLASSNAME, METHODNAME);
    }
    
    /**
     * Constructor for use when running standalone, outside of any web container.
     * @throws Exception 
     */
    @SuppressWarnings("rawtypes")
    public FHIRPersistenceJDBCNormalizedImpl(Properties configProps) throws Exception {
        super(configProps);
        final String METHODNAME = "FHIRPersistenceJDBCNormalizedImpl(Properties)";
        log.entering(CLASSNAME, METHODNAME);
        
        this.updateCreateEnabled = Boolean.parseBoolean(configProps.getProperty("updateCreateEnabled"));
        
        this.setBaseDao(new FHIRDbDAOBasicImpl(configProps));
        this.setManagedConnection(this.getBaseDao().getConnection());
        this.resourceDao = new ResourceDAONormalizedImpl(this.getManagedConnection());
        this.resourceDao.setRepInfoRequired(false);
        this.parameterDao = new ParameterDAONormalizedImpl(this.getManagedConnection());
                
        log.exiting(CLASSNAME, METHODNAME);
    }

    /**
     * Constructor for use when running standalone, outside of any web container.
     * @throws Exception 
     */
    @SuppressWarnings("rawtypes")
    public FHIRPersistenceJDBCNormalizedImpl(Properties configProps, IConnectionProvider cp) throws Exception {
        super(configProps, cp);
        final String METHODNAME = "FHIRPersistenceJDBCNormalizedImpl(Properties, IConnectionProvider)";
        log.entering(CLASSNAME, METHODNAME);
        
        this.updateCreateEnabled = Boolean.parseBoolean(configProps.getProperty("updateCreateEnabled"));
        
        this.setBaseDao(new FHIRDbDAOBasicImpl(cp, configProps.getProperty("adminSchemaName")));
        this.setManagedConnection(this.getBaseDao().getConnection());
        this.resourceDao = new ResourceDAONormalizedImpl(this.getManagedConnection());
        this.resourceDao.setRepInfoRequired(false);
        this.parameterDao = new ParameterDAONormalizedImpl(this.getManagedConnection());
                
        log.exiting(CLASSNAME, METHODNAME);
    }

    /* (non-Javadoc)
     * @see com.ibm.fhir.persistence.FHIRPersistence#isDeleteSupported()
     */
    @Override
    public boolean isDeleteSupported() {
        return true;
    }
    
    /* (non-Javadoc)
     * @see com.ibm.fhir.persistence.FHIRPersistence#create(com.ibm.fhir.persistence.context.FHIRPersistenceContext, com.ibm.fhir.model.Resource)
     */
    @Override
    public <T extends Resource> SingleResourceResult<T> create(FHIRPersistenceContext context, T resource) throws FHIRPersistenceException  {
        final String METHODNAME = "create";
        log.entering(CLASSNAME, METHODNAME);
        
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        String logicalId;
        
        // We need to update the meta in the resource, so we need a modifiable version
        Resource.Builder resultBuilder = resource.toBuilder();

        
        try {
            // This create() operation is only called by a REST create. If the given resource
            // contains an id, the for R4 we need to ignore it and replace it with our
            // system-generated value. For the update-or-create scenario, see doUpdate()
            // Default version is 1 for a brand new FHIR Resource.
            int newVersionNumber = 1;
            logicalId = UUID.randomUUID().toString();
            if (log.isLoggable(Level.FINE)) {
                log.fine("Creating new FHIR Resource of type '" + resource.getClass().getSimpleName() + "'");
            }

            // Set the resource id and meta fields.
            Instant lastUpdated = Instant.now(ZoneOffset.UTC);
            resultBuilder.id(Id.of(logicalId));
            Meta meta = resource.getMeta();
            Meta.Builder metaBuilder = meta == null ? Meta.builder() : meta.toBuilder();
            metaBuilder.versionId(Id.of(Integer.toString(newVersionNumber)));
            metaBuilder.lastUpdated(lastUpdated);
            resultBuilder.meta(metaBuilder.build());
            
            // rebuild the resource with updated meta
            @SuppressWarnings("unchecked")
            T updatedResource = (T) resultBuilder.build();
            
            // Create the new Resource DTO instance.
            com.ibm.fhir.persistence.jdbc.dto.Resource resourceDTO = new com.ibm.fhir.persistence.jdbc.dto.Resource();
            resourceDTO.setLogicalId(logicalId);
            resourceDTO.setVersionId(newVersionNumber);
            Timestamp timestamp = FHIRUtilities.convertToTimestamp(lastUpdated.getValue());
            resourceDTO.setLastUpdated(timestamp);
            resourceDTO.setResourceType(updatedResource.getClass().getSimpleName());
            
            // Serialize and compress the Resource
            GZIPOutputStream zipStream = new GZIPOutputStream(stream);
            FHIRGenerator.generator( Format.JSON, false).generate(updatedResource, zipStream);
            zipStream.finish();
            resourceDTO.setData(stream.toByteArray());
            zipStream.close();
            
            // Persist the Resource DTO.
            this.getResourceDao().setPersistenceContext(context);
            this.getResourceDao().insert(resourceDTO, this.extractSearchParameters(updatedResource, resourceDTO), this.parameterDao);
            if (log.isLoggable(Level.FINE)) {
                log.fine("Persisted FHIR Resource '" + resourceDTO.getResourceType() + "/" + resourceDTO.getLogicalId() + "' id=" + resourceDTO.getId()
                            + ", version=" + resourceDTO.getVersionId());
            }
            
            SingleResourceResult<T> result = new SingleResourceResult.Builder<T>()
                    .success(true)
                    .resource(updatedResource)
                    .build();
            
            return result;
        }
        catch(FHIRPersistenceFKVException e) {
            log.log(Level.SEVERE, "FK violation", e);
//            log.log(Level.SEVERE, this.performCacheDiagnostics());
            throw e;
        }
        catch(FHIRPersistenceException e) {
            throw e;
        }
        catch(Throwable e) {
            FHIRPersistenceException fx = new FHIRPersistenceException("Unexpected error while performing a create operation.");
            log.log(Level.SEVERE, fx.getMessage(), e);
            throw fx;
        }
        finally {
           log.exiting(CLASSNAME, METHODNAME);
        }
    }

    /* (non-Javadoc)
     * @see com.ibm.fhir.persistence.FHIRPersistence#update(com.ibm.fhir.persistence.context.FHIRPersistenceContext, java.lang.String, com.ibm.fhir.model.Resource)
     */
    @Override
    public <T extends Resource> SingleResourceResult<T> update(FHIRPersistenceContext context, String logicalId, T resource) throws FHIRPersistenceException {
        final String METHODNAME = "update";
        log.entering(CLASSNAME, METHODNAME);
        
        Class<? extends Resource> resourceType = resource.getClass();
        com.ibm.fhir.persistence.jdbc.dto.Resource existingResourceDTO;
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        
        // Resources are immutable, so we need a new builder to update it (since R4)
        Resource.Builder resultBuilder = resource.toBuilder();
        
        try {
            // Assume we have no existing resource.
            int existingVersion = 0;
            
            // Compute the new version # from the existing version #.
            
            // If the "previous resource" is set in the persistence event, then get the 
            // existing version # from that.
            if (context.getPersistenceEvent() != null && context.getPersistenceEvent().isPrevFhirResourceSet()) {
                Resource existingResource = context.getPersistenceEvent().getPrevFhirResource();
                if (existingResource != null) {
                    log.fine("Using pre-fetched 'previous' resource.");
                    String version = existingResource.getMeta().getVersionId().getValue();
                    existingVersion = Integer.valueOf(version);
                }
            } 
            
            // Otherwise, go ahead and read the resource from the datastore and get the
            // existing version # from it.
            else {
                log.fine("Fetching 'previous' resource for update.");
                existingResourceDTO = this.getResourceDao().read(logicalId, resourceType.getSimpleName());
                if (existingResourceDTO != null) {
                    existingVersion = existingResourceDTO.getVersionId();
                }
            }
            
            // If this logical resource didn't exist and the "updateCreate" feature is not enabled,
            // then this is an error.
            if (existingVersion == 0 && !updateCreateEnabled) {
                String msg = "Resource '" + resourceType.getSimpleName() + "/" + logicalId + "' not found.";
                log.log(Level.SEVERE, msg);
                throw new FHIRPersistenceResourceNotFoundException(msg);
            }
            
            // Bump up the existing version # to get the new version.
            int newVersionNumber = existingVersion + 1;
            
            if (log.isLoggable(Level.FINE)) {
                if (existingVersion != 0) {
                    log.fine("Updating FHIR Resource '" + resource.getClass().getSimpleName() + "/" + logicalId + "', version=" + existingVersion);
                }
                log.fine("Storing new FHIR Resource '" + resource.getClass().getSimpleName() + "/" + logicalId + "', version=" + newVersionNumber);
            }
            
            Instant lastUpdated = Instant.now(ZoneOffset.UTC);
            
            // Set the resource id and meta fields.
            resultBuilder.id(Id.of(logicalId));
            Meta meta = resource.getMeta();
            Meta.Builder metaBuilder = meta == null ? Meta.builder() : meta.toBuilder();
            metaBuilder.versionId(Id.of(Integer.toString(newVersionNumber)));
            metaBuilder.lastUpdated(lastUpdated);
            resultBuilder.meta(metaBuilder.build());
            
            @SuppressWarnings("unchecked")
            T updatedResource = (T) resultBuilder.build();
            
            // Create the new Resource DTO instance.
            com.ibm.fhir.persistence.jdbc.dto.Resource resourceDTO = new com.ibm.fhir.persistence.jdbc.dto.Resource();
            resourceDTO.setLogicalId(logicalId);
            resourceDTO.setVersionId(newVersionNumber);
            Timestamp timestamp = FHIRUtilities.convertToTimestamp(lastUpdated.getValue());
            resourceDTO.setLastUpdated(timestamp);
            resourceDTO.setResourceType(updatedResource.getClass().getSimpleName());
                        
            // Serialize and compress the Resource
            GZIPOutputStream zipStream = new GZIPOutputStream(stream);
            FHIRGenerator.generator(Format.JSON, false).generate(updatedResource, zipStream);
            zipStream.finish();
            resourceDTO.setData(stream.toByteArray());
            zipStream.close();
            
            // Persist the Resource DTO.
            this.getResourceDao().setPersistenceContext(context);
            this.getResourceDao().insert(resourceDTO, this.extractSearchParameters(updatedResource, resourceDTO), this.parameterDao);
            if (log.isLoggable(Level.FINE)) {
                log.fine("Persisted FHIR Resource '" + resourceDTO.getResourceType() + "/" + resourceDTO.getLogicalId() + "' id=" + resourceDTO.getId()
                            + ", version=" + resourceDTO.getVersionId());
            }
            
            SingleResourceResult<T> result = new SingleResourceResult.Builder<T>()
                    .success(true)
                    .resource(updatedResource)
                    .build();
            
            return result;
        }
        catch(FHIRPersistenceFKVException e) {
            log.log(Level.SEVERE, this.performCacheDiagnostics());
            throw e;
        }
        catch(FHIRPersistenceException e) {
            throw e;
        }
        catch(Throwable e) {
            // don't chain the exception to avoid leaking secrets
            FHIRPersistenceException fx = new FHIRPersistenceException("Unexpected error while performing an update operation.");
            log.log(Level.SEVERE, fx.getMessage(), e);
            throw fx;
        }
        finally {
            log.exiting(CLASSNAME, METHODNAME);
        }
    }
    
    /* (non-Javadoc)
     * @see com.ibm.fhir.persistence.FHIRPersistence#search(com.ibm.fhir.persistence.context.FHIRPersistenceContext, java.lang.Class)
     */
    @Override
    public MultiResourceResult<Resource> search(FHIRPersistenceContext context, Class<? extends Resource> resourceType)
            throws FHIRPersistenceException {
        final String METHODNAME = "search";
        log.entering(CLASSNAME, METHODNAME);
        
        List<Resource> resources = new ArrayList<Resource>();
        FHIRSearchContext searchContext = context.getSearchContext();
        JDBCNormalizedQueryBuilder queryBuilder;
        List<Long> sortedIdList;
        List<com.ibm.fhir.persistence.jdbc.dto.Resource> unsortedResultsList;
        int searchResultCount = 0;
        int pageSize;
        int lastPageNumber;
        SqlQueryData countQuery;
        SqlQueryData query;
                
        try {
            queryBuilder = new JDBCNormalizedQueryBuilder((ParameterNormalizedDAO)this.getParameterDao(),
                                                          (ResourceNormalizedDAO)this.getResourceDao());
             
            countQuery = queryBuilder.buildCountQuery(resourceType, searchContext);
            if (countQuery != null) {
                searchResultCount = this.getResourceDao().searchCount(countQuery);
                if (log.isLoggable(Level.FINE)) {
                    log.fine("searchResultCount = " + searchResultCount);
                }
                searchContext.setTotalCount(searchResultCount);
                pageSize = searchContext.getPageSize();
                lastPageNumber = (int) ((searchResultCount + pageSize - 1) / pageSize);
                searchContext.setLastPageNumber(lastPageNumber);
                
                 
                // For _summary=count, we don't need to return any resource 
                if (searchResultCount > 0 && 
                        !(searchContext.getSummaryParameter() != null 
                        && searchContext.getSummaryParameter().equals(SummaryValueSet.COUNT))) {
                    query = queryBuilder.buildQuery(resourceType, searchContext);
                    
                    List<String> elements = searchContext.getElementsParameters();
                    
                    //Only consider _summary if _elements parameter is empty
                    if (elements == null && searchContext.hasSummaryParameter()) {
                        Set<String> summaryElements = null;
                        SummaryValueSet summary = searchContext.getSummaryParameter();
                        
                        switch (summary) {
                        case TRUE:
                            summaryElements = JsonSupport.getSummaryElementNames(resourceType);
                            break;
                        case TEXT:
                            summaryElements = SearchUtil.getSummaryTextElementNames(resourceType);
                            break;
                        case DATA:
                            summaryElements = JsonSupport.getSummaryDataElementNames(resourceType);
                            break;
                        default:
                            break;
                            
                        }

                        if (summaryElements != null) {
                            elements = new ArrayList<String>();
                            elements.addAll(summaryElements);
                        }
                    }
                    
                    if (searchContext.hasSortParameters()) {
                        // Sorting results of a system-level search is limited, and has a different logic path
                        // than other sorted searches.
                        if (resourceType.equals(Resource.class)) {
                           resources = this.convertResourceDTOList(this.resourceDao.search(query), resourceType, elements);
                        }
                        else {
                            sortedIdList = this.resourceDao.searchForIds(query);
                            resources = this.buildSortedFhirResources(context, resourceType, sortedIdList, elements);
                        }
                    }
                    else {
                        unsortedResultsList = this.getResourceDao().search(query);
                        resources = this.convertResourceDTOList(unsortedResultsList, resourceType, elements);
                    }  
                }
            }
            
            MultiResourceResult<Resource> result = new MultiResourceResult.Builder<>()
                    .success(true)
                    .resource(resources)
                    .build();
            
            return result;
        }
        catch(FHIRPersistenceException e) {
            throw e;
        }
        catch(Throwable e) {
            FHIRPersistenceException fx = new FHIRPersistenceException("Unexpected error while performing a search operation.");
            log.log(Level.SEVERE, fx.getMessage(), e);
            throw fx;
        }
        finally {
            log.exiting(CLASSNAME, METHODNAME);
        }
    }
    
    protected ParameterDAO getParameterDao() {
        return this.parameterDao;
    }

    protected ResourceNormalizedDAO getResourceDao() {
        return resourceDao;
    }

    /* (non-Javadoc)
     * @see com.ibm.fhir.persistence.FHIRPersistence#delete(com.ibm.fhir.persistence.context.FHIRPersistenceContext, Class<? extends Resource> resourceType, java.lang.String)
     */
    @Override
    public <T extends Resource> SingleResourceResult<T> delete(FHIRPersistenceContext context, Class<T> resourceType, String logicalId) throws FHIRPersistenceException {
        final String METHODNAME = "delete";
        log.entering(CLASSNAME, METHODNAME);
        
        
        com.ibm.fhir.persistence.jdbc.dto.Resource existingResourceDTO = null;
        T existingResource = null;
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        
        Resource.Builder resourceBuilder;
        
        try {
            existingResourceDTO = this.getResourceDao().read(logicalId, resourceType.getSimpleName());
            
            if (existingResourceDTO != null) {
                if (existingResourceDTO.isDeleted()) {
                    existingResource = this.convertResourceDTO(existingResourceDTO, resourceType, null);
                    resourceBuilder = existingResource.toBuilder();
                    
                    SingleResourceResult<T> result = new SingleResourceResult.Builder<T>()
                            .success(true)
                            .resource(existingResource)
                            .build();
                    
                    return result;
                }
                else {
                    existingResource = this.convertResourceDTO(existingResourceDTO, resourceType, null);
                    
                    // If replication info is required, add the value of the patientId, siteId, and subjectId extensions 
                    // to the RepInfo
                    if (this.resourceDao.isRepInfoRequired()) {
                        ReplicationUtil.addExtensionDataToRepInfo(context, existingResource);
                    }
                    
                    // Resources are immutable, so we need a new builder to update it (since R4)
                    resourceBuilder = existingResource.toBuilder();
    
                    int newVersionNumber = existingResourceDTO.getVersionId() + 1;
                    Instant lastUpdated = Instant.now(ZoneOffset.UTC);
                    
                    // Update the soft-delete resource to reflect the new version and lastUpdated values.
                    Meta meta = existingResource.getMeta();
                    Meta.Builder metaBuilder = meta == null ? Meta.builder() : meta.toBuilder();
                    metaBuilder.versionId(Id.of(Integer.toString(newVersionNumber)));
                    metaBuilder.lastUpdated(lastUpdated);
                    resourceBuilder.meta(metaBuilder.build());
                    
                    
                    @SuppressWarnings("unchecked")
                    T updatedResource = (T) resourceBuilder.build();
    
                    // Create a new Resource DTO instance to represent the deleted version.
                    com.ibm.fhir.persistence.jdbc.dto.Resource resourceDTO = new com.ibm.fhir.persistence.jdbc.dto.Resource();
                    resourceDTO.setLogicalId(logicalId);
                    resourceDTO.setVersionId(newVersionNumber);
                    
                    // Serialize and compress the Resource
                    GZIPOutputStream zipStream = new GZIPOutputStream(stream);
                    FHIRGenerator.generator(Format.JSON, false).generate(updatedResource, zipStream);
                    zipStream.finish();
                    resourceDTO.setData(stream.toByteArray());
                    zipStream.close();
                    
                    Timestamp timestamp = FHIRUtilities.convertToTimestamp(lastUpdated.getValue());
                    resourceDTO.setLastUpdated(timestamp);
                    resourceDTO.setResourceType(resourceType.getSimpleName());
                    resourceDTO.setDeleted(true);
    
                    // Persist the logically deleted Resource DTO.
                    this.getResourceDao().setPersistenceContext(context);
                    this.getResourceDao().insert(resourceDTO, null, null);
                    
                    if (log.isLoggable(Level.FINE)) {
                        log.fine("Persisted FHIR Resource '" + resourceDTO.getResourceType() + "/" + resourceDTO.getLogicalId() + "' id=" + resourceDTO.getId()
                                    + ", version=" + resourceDTO.getVersionId());
                    }
                    
                    SingleResourceResult<T> result = new SingleResourceResult.Builder<T>()
                            .success(true)
                            .resource(updatedResource)
                            .build();
                    
                    return result;
                }
            }
            else {
                // issue fhir-527. Need to return not found
                throw new FHIRPersistenceResourceNotFoundException("resource does not exist: " + resourceType.getSimpleName() + ":" + logicalId);
            }
        }
        catch(FHIRPersistenceFKVException e) {
            log.log(Level.SEVERE, this.performCacheDiagnostics());
            throw e;
        }
        catch(FHIRPersistenceException e) {
            throw e;
        }
        catch(Throwable e) {
            FHIRPersistenceException fx = new FHIRPersistenceException("Unexpected error while performing a delete operation.");
            log.log(Level.SEVERE, fx.getMessage(), e);
            throw fx;
        }
        finally {
            log.exiting(CLASSNAME, METHODNAME);
        }
    }

    /* (non-Javadoc)
     * @see com.ibm.fhir.persistence.FHIRPersistence#read(com.ibm.fhir.persistence.context.FHIRPersistenceContext, java.lang.Class, java.lang.String)
     */
    @Override
    public <T extends Resource> SingleResourceResult<T> read(FHIRPersistenceContext context, Class<T> resourceType, String logicalId)
                            throws FHIRPersistenceException, FHIRPersistenceResourceDeletedException {
        final String METHODNAME = "read";
        log.entering(CLASSNAME, METHODNAME);
        
        T resource = null;
        com.ibm.fhir.persistence.jdbc.dto.Resource resourceDTO = null;
                
        try {
            resourceDTO = this.getResourceDao().read(logicalId, resourceType.getSimpleName());
            if (resourceDTO != null && resourceDTO.isDeleted() && !context.includeDeleted()) {
                throw new FHIRPersistenceResourceDeletedException("Resource '" + resourceType.getSimpleName() + "/" + logicalId + "' is deleted.");
            }
            resource = this.convertResourceDTO(resourceDTO, resourceType, null);
            
            SingleResourceResult<T> result = new SingleResourceResult.Builder<T>()
                    .success(true)
                    .resource(resource)
                    .build();
            
            return result;
        }
        catch(FHIRPersistenceResourceDeletedException e) {
            throw e;
        }
        catch(Throwable e) {
            FHIRPersistenceException fx = new FHIRPersistenceException("Unexpected error while performing a read operation.");
            log.log(Level.SEVERE, fx.getMessage(), e);
            throw fx;

        }
        finally {
            log.exiting(CLASSNAME, METHODNAME);
        }        
    }

    /* (non-Javadoc)
     * @see com.ibm.fhir.persistence.FHIRPersistence#history(com.ibm.fhir.persistence.context.FHIRPersistenceContext, java.lang.Class, java.lang.String)
     */
    @Override
    public <T extends Resource> MultiResourceResult<T> history(FHIRPersistenceContext context, Class<T> resourceType,
            String logicalId) throws FHIRPersistenceException {
        final String METHODNAME = "history";
        log.entering(CLASSNAME, METHODNAME);
        
        List<T> resources = new ArrayList<>();
        List<com.ibm.fhir.persistence.jdbc.dto.Resource> resourceDTOList;
        Map<String,List<Integer>> deletedResourceVersions = new HashMap<>();
        FHIRHistoryContext historyContext;
        int resourceCount;
        Instant since;
        Timestamp fromDateTime = null;
        int pageSize;
        int lastPageNumber;
        int offset;
                
        try {
            historyContext = context.getHistoryContext();
            historyContext.setDeletedResources(deletedResourceVersions);
            since = historyContext.getSince();
            if (since != null) {
                fromDateTime = FHIRUtilities.convertToTimestamp(since.getValue());
            }
            
            resourceCount = this.getResourceDao().historyCount(resourceType.getSimpleName(), logicalId, fromDateTime);
            historyContext.setTotalCount(resourceCount);
            pageSize = historyContext.getPageSize();
            lastPageNumber = (int) ((resourceCount + pageSize - 1) / pageSize);
            historyContext.setLastPageNumber(lastPageNumber);            
            
            
            
            if (resourceCount > 0) {
                offset = (historyContext.getPageNumber() - 1) * pageSize;
                resourceDTOList = this.getResourceDao().history(resourceType.getSimpleName(), logicalId, fromDateTime, offset, pageSize);
                for (com.ibm.fhir.persistence.jdbc.dto.Resource resourceDTO : resourceDTOList) {
                    if (resourceDTO.isDeleted()) {
                        deletedResourceVersions.putIfAbsent(logicalId, new ArrayList<Integer>());
                        deletedResourceVersions.get(logicalId).add(resourceDTO.getVersionId());
                    }
                }
                log.log(Level.FINE, "deletedResourceVersions=" + deletedResourceVersions);
                resources = this.convertResourceDTOList(resourceDTOList, resourceType);
            } 
            
            MultiResourceResult<T> result = new MultiResourceResult.Builder<T>()
                    .success(true)
                    .resource(resources)
                    .build();
            
            return result;
        }
        catch(FHIRPersistenceException e) {
            throw e;
        }
        catch(Throwable e) {
            FHIRPersistenceException fx = new FHIRPersistenceException("Unexpected error while performing a history operation.");
            log.log(Level.SEVERE, fx.getMessage(), e);
            throw fx;
        }
        finally {
            log.exiting(CLASSNAME, METHODNAME);
        }
    }

    /* (non-Javadoc)
     * @see com.ibm.fhir.persistence.FHIRPersistence#vread(com.ibm.fhir.persistence.context.FHIRPersistenceContext, java.lang.Class, java.lang.String, java.lang.String)
     */
    @Override
    public <T extends Resource> SingleResourceResult<T> vread(FHIRPersistenceContext context, Class<T> resourceType, String logicalId, String versionId) 
                        throws FHIRPersistenceException {
        final String METHODNAME = "vread";
        log.entering(CLASSNAME, METHODNAME);
        
        T resource = null;
        com.ibm.fhir.persistence.jdbc.dto.Resource resourceDTO = null;
        int version;

        try {
            version = Integer.parseInt(versionId);
            resourceDTO = this.getResourceDao().versionRead(logicalId, resourceType.getSimpleName(), version);
            if (resourceDTO != null && resourceDTO.isDeleted()) {
                throw new FHIRPersistenceResourceDeletedException("Resource '" + resourceType.getSimpleName() + "/" + logicalId + "' version " + versionId + " is deleted.");
            }
            resource = this.convertResourceDTO(resourceDTO, resourceType, null);
            
            SingleResourceResult<T> result = new SingleResourceResult.Builder<T>()
                    .success(true)
                    .resource(resource)
                    .build();
            
            return result;
        }
        catch(FHIRPersistenceResourceDeletedException e) {
            throw e;
        }
        catch (NumberFormatException e) {
            throw new FHIRPersistenceException("Invalid version id specified for vread operation: " + versionId);
        }
        catch(Throwable e) {
            FHIRPersistenceException fx = new FHIRPersistenceException("Unexpected error while performing a version read operation.");
            log.log(Level.SEVERE, fx.getMessage(), e);
            throw fx;
        }
        finally {
            log.exiting(CLASSNAME, METHODNAME);
        }
    }
    
    /**
     * Returns a List of Resource DTOs corresponding to the passed list of Resource IDs.
     * @param resourceType The type of resource being queried.
     * @param sortedIdList A sorted list of Resource IDs.
     * @return List - A list of ResourceDTOs
     * @throws FHIRPersistenceDataAccessException
     * @throws FHIRPersistenceDBConnectException
     */
    @Override
    protected List<com.ibm.fhir.persistence.jdbc.dto.Resource> getResourceDTOs(
            Class<? extends Resource> resourceType, List<Long> sortedIdList) throws FHIRPersistenceDataAccessException, FHIRPersistenceDBConnectException {
         
        return this.getResourceDao().searchByIds(resourceType.getSimpleName(), sortedIdList);
    }
    
    /**
     * Converts the passed Resource Data Transfer Object collection to a collection of FHIR Resource objects.
     * @param resourceDTOList
     * @param resourceType
     * @return
     * @throws FHIRException
     * @throws IOException 
     */
    protected List<Resource> convertResourceDTOList(List<com.ibm.fhir.persistence.jdbc.dto.Resource> resourceDTOList, 
            Class<? extends Resource> resourceType, List<String> elements) throws FHIRException, IOException {
        final String METHODNAME = "convertResourceDTO List";
        log.entering(CLASSNAME, METHODNAME);
        
        List<Resource> resources = new ArrayList<>();
        try {
            for (com.ibm.fhir.persistence.jdbc.dto.Resource resourceDTO : resourceDTOList) {
                Resource existingResource = this.convertResourceDTO(resourceDTO, resourceType, elements);
                if (resourceDTO.isDeleted()) {
                    Resource deletedResourceMarker = FHIRPersistenceUtil.createDeletedResourceMarker(existingResource);
                    ReplicationUtil.addExtensionDataToResource(existingResource, deletedResourceMarker);
                    resources.add(deletedResourceMarker);
                } else {
                    resources.add(existingResource);
                }
            }
        }
        finally {
            log.exiting(CLASSNAME, METHODNAME);
        }
        return resources;
    }
    
   /**
     * Calls some cache analysis methods and aggregates the output into a single String.
     * @return
     */
    private String performCacheDiagnostics() {
        
        StringBuffer diags = new StringBuffer();
        diags.append(ParameterNamesCache.dumpCacheContents()).append(ParameterNamesCache.reportCacheDiscrepancies(this.parameterDao));
        diags.append(CodeSystemsCache.dumpCacheContents()).append(CodeSystemsCache.reportCacheDiscrepancies(this.parameterDao));
        diags.append(ResourceTypesCache.dumpCacheContents()).append(ResourceTypesCache.reportCacheDiscrepancies(this.resourceDao));
        
        return diags.toString();
    }
    
    /**
     * Looks up and returns an instance of TransactionSynchronizationRegistry, which is used in support of writing committed
     * data to JDBC PL in-memory caches.
     * @return TransactionSynchronizationRegistry
     * @throws FHIRPersistenceException
     */
    private TransactionSynchronizationRegistry getTrxSynchRegistry() throws FHIRPersistenceException {
        
        InitialContext ctxt;
        
        if (this.trxSynchRegistry == null) {
            try {
                ctxt = new InitialContext();
                this.trxSynchRegistry = (TransactionSynchronizationRegistry) ctxt.lookup(TRX_SYNCH_REG_JNDI_NAME);
            }
            catch(Throwable e) {
                FHIRPersistenceException fx = new FHIRPersistenceException("Failed to acquire TrxSynchRegistry service");
                log.log(Level.SEVERE, fx.getMessage(), e);
                throw fx;
            }
        }
        
        return this.trxSynchRegistry;
    }
    
    /**
     * Extracts search parameters for the passed FHIR Resource.
     * @param fhirResource - Some FHIR Resource
     * @param resourceDTO - A Resource DTO representation of the passed FHIR Resource.
     * @throws Exception 
     */
    private List<Parameter> extractSearchParameters(Resource fhirResource, com.ibm.fhir.persistence.jdbc.dto.Resource resourceDTO) 
                 throws Exception {
        final String METHODNAME = "extractSearchParameters";
        log.entering(CLASSNAME, METHODNAME);
        
        Map<SearchParameter, List<FHIRPathNode>> map;
        String code;
        String type;
        String expression;
        
        List<Parameter> allParameters = new ArrayList<>();
        
        try {
            map = SearchUtil.extractParameterValues(fhirResource);
            
            for (Entry<SearchParameter, List<FHIRPathNode>> entry : map.entrySet()) {
                 
                code = entry.getKey().getCode().getValue();
                type = entry.getKey().getType().getValue();
                expression = entry.getKey().getExpression().getValue();
                
                if (log.isLoggable(Level.FINE)) {
                    log.fine("Processing SearchParameter code: " + code + ", type: " + type + ", expression: " + expression);
                }
                
                JDBCParameterBuildingVisitor parameterBuilder = new JDBCParameterBuildingVisitor(code);
                
                List<FHIRPathNode> values = entry.getValue();
                
                for (FHIRPathNode value : values) {
                    if (value.isElementNode()) {
                        // parameterBuilder aggregates the results for later retrieval
                        value.asElementNode().element().accept(parameterBuilder);
                    } else if (value.isPrimitiveValue()){
                        Parameter p = processPrimitiveValue(value.asPrimitiveValue());
                        p.setName(code);
                        p.setType(Type.fromValue(type));
                        p.setResourceId(resourceDTO.getId());
                        p.setResourceType(fhirResource.getClass().getSimpleName());
                        allParameters.add(p);
                        if (log.isLoggable(Level.FINE)) {
                            log.fine("Extracted Parameter '" + p.getName() + "' from Resource.");
                        }
                    } else {
                        // log and continue
                        if (log.isLoggable(Level.FINE)) {
                            log.fine("Unable to extract value from '" + value.path() +
                                    "'; search parameter value extraction can only be performed on Elements and primitive values.");
                        }
                        // TODO: return this as a OperationOutcomeIssue with severity of WARNING
                        continue;
                    }
                }
                // retrieve the list of parameters built from all the FHIRPathElementNode values 
                List<Parameter> parameters = parameterBuilder.getResult();
                for (Parameter p : parameters) {
                    p.setType(Type.fromValue(type));
                    p.setResourceId(resourceDTO.getId());
                    p.setResourceType(fhirResource.getClass().getSimpleName());
                    allParameters.add(p);
                    if (log.isLoggable(Level.FINE)) {
                        log.fine("Extracted Parameter '" + p.getName() + "' from Resource.");
                    }
                }
            }
        }
        finally {
            log.exiting(CLASSNAME, METHODNAME);
        }
        return allParameters;
    }

    /**
     * Create a Parameter DTO from the primitive value.
     * Note: this method only sets the value; 
     * caller is responsible for setting all other fields on the created Parameter.
     */
    private Parameter processPrimitiveValue(FHIRPathPrimitiveValue primitiveValue) {
        Parameter p = new Parameter();
        if (primitiveValue.isBooleanValue()) {
            if (primitiveValue.asBooleanValue()._boolean()) {
                p.setValueCode("true");
            } else {
                p.setValueCode("false");
            }
        } else if (primitiveValue.isDateTimeValue()) {
            p.setValueDate(Timestamp.from(QueryBuilderUtil.getInstantFromPartial(primitiveValue.asDateTimeValue().dateTime())));
        } else if (primitiveValue.isStringValue()) {
            p.setValueString(primitiveValue.asStringValue().string());
        } else if (primitiveValue.isTimeValue()) {
            p.setValueDate(Timestamp.from(QueryBuilderUtil.getInstantFromPartial(primitiveValue.asTimeValue().time())));
        } else if (primitiveValue.isNumberValue()) {
            p.setValueNumber(primitiveValue.asNumberValue().decimal());
        }
        return p;
    }

}