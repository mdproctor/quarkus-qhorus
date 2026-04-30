package io.casehub.qhorus.runtime.store.query;

import io.casehub.qhorus.runtime.data.SharedData;

public final class DataQuery {

    private final String createdBy;
    private final Boolean complete;

    private DataQuery(Builder b) {
        this.createdBy = b.createdBy;
        this.complete = b.complete;
    }

    public static DataQuery all() {
        return new Builder().build();
    }

    public static DataQuery completeOnly() {
        return new Builder().complete(true).build();
    }

    public static DataQuery byCreator(String instanceId) {
        return new Builder().createdBy(instanceId).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public String createdBy() {
        return createdBy;
    }

    public Boolean complete() {
        return complete;
    }

    public boolean matches(SharedData d) {
        if (createdBy != null && !createdBy.equals(d.createdBy)) {
            return false;
        }
        if (complete != null && complete != d.complete) {
            return false;
        }
        return true;
    }

    public Builder toBuilder() {
        return new Builder().createdBy(createdBy).complete(complete);
    }

    public static final class Builder {
        private String createdBy;
        private Boolean complete;

        public Builder createdBy(String v) {
            this.createdBy = v;
            return this;
        }

        public Builder complete(Boolean v) {
            this.complete = v;
            return this;
        }

        public DataQuery build() {
            return new DataQuery(this);
        }
    }
}
