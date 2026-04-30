package io.casehub.qhorus.runtime.instance;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import io.casehub.qhorus.runtime.store.ReactiveInstanceStore;
import io.casehub.qhorus.runtime.store.query.InstanceQuery;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;

@Alternative
@ApplicationScoped
public class ReactiveInstanceService {

    @Inject
    ReactiveInstanceStore instanceStore;

    /** Convenience overload — no claudony session, not read-only. */
    public Uni<Instance> register(String instanceId, String description, List<String> capabilityTags) {
        return register(instanceId, description, capabilityTags, null, false);
    }

    /** Convenience overload — not read-only. */
    public Uni<Instance> register(String instanceId, String description, List<String> capabilityTags,
            String claudonySessionId) {
        return register(instanceId, description, capabilityTags, claudonySessionId, false);
    }

    /**
     * Register or update an instance. Creates if not found; updates description,
     * status, lastSeen, claudonySessionId, and readOnly if already present.
     * Replaces capability tags on every call — no stale tags accumulate.
     */
    public Uni<Instance> register(String instanceId, String description, List<String> capabilityTags,
            String claudonySessionId, boolean readOnly) {
        return Panache.withTransaction(() -> instanceStore.findByInstanceId(instanceId).flatMap(opt -> {
            Instance instance = opt.orElse(null);
            if (instance == null) {
                instance = new Instance();
                instance.instanceId = instanceId;
            }
            instance.description = description;
            instance.status = "online";
            instance.lastSeen = Instant.now();
            instance.claudonySessionId = claudonySessionId;
            instance.readOnly = readOnly;

            final Instance toSave = instance;
            return instanceStore.put(toSave)
                    .flatMap(saved -> instanceStore.putCapabilities(saved.id, capabilityTags)
                            .map(ignored -> saved));
        }));
    }

    public Uni<Void> heartbeat(String instanceId) {
        return Panache.withTransaction(() -> instanceStore.findByInstanceId(instanceId)
                .invoke(opt -> opt.ifPresent(i -> {
                    i.lastSeen = Instant.now();
                    i.status = "online";
                }))
                .replaceWithVoid());
    }

    public Uni<Optional<Instance>> findByInstanceId(String instanceId) {
        return instanceStore.findByInstanceId(instanceId);
    }

    public Uni<List<Instance>> findByCapability(String tag) {
        return instanceStore.scan(InstanceQuery.byCapability(tag));
    }

    /**
     * Returns all capability tags registered for the given instance.
     * Used by the read-side addressing filter to resolve capability and role dispatch.
     * Returns an empty list for unregistered instances (safe default — no visibility).
     */
    public Uni<List<String>> findCapabilityTagsForInstance(String instanceId) {
        return instanceStore.findByInstanceId(instanceId)
                .flatMap(opt -> opt.isPresent()
                        ? instanceStore.findCapabilities(opt.get().id)
                        : Uni.createFrom().item(List.of()));
    }

    public Uni<List<Instance>> listAll() {
        return instanceStore.scan(InstanceQuery.all());
    }
}
