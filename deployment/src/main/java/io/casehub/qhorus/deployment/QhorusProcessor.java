package io.casehub.qhorus.deployment;

import java.util.function.BooleanSupplier;

import io.casehub.qhorus.runtime.api.ReactiveA2AResource;
import io.casehub.qhorus.runtime.api.ReactiveAgentCardResource;
import io.casehub.qhorus.runtime.mcp.ReactiveQhorusMcpTools;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;

/**
 * Quarkus build-time processor for the Qhorus extension.
 * Registers the "qhorus" feature and, when reactive is enabled, ensures
 * reactive beans are not pruned by Arc's unused-bean removal.
 */
class QhorusProcessor {

    private static final String FEATURE = "qhorus";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    /**
     * When {@code casehub.qhorus.reactive.enabled=true}, marks reactive MCP tool and
     * REST resource beans as unremovable so Arc does not prune them.
     * The actual activation is handled by {@code @IfBuildProperty} on each bean class.
     */
    @BuildStep(onlyIf = ReactiveEnabled.class)
    UnremovableBeanBuildItem markReactiveBeans() {
        return UnremovableBeanBuildItem.beanTypes(
                ReactiveQhorusMcpTools.class,
                ReactiveAgentCardResource.class,
                ReactiveA2AResource.class);
    }

    /** Activates when {@code casehub.qhorus.reactive.enabled=true}. */
    static final class ReactiveEnabled implements BooleanSupplier {

        QhorusBuildConfig config;

        @Override
        public boolean getAsBoolean() {
            return config.reactive().enabled();
        }
    }
}
