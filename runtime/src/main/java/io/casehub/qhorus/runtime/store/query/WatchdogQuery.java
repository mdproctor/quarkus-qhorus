package io.casehub.qhorus.runtime.store.query;

import io.casehub.qhorus.runtime.watchdog.Watchdog;

public final class WatchdogQuery {

    private final String conditionType;

    private WatchdogQuery(Builder b) {
        this.conditionType = b.conditionType;
    }

    public static WatchdogQuery all() {
        return new Builder().build();
    }

    public static WatchdogQuery byConditionType(String conditionType) {
        return new Builder().conditionType(conditionType).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public String conditionType() {
        return conditionType;
    }

    public boolean matches(Watchdog w) {
        if (conditionType != null && !conditionType.equals(w.conditionType)) {
            return false;
        }
        return true;
    }

    public Builder toBuilder() {
        return new Builder().conditionType(conditionType);
    }

    public static final class Builder {
        private String conditionType;

        public Builder conditionType(String v) {
            this.conditionType = v;
            return this;
        }

        public WatchdogQuery build() {
            return new WatchdogQuery(this);
        }
    }
}
