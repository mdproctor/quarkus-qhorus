package io.casehub.qhorus.runtime.config;

import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "casehub.qhorus")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface QhorusConfig {

    /** Cleanup and data retention settings. */
    Cleanup cleanup();

    /** Agent Card fields served at /.well-known/agent-card.json. */
    AgentCard agentCard();

    /** A2A compatibility endpoint settings. */
    A2a a2a();

    /** Watchdog alerting settings (optional module). */
    Watchdog watchdog();

    /** Attestation confidence values written on terminal commitment outcomes. */
    Attestation attestation();

    /** Reactive dual-stack settings (build-time fixed — read QhorusBuildConfig at build time). */
    Reactive reactive();

    interface Reactive {
        /**
         * When true, the reactive dual-stack is active (set at build time via
         * casehub.qhorus.reactive.enabled). Runtime reads of this value reflect
         * what was set when the application was compiled.
         */
        @WithDefault("false")
        boolean enabled();
    }

    interface Watchdog {
        /**
         * When true, enables the watchdog MCP tools and condition evaluation scheduler.
         * Disabled by default — opt-in.
         */
        @WithDefault("false")
        boolean enabled();

        /** Interval in seconds between watchdog evaluation runs. Default: 60. */
        @WithDefault("60")
        int checkIntervalSeconds();
    }

    interface A2a {
        /**
         * When true, exposes A2A-compatible REST endpoints at /a2a/*.
         * Disabled by default — opt-in to avoid unintended exposure.
         */
        @WithDefault("false")
        boolean enabled();
    }

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
    }

    interface Attestation {
        /**
         * Confidence score (0.0–1.0) for the SOUND attestation written when a DONE message
         * closes a COMMAND commitment. Default: 0.7.
         */
        @WithDefault("0.7")
        double doneConfidence();

        /**
         * Confidence score (0.0–1.0) for the FLAGGED attestation written when a FAILURE
         * message closes a COMMAND commitment. Default: 0.6.
         */
        @WithDefault("0.6")
        double failureConfidence();

        /**
         * Confidence score (0.0–1.0) for the FLAGGED attestation written when a DECLINE
         * message closes a COMMAND commitment. Default: 0.4.
         */
        @WithDefault("0.4")
        double declineConfidence();
    }
}
