/**
 * (C) Copyright IBM Corp. 2016,2017,2018,2019
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.ibm.watsonhealth.fhir.client.impl;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.Properties;

import javax.crypto.spec.SecretKeySpec;
import javax.json.JsonObject;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;

import com.ibm.watsonhealth.fhir.client.FHIRResponse;
import com.ibm.watsonhealth.fhir.client.FHIRClient;
import com.ibm.watsonhealth.fhir.core.FHIRUtilities;
import com.ibm.watsonhealth.fhir.core.MediaType;
import com.ibm.watsonhealth.fhir.model.Resource;
import com.ibm.watsonhealth.fhir.provider.FHIRJsonProvider;
import com.ibm.watsonhealth.fhir.provider.FHIRProvider;

/**
 * Provides an implementation of the FHIRClient interface, which can be used as a high-level API for invoking FHIR
 * REST APIs.
 */
public class FHIRClientImpl implements FHIRClient {
    
    private static final String KEYSTORE_TYPE_JKS = "JKS";
    private static final String KEYSTORE_TYPE_JCEKS = "JCEKS";
    private static final String ENCRYPTION_ALGORITHM_AES = "AES";

    private Client client = null;
    private Properties clientProperties = null;
    private String baseEndpointURL = null;
    
    private boolean basicAuthEnabled = false;
    private String basicAuthUsername = null;
    private String basicAuthPassword = null;
    
    private boolean clientAuthEnabled = false;
    private String trustStoreLocation = null;
    private String trustStorePassword = null;
    private String keyStoreLocation = null;
    private String keyStorePassword = null;
    private String keyStoreKeyPassword = null;
    
    private KeyStore trustStore = null;
    private KeyStore keyStore = null;
    
    private boolean encryptionEnabled = false;
    private String encKeyStoreLocation = null;
    private String encKeyStorePassword = null;
    private String encKeyPassword = null;
    private SecretKeySpec encryptionKey = null;

    protected FHIRClientImpl() {
    }

    public FHIRClientImpl(Properties props) throws Exception {
        initProperties(props);
    }


    /* (non-Javadoc)
     * @see com.ibm.watsonhealth.fhir.client.FHIRClient#metadata()
     */
    @Override
    public FHIRResponse metadata() throws Exception {
        WebTarget endpoint = getWebTarget();
        Response response = endpoint.path("metadata").request().get();
        return new FHIRResponseImpl(response);
    }

    /* (non-Javadoc)
     * @see com.ibm.watsonhealth.fhir.client.FHIRClient#create(com.ibm.watsonhealth.fhir.model.Resource)
     */
    @Override
    public FHIRResponse create(Resource resource) throws Exception {
        String resourceType = resource.getClass().getSimpleName();
        WebTarget endpoint = getWebTarget();
        Entity<Resource> entity = Entity.entity(resource, MediaType.APPLICATION_JSON_FHIR);
        Response response = endpoint.path(resourceType).request().post(entity);
        return new FHIRResponseImpl(response);
    }

    /* (non-Javadoc)
     * @see com.ibm.watsonhealth.fhir.client.FHIRClient#create(javax.json.JsonObject, java.lang.String)
     */
    @Override
    public FHIRResponse create(JsonObject resource) throws Exception {
        String resourceType = resource.getString("resourceType");
        if (resourceType == null || resourceType.isEmpty()) {
            throw new IllegalArgumentException("Unable to retrieve the resource type resource.");
        }
        WebTarget endpoint = getWebTarget();
        Entity<JsonObject> entity = Entity.entity(resource, MediaType.APPLICATION_JSON_FHIR);
        Response response = endpoint.path(resourceType).request().post(entity);
        return new FHIRResponseImpl(response);
    }
    

    /* (non-Javadoc)
     * @see com.ibm.watsonhealth.fhir.client.FHIRClient#update(java.lang.Object, java.lang.Class, java.lang.String)
     */
    @Override
    public FHIRResponse update(Resource resource) throws Exception {
        WebTarget endpoint = getWebTarget();
        String resourceType = resource.getClass().getSimpleName();
        String resourceId = (resource.getId() != null ? resource.getId().getValue() : null);
        if (resourceId == null || resourceId.isEmpty()) {
            throw new IllegalArgumentException("Unable to retrieve the id from resource.");
        }
        Entity<Resource> entity = Entity.entity(resource, MediaType.APPLICATION_JSON_FHIR);
        Response response = endpoint.path(resourceType).path(resourceId).request().put(entity);
        return new FHIRResponseImpl(response);
    }

    /* (non-Javadoc)
     * @see com.ibm.watsonhealth.fhir.client.FHIRClient#update(javax.json.JsonObject, java.lang.String, java.lang.String)
     */
    @Override
    public FHIRResponse update(JsonObject resource) throws Exception {
        String resourceType = resource.getString("resourceType");
        if (resourceType == null || resourceType.isEmpty()) {
            throw new IllegalArgumentException("Unable to retrieve the resource type from resource.");
        }
        String resourceId = resource.getString("id");
        if (resourceId == null || resourceId.isEmpty()) {
            throw new IllegalArgumentException("Unable to retrieve the id from resource.");
        }
        WebTarget endpoint = getWebTarget();
        Entity<JsonObject> entity = Entity.entity(resource, MediaType.APPLICATION_JSON_FHIR);
        Response response = endpoint.path(resourceType).path(resourceId).request().put(entity);
        return new FHIRResponseImpl(response);
    }

    /* (non-Javadoc)
     * @see com.ibm.watsonhealth.fhir.client.FHIRClient#read(java.lang.String, java.lang.String)
     */
    @Override
    public FHIRResponse read(String resourceType, String resourceId) throws Exception {
        WebTarget endpoint = getWebTarget();
        Response response = endpoint.path(resourceType).path(resourceId).request().get();
        return new FHIRResponseImpl(response);
    }

    /* (non-Javadoc)
     * @see com.ibm.watsonhealth.fhir.client.FHIRClient#vread(java.lang.String, java.lang.String, int)
     */
    @Override
    public FHIRResponse vread(String resourceType, String resourceId, String versionId) throws Exception {
        WebTarget endpoint = getWebTarget();
        Response response = endpoint.path(resourceType).path(resourceId).path("_history").path(versionId).request().get();
        return new FHIRResponseImpl(response);
    }
    
    protected synchronized Client getClient() throws Exception {
        if (client == null) {
            ClientBuilder cb = ClientBuilder.newBuilder()
                    .register(new FHIRProvider())
                    .register(new FHIRJsonProvider());
            
            // Add support for basic auth if enabled.
            if (isBasicAuthEnabled()) {
                cb = cb.register(new FHIRBasicAuthenticator(getBasicAuthUsername(), getBasicAuthPassword()));
            }
            
            // If using clientauth, then we need to attach our Keystore.
            if (isClientAuthEnabled()) {
                cb = cb.keyStore(getKeyStore(), getKeyStoreKeyPassword());
            }
            
            // If using clientauth or an https endpoint, then we need to attach our Truststore.
            if (isClientAuthEnabled() || usingSSLTransport()) {
                cb = cb.trustStore(getTrustStore());
            }
            
            // Add support for encryption/decryption if enabled.
            if (isEncryptionEnabled()) {
                try {
                    cb = cb.register(new FHIREncryptionClientFilter(getEncryptionKey()));
                } catch (Throwable t) {
                    throw new Exception("Unexpected error while registering encryption client filter: ", t);
                }
            }
            
            // Finally, add a hostname verifier if we're using an ssl transport.
            if (usingSSLTransport()) {
                cb = cb.hostnameVerifier(new HostnameVerifier() {
                    public boolean verify(String s, SSLSession sslSession) {
                        return true;
                    }
                });
            }

            // Save off our cached Client instance.
            client = cb.build();
            
        }
        return client;
    }

    
    /*
     * (non-Javadoc)
     * @see com.ibm.watsonhealth.fhir.client.FHIRClient#getWebTarget()
     */
    @Override
    public WebTarget getWebTarget() throws Exception {
        
        return getClient().target(getBaseEndpointURL());
    }

    /**
     * Process all the required properties found in the Properties object.
     * 
     * @param props
     */
    private void initProperties(Properties props) throws Exception {
        try {
            setClientProperties(props);

            // Get the base endpoint URL and make sure it ends with a /.
            String s = getRequiredProperty(PROPNAME_BASE_URL);
            if (!s.endsWith("/")) {
                s += "/";
            }
            setBaseEndpointURL(s);
            
            // Process the basic auth properties (temporary until client auth is fully working).
            setBasicAuthEnabled(Boolean.parseBoolean(getProperty(PROPNAME_BASIC_AUTH_ENABLED, "false")));
            if (isBasicAuthEnabled()) {
                setBasicAuthUsername(getRequiredProperty(PROPNAME_CLIENT_USERNAME));
                setBasicAuthPassword(FHIRUtilities.decode(getRequiredProperty(PROPNAME_CLIENT_PASSWORD)));
            }
            
            // If necessary, load the truststore-related properties.
            setClientAuthEnabled(Boolean.parseBoolean(getProperty(PROPNAME_CLIENT_AUTH_ENABLED, "false")));
            if (isClientAuthEnabled() || usingSSLTransport()) {
                setTrustStoreLocation(getRequiredProperty(PROPNAME_TRUSTSTORE_LOCATION));
                setTrustStorePassword(FHIRUtilities.decode(getRequiredProperty(PROPNAME_TRUSTSTORE_PASSWORD)));
                setTrustStore(loadKeyStoreFile(getTrustStoreLocation(), getTrustStorePassword(), KEYSTORE_TYPE_JKS));
            }
            
            // If necessary, load the keystore-related properties.
            if (isClientAuthEnabled()) {
                setKeyStoreLocation(getRequiredProperty(PROPNAME_KEYSTORE_LOCATION));
                setKeyStorePassword(FHIRUtilities.decode(getRequiredProperty(PROPNAME_KEYSTORE_PASSWORD)));
                setKeyStoreKeyPassword(FHIRUtilities.decode(getRequiredProperty(PROPNAME_KEYSTORE_KEY_PASSWORD)));
                setKeyStore(loadKeyStoreFile(getKeyStoreLocation(), getKeyStorePassword(), KEYSTORE_TYPE_JKS));
            }
            
            // Process the encryption-related properties.
            setEncryptionEnabled(Boolean.parseBoolean(getProperty(PROPNAME_ENCRYPTION_ENABLED, "false")));
            if (isEncryptionEnabled()) {
                setEncKeyStoreLocation(getRequiredProperty(PROPNAME_ENCRYPTION_KSLOC));
                setEncKeyStorePassword(FHIRUtilities.decode(getRequiredProperty(PROPNAME_ENCRYPTION_KSPW)));
                setEncKeyPassword(FHIRUtilities.decode(getRequiredProperty(PROPNAME_ENCRYPTION_KEYPW)));
                setEncryptionKey(FHIRUtilities.retrieveEncryptionKeyFromKeystore(getEncKeyStoreLocation(), getEncKeyStorePassword(), ENCRYPTION_KEY_ALIAS, getEncKeyPassword(), KEYSTORE_TYPE_JCEKS, ENCRYPTION_ALGORITHM_AES));
            }
        } catch (Throwable t) {
            throw new Exception("Unexpected error while processing client properties.", t);
        }
    }
    
    private boolean usingSSLTransport() {
        return getBaseEndpointURL().startsWith("https:");
    }

    /**
     * Loads the client trust store file for use with https endpoints.
     */
    private KeyStore loadKeyStoreFile(String ksFilename, String ksPassword, String ksType) {
        try {
            KeyStore ks = KeyStore.getInstance(ksType);

            // First, search the classpath for the truststore file.
            InputStream is = null;
            URL tsURL = Thread.currentThread().getContextClassLoader().getResource(ksFilename);
            if (tsURL != null) {
                is = tsURL.openStream();
            }

            // If the classpath search failed, try to open the file directly.
            if (is == null) {
                File tsFile = new File(ksFilename);
                if (tsFile.exists()) {
                    is = new FileInputStream(tsFile);
                }
            }

            // If we couldn't open the file, throw an exception now.
            if (is == null) {
                throw new FileNotFoundException("KeyStore file '" + ksFilename + "' was not found.");
            }

            // Load up the truststore file.
            ks.load(is, ksPassword.toCharArray());

            return ks;
        } catch (KeyStoreException | NoSuchAlgorithmException | CertificateException | IOException e) {
            throw new IllegalStateException("Error loading keystore file '" + ksFilename + "' : " + e);
        }
    }

    /**
     * Retrieves the specified property from the client properties object.
     */
    private String getProperty(String propertyName, String defaultValue) {
        return clientProperties.getProperty(propertyName, defaultValue);
    }

    private String getRequiredProperty(String propertyName) throws Exception {
        String s = getProperty(propertyName, null);
        if (s == null) {
            throw new IllegalStateException("Required property '" + propertyName + "' not found in client properties object.");
        }

        return s;
    }

    private SecretKeySpec getEncryptionKey() {
        return encryptionKey;
    }

    private void setEncryptionKey(SecretKeySpec encryptionKey) {
        this.encryptionKey = encryptionKey;
    }

    private void setClientProperties(Properties clientProperties) {
        this.clientProperties = clientProperties;
    }

    private String getBaseEndpointURL() {
        return baseEndpointURL;
    }

    private void setBaseEndpointURL(String baseEndpointURL) {
        this.baseEndpointURL = baseEndpointURL;
    }

    private String getTrustStoreLocation() {
        return trustStoreLocation;
    }

    private void setTrustStoreLocation(String trustStoreLocation) {
        this.trustStoreLocation = trustStoreLocation;
    }

    private String getTrustStorePassword() {
        return trustStorePassword;
    }

    private void setTrustStorePassword(String trustStorePassword) {
        this.trustStorePassword = trustStorePassword;
    }

    private KeyStore getTrustStore() {
        return trustStore;
    }

    private void setTrustStore(KeyStore trustStore) {
        this.trustStore = trustStore;
    }

    private boolean isEncryptionEnabled() {
        return encryptionEnabled;
    }

    private void setEncryptionEnabled(boolean encryptionEnabled) {
        this.encryptionEnabled = encryptionEnabled;
    }

    private boolean isBasicAuthEnabled() {
        return basicAuthEnabled;
    }

    private void setBasicAuthEnabled(boolean basicAuthEnabled) {
        this.basicAuthEnabled = basicAuthEnabled;
    }

    private String getBasicAuthUsername() {
        return basicAuthUsername;
    }

    private void setBasicAuthUsername(String basicAuthUsername) {
        this.basicAuthUsername = basicAuthUsername;
    }

    private String getBasicAuthPassword() {
        return basicAuthPassword;
    }

    private void setBasicAuthPassword(String basicAuthPassword) {
        this.basicAuthPassword = basicAuthPassword;
    }

    private boolean isClientAuthEnabled() {
        return clientAuthEnabled;
    }

    private void setClientAuthEnabled(boolean clientAuthEnabled) {
        this.clientAuthEnabled = clientAuthEnabled;
    }

    private KeyStore getKeyStore() {
        return keyStore;
    }

    private void setKeyStore(KeyStore keyStore) {
        this.keyStore = keyStore;
    }

    public String getEncKeyStoreLocation() {
        return encKeyStoreLocation;
    }

    public void setEncKeyStoreLocation(String encKeyStoreLocation) {
        this.encKeyStoreLocation = encKeyStoreLocation;
    }

    public String getEncKeyStorePassword() {
        return encKeyStorePassword;
    }

    public void setEncKeyStorePassword(String encKeyStorePassword) {
        this.encKeyStorePassword = encKeyStorePassword;
    }

    public String getEncKeyPassword() {
        return encKeyPassword;
    }

    public void setEncKeyPassword(String encKeyPassword) {
        this.encKeyPassword = encKeyPassword;
    }

    public String getKeyStoreLocation() {
        return keyStoreLocation;
    }

    public void setKeyStoreLocation(String keyStoreLocation) {
        this.keyStoreLocation = keyStoreLocation;
    }

    public String getKeyStorePassword() {
        return keyStorePassword;
    }

    public void setKeyStorePassword(String keyStorePassword) {
        this.keyStorePassword = keyStorePassword;
    }

    public String getKeyStoreKeyPassword() {
        return keyStoreKeyPassword;
    }

    public void setKeyStoreKeyPassword(String keyStoreKeyPassword) {
        this.keyStoreKeyPassword = keyStoreKeyPassword;
    }
}