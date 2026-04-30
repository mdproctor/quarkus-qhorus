package io.casehub.qhorus.runtime.instance;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import io.casehub.qhorus.runtime.store.InstanceStore;
import io.casehub.qhorus.runtime.store.query.InstanceQuery;

@ApplicationScoped
public class InstanceService {

    @Inject
    InstanceStore instanceStore;

    /** Convenience overload — no claudony session, not read-only. */
    @Transactional
    public Instance register(String instanceId, String description, List<String> capabilityTags) {
        return register(instanceId, description, capabilityTags, null, false);
    }

    /** Convenience overload — not read-only. */
    @Transactional
    public Instance register(String instanceId, String description, List<String> capabilityTags,
            String claudonySessionId) {
        return register(instanceId, description, capabilityTags, claudonySessionId, false);
    }

    /**
     * Register or update an instance. Creates if not found; updates description,
     * status, lastSeen, claudonySessionId, and readOnly if already present.
     * Replaces capability tags on every call — no stale tags accumulate.
     */
    @Transactional
    public Instance register(String instanceId, String description, List<String> capabilityTags,
            String claudonySessionId, boolean readOnly) {
        Instance instance = instanceStore.findByInstanceId(instanceId).orElse(null);

        if (instance == null) {
            instance = new Instance();
            instance.instanceId = instanceId;
        }

        instance.description = description;
        instance.status = "online";
        instance.lastSeen = Instant.now();
        instance.claudonySessionId = claudonySessionId;
        instance.readOnly = readOnly;
        instanceStore.put(instance);

        // Replace capability tags — delete existing, insert new
        instanceStore.putCapabilities(instance.id, capabilityTags);

        return instance;
    }

    @Transactional
    public void heartbeat(String instanceId) {
        instanceStore.findByInstanceId(instanceId).ifPresent(instance -> {
            instance.lastSeen = Instant.now();
            instance.status = "online";
        });
    }

    public Optional<Instance> findByInstanceId(String instanceId) {
        return instanceStore.findByInstanceId(instanceId);
    }

    public List<Instance> findByCapability(String tag) {
        return instanceStore.scan(InstanceQuery.byCapability(tag));
    }

    /**
     * Returns all capability tags registered for the given instance.
     * Used by the read-side addressing filter to resolve capability and role dispatch.
     * Returns an empty list for unregistered instances (safe default — no visibility).
     */
    public List<String> findCapabilityTagsForInstance(String instanceId) {
        return instanceStore.findByInstanceId(instanceId)
                .map(i -> instanceStore.findCapabilities(i.id))
                .orElse(List.of());
    }

    public List<Instance> listAll() {
        return instanceStore.scan(InstanceQuery.all());
    }

    @Transactional
    public void markStaleOlderThan(int thresholdSeconds) {
        Instant cutoff = Instant.now().minusSeconds(thresholdSeconds);
        Instance.update("status = 'stale' WHERE lastSeen < ?1 AND status = 'online'", cutoff);
    }
}
