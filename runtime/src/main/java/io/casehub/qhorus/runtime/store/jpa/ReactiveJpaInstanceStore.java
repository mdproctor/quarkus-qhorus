package io.casehub.qhorus.runtime.store.jpa;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import io.casehub.qhorus.runtime.instance.Capability;
import io.casehub.qhorus.runtime.instance.Instance;
import io.casehub.qhorus.runtime.store.ReactiveInstanceStore;
import io.casehub.qhorus.runtime.store.query.InstanceQuery;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;

@Alternative
@ApplicationScoped
public class ReactiveJpaInstanceStore implements ReactiveInstanceStore {

    @Inject
    InstanceReactivePanacheRepo instanceRepo;

    @Inject
    CapabilityReactivePanacheRepo capRepo;

    @Override
    @WithTransaction
    public Uni<Instance> put(Instance instance) {
        return instanceRepo.persist(instance);
    }

    @Override
    public Uni<Optional<Instance>> find(UUID id) {
        return instanceRepo.findById(id).map(Optional::ofNullable);
    }

    @Override
    public Uni<Optional<Instance>> findByInstanceId(String instanceId) {
        return instanceRepo.find("instanceId", instanceId).firstResult().map(Optional::ofNullable);
    }

    @Override
    public Uni<List<Instance>> scan(InstanceQuery q) {
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

        return instanceRepo.list(jpql.toString(), params.toArray());
    }

    @Override
    @WithTransaction
    public Uni<Void> putCapabilities(UUID instanceId, List<String> tags) {
        return capRepo.delete("instanceId", instanceId)
                .flatMap(ignored -> {
                    List<Uni<Capability>> persists = tags.stream()
                            .map(tag -> {
                                Capability c = new Capability();
                                c.instanceId = instanceId;
                                c.tag = tag;
                                return capRepo.persist(c);
                            })
                            .toList();
                    return Uni.join().all(persists).andCollectFailures();
                })
                .replaceWithVoid();
    }

    @Override
    @WithTransaction
    public Uni<Void> deleteCapabilities(UUID instanceId) {
        return capRepo.delete("instanceId", instanceId).replaceWithVoid();
    }

    @Override
    public Uni<List<String>> findCapabilities(UUID instanceId) {
        return capRepo.list("instanceId", instanceId)
                .map(caps -> caps.stream().map(c -> c.tag).toList());
    }

    @Override
    @WithTransaction
    public Uni<Void> delete(UUID id) {
        return capRepo.delete("instanceId", id)
                .flatMap(ignored -> instanceRepo.deleteById(id))
                .replaceWithVoid();
    }
}
