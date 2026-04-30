package io.casehub.qhorus.deployment;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Build-time configuration for Qhorus. Properties here are fixed at build time
 * and cannot be overridden at runtime.
 */
@ConfigMapping(prefix = "casehub.qhorus")
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
public interface QhorusBuildConfig {

    /** Reactive dual-stack settings. */
    Reactive reactive();

    interface Reactive {
        /**
         * When true, activates the reactive dual-stack: ReactiveQhorusMcpTools,
         * ReactiveAgentCardResource, and ReactiveA2AResource replace their blocking
         * counterparts. Default: false (blocking stack active).
         */
        @WithDefault("false")
        boolean enabled();
    }
}
