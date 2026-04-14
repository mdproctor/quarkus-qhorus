package io.quarkiverse.qhorus.runtime.config;

import java.util.Optional;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "quarkus.qhorus")
public interface QhorusConfig {

    /** Cleanup and data retention settings. */
    Cleanup cleanup();

    /** Agent Card fields served at /.well-known/agent-card.json. */
    AgentCard agentCard();

    interface AgentCard {
        /** Display name of this Qhorus deployment. */
        @WithDefault("Qhorus Agent Mesh")
        String name();

        /** Human-readable description of what this deployment provides. */
        @WithDefault("Peer-to-peer agent communication mesh — channels, messages, shared data, presence")
        String description();

        /** Public URL of this deployment. Should be set per-deployment; absent when not configured. */
        Optional<String> url();

        /** Version of this Qhorus deployment. */
        @WithDefault("1.0.0")
        String version();
    }

    interface Cleanup {

        /** How long (seconds) before an instance is considered stale. Default: 120. */
        @WithDefault("120")
        int staleInstanceSeconds();

        /** Days to retain old messages and shared data before purging. Default: 7. */
        @WithDefault("7")
        int dataRetentionDays();

        /** Interval in seconds between runs of the PendingReply expiry cleanup job. Default: 60. */
        @WithDefault("60")
        int pendingReplyCheckSeconds();
    }
}
