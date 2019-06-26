/**
 * (C) Copyright IBM Corp. 2019
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.ibm.watsonhealth.fhir.model.type;

import java.util.Collection;

public class SupplyDeliveryStatus extends Code {
    /**
     * In Progress
     */
    public static final SupplyDeliveryStatus IN_PROGRESS = SupplyDeliveryStatus.of(ValueSet.IN_PROGRESS);

    /**
     * Delivered
     */
    public static final SupplyDeliveryStatus COMPLETED = SupplyDeliveryStatus.of(ValueSet.COMPLETED);

    /**
     * Abandoned
     */
    public static final SupplyDeliveryStatus ABANDONED = SupplyDeliveryStatus.of(ValueSet.ABANDONED);

    /**
     * Entered In Error
     */
    public static final SupplyDeliveryStatus ENTERED_IN_ERROR = SupplyDeliveryStatus.of(ValueSet.ENTERED_IN_ERROR);

    private SupplyDeliveryStatus(Builder builder) {
        super(builder);
    }

    public static SupplyDeliveryStatus of(java.lang.String value) {
        return SupplyDeliveryStatus.builder().value(value).build();
    }

    public static SupplyDeliveryStatus of(ValueSet value) {
        return SupplyDeliveryStatus.builder().value(value).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        Builder builder = new Builder();
        builder.id = id;
        builder.extension.addAll(extension);
        builder.value = value;
        return builder;
    }

    public static class Builder extends Code.Builder {
        private Builder() {
            super();
        }

        @Override
        public Builder id(java.lang.String id) {
            return (Builder) super.id(id);
        }

        @Override
        public Builder extension(Extension... extension) {
            return (Builder) super.extension(extension);
        }

        @Override
        public Builder extension(Collection<Extension> extension) {
            return (Builder) super.extension(extension);
        }

        @Override
        public Builder value(java.lang.String value) {
            return (Builder) super.value(ValueSet.from(value).value());
        }

        public Builder value(ValueSet value) {
            return (Builder) super.value(value.value());
        }

        @Override
        public SupplyDeliveryStatus build() {
            return new SupplyDeliveryStatus(this);
        }
    }

    public enum ValueSet {
        /**
         * In Progress
         */
        IN_PROGRESS("in-progress"),

        /**
         * Delivered
         */
        COMPLETED("completed"),

        /**
         * Abandoned
         */
        ABANDONED("abandoned"),

        /**
         * Entered In Error
         */
        ENTERED_IN_ERROR("entered-in-error");

        private final java.lang.String value;

        ValueSet(java.lang.String value) {
            this.value = value;
        }

        public java.lang.String value() {
            return value;
        }

        public static ValueSet from(java.lang.String value) {
            for (ValueSet c : ValueSet.values()) {
                if (c.value.equals(value)) {
                    return c;
                }
            }
            throw new IllegalArgumentException(value);
        }
    }
}