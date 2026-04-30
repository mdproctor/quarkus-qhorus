package io.casehub.qhorus.runtime.store.jpa;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import io.casehub.qhorus.runtime.instance.Capability;
import io.casehub.qhorus.runtime.instance.Instance;
import io.casehub.qhorus.runtime.store.InstanceStore;
import io.casehub.qhorus.runtime.store.query.InstanceQuery;

@ApplicationScoped
public class JpaInstanceStore implements InstanceStore {

    @Override
    @Transactional
    public Instance put(Instance instance) {
        instance.persistAndFlush();
        return instance;
    }

    @Override
    public Optional<Instance> find(UUID id) {
        return Optional.ofNullable(Instance.findById(id));
    }

    @Override
    public Optional<Instance> findByInstanceId(String instanceId) {
        return Instance.find("instanceId", instanceId).firstResultOptional();
    }

    @Override
    public List<Instance> scan(InstanceQuery q) {
        StringBuilder jpql = new StringBuilder("FROM Instance WHERE 1=1");
        List<Object> params = new ArrayList<>();
        int idx = 1;

        if (q.status() != null) {
            jpql.append(" AND status = ?").append(idx++);
            params.add(q.status());
        }
        if (q.staleOlderThan() != null) {
            jpql.append(" AND lastSeen < ?").append(idx++);
            params.add(q.staleOlderThan());
        }
        if (q.capability() != null) {
            jpql.append(" AND id IN (SELECT c.instanceId FROM Capability c WHERE c.tag = ?").append(idx++).append(")");
            params.add(q.capability());
        }

        return Instance.list(jpql.toString(), params.toArray());
    }

    @Override
    @Transactional
    public void putCapabilities(UUID instanceId, List<String> tags) {
        Capability.delete("instanceId", instanceId);
        for (String tag : tags) {
            Capability cap = new Capability();
            cap.instanceId = instanceId;
            cap.tag = tag;
            cap.persist();
        }
    }

    @Override
    @Transactional
    public void deleteCapabilities(UUID instanceId) {
        Capability.delete("instanceId", instanceId);
    }

    @Override
    public List<String> findCapabilities(UUID instanceId) {
        return Capability.<Capability> list("instanceId", instanceId)
                .stream()
                .map(c -> c.tag)
                .toList();
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        Capability.delete("instanceId", id);
        Instance.deleteById(id);
    }
}
