package io.quarkiverse.qhorus.runtime.ledger;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkiverse.qhorus.api.spi.InstanceActorIdProvider;
import io.quarkus.arc.DefaultBean;

/**
 * Identity implementation of {@link InstanceActorIdProvider} — returns the instanceId
 * unchanged. Active unless a higher-priority {@code @Alternative} is registered.
 *
 * <p>
 * Refs #124.
 */
@DefaultBean
@ApplicationScoped
public class DefaultInstanceActorIdProvider implements InstanceActorIdProvider {

    @Override
    public String resolve(final String instanceId) {
        return instanceId;
    }
}
