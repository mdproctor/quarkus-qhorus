package io.casehub.qhorus.runtime.store.query;

import java.time.Instant;

import io.casehub.qhorus.runtime.instance.Instance;

public final class InstanceQuery {

    private final String capability;
    private final String status;
    private final Instant staleOlderThan;

    private InstanceQuery(Builder b) {
        this.capability = b.capability;
        this.status = b.status;
        this.staleOlderThan = b.staleOlderThan;
    }

    public static InstanceQuery all() {
        return new Builder().build();
    }

    public static InstanceQuery online() {
        return new Builder().status("online").build();
    }

    public static InstanceQuery byCapability(String tag) {
        return new Builder().capability(tag).build();
    }

    public static InstanceQuery staleOlderThan(Instant threshold) {
        return new Builder().staleOlderThan(threshold).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public String capability() {
        return capability;
    }

    public String status() {
        return status;
    }

    public Instant staleOlderThan() {
        return staleOlderThan;
    }

    /**
     * Matches an Instance against this query's scalar predicates.
     * Capability filtering requires a join to the Capability table and is applied by the store.
     */
    public boolean matches(Instance inst) {
        if (status != null && !status.equals(inst.status)) {
            return false;
        }
        if (staleOlderThan != null && (inst.lastSeen == null || !inst.lastSeen.isBefore(staleOlderThan))) {
            return false;
        }
        return true;
    }

    public Builder toBuilder() {
        return new Builder().capability(capability).status(status).staleOlderThan(staleOlderThan);
    }

    public static final class Builder {
        private String capability;
        private String status;
        private Instant staleOlderThan;

        public Builder capability(String v) {
            this.capability = v;
            return this;
        }

        public Builder status(String v) {
            this.status = v;
            return this;
        }

        public Builder staleOlderThan(Instant v) {
            this.staleOlderThan = v;
            return this;
        }

        public InstanceQuery build() {
            return new InstanceQuery(this);
        }
    }
}
