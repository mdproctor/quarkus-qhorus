package io.quarkiverse.qhorus.runtime.instance;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class InstanceService {

    /** Convenience overload — no claudony session. */
    @Transactional
    public Instance register(String instanceId, String description, List<String> capabilityTags) {
        return register(instanceId, description, capabilityTags, null);
    }

    /**
     * Register or update an instance. Creates if not found; updates description,
     * status, lastSeen, and claudonySessionId if already present.
     * Replaces capability tags on every call — no stale tags accumulate.
     */
    @Transactional
    public Instance register(String instanceId, String description, List<String> capabilityTags,
            String claudonySessionId) {
        Instance instance = Instance.<Instance> find("instanceId", instanceId)
                .firstResult();

        if (instance == null) {
            instance = new Instance();
            instance.instanceId = instanceId;
        }

        instance.description = description;
        instance.status = "online";
        instance.lastSeen = Instant.now();
        instance.claudonySessionId = claudonySessionId;
        instance.persist();

        // Replace capability tags — delete existing, insert new
        Capability.delete("instanceId", instance.id);
        for (String tag : capabilityTags) {
            Capability cap = new Capability();
            cap.instanceId = instance.id;
            cap.tag = tag;
            cap.persist();
        }

        return instance;
    }

    @Transactional
    public void heartbeat(String instanceId) {
        Instance instance = Instance.<Instance> find("instanceId", instanceId).firstResult();
        if (instance != null) {
            instance.lastSeen = Instant.now();
            instance.status = "online";
        }
    }

    public Optional<Instance> findByInstanceId(String instanceId) {
        return Instance.find("instanceId", instanceId).firstResultOptional();
    }

    public List<Instance> findByCapability(String tag) {
        return Instance.find(
                "id IN (SELECT c.instanceId FROM Capability c WHERE c.tag = ?1)", tag)
                .list();
    }

    /**
     * Returns all capability tags registered for the given instance.
     * Used by the read-side addressing filter to resolve capability and role dispatch.
     * Returns an empty list for unregistered instances (safe default — no visibility).
     */
    public List<String> findCapabilityTagsForInstance(String instanceId) {
        return findByInstanceId(instanceId)
                .map(i -> Capability.<Capability> find("instanceId", i.id).list()
                        .stream()
                        .map(c -> c.tag)
                        .toList())
                .orElse(List.of());
    }

    public List<Instance> listAll() {
        return Instance.listAll();
    }

    @Transactional
    public void markStaleOlderThan(int thresholdSeconds) {
        Instant cutoff = Instant.now().minusSeconds(thresholdSeconds);
        Instance.update("status = 'stale' WHERE lastSeen < ?1 AND status = 'online'", cutoff);
    }
}
