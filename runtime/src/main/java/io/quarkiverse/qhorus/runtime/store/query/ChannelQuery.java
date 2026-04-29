package io.quarkiverse.qhorus.runtime.store.query;

import io.quarkiverse.qhorus.api.channel.ChannelSemantic;
import io.quarkiverse.qhorus.runtime.channel.Channel;

public final class ChannelQuery {

    private final String namePattern;
    private final ChannelSemantic semantic;
    private final Boolean paused;

    private ChannelQuery(Builder b) {
        this.namePattern = b.namePattern;
        this.semantic = b.semantic;
        this.paused = b.paused;
    }

    public static ChannelQuery all() {
        return new Builder().build();
    }

    public static ChannelQuery pausedOnly() {
        return new Builder().paused(true).build();
    }

    public static ChannelQuery byName(String pattern) {
        return new Builder().namePattern(pattern).build();
    }

    public static ChannelQuery bySemantic(ChannelSemantic s) {
        return new Builder().semantic(s).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public String namePattern() {
        return namePattern;
    }

    public ChannelSemantic semantic() {
        return semantic;
    }

    public Boolean paused() {
        return paused;
    }

    public boolean matches(Channel ch) {
        if (paused != null && paused != ch.paused) {
            return false;
        }
        if (semantic != null && !semantic.equals(ch.semantic)) {
            return false;
        }
        if (namePattern != null && (ch.name == null || !ch.name.matches(namePattern.replace("*", ".*")))) {
            return false;
        }
        return true;
    }

    public Builder toBuilder() {
        return new Builder().namePattern(namePattern).semantic(semantic).paused(paused);
    }

    public static final class Builder {
        private String namePattern;
        private ChannelSemantic semantic;
        private Boolean paused;

        public Builder namePattern(String v) {
            this.namePattern = v;
            return this;
        }

        public Builder semantic(ChannelSemantic v) {
            this.semantic = v;
            return this;
        }

        public Builder paused(Boolean v) {
            this.paused = v;
            return this;
        }

        public ChannelQuery build() {
            return new ChannelQuery(this);
        }
    }
}
