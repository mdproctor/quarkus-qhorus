package io.casehub.qhorus.runtime.store.jpa;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import io.casehub.qhorus.runtime.data.ArtefactClaim;
import io.casehub.qhorus.runtime.data.SharedData;
import io.casehub.qhorus.runtime.store.ReactiveDataStore;
import io.casehub.qhorus.runtime.store.query.DataQuery;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;

@Alternative
@ApplicationScoped
public class ReactiveJpaDataStore implements ReactiveDataStore {

    @Inject
    SharedDataReactivePanacheRepo dataRepo;

    @Inject
    ArtefactClaimReactivePanacheRepo claimRepo;

    @Override
    @WithTransaction
    public Uni<SharedData> put(SharedData data) {
        return dataRepo.persist(data);
    }

    @Override
    public Uni<Optional<SharedData>> find(UUID id) {
        return dataRepo.findById(id).map(Optional::ofNullable);
    }

    @Override
    public Uni<Optional<SharedData>> findByKey(String key) {
        return dataRepo.find("key", key).firstResult().map(Optional::ofNullable);
    }

    @Override
    public Uni<List<SharedData>> scan(DataQuery q) {
        StringBuilder jpql = new StringBuilder("FROM SharedData WHERE 1=1");
        List<Object> params = new ArrayList<>();
        int idx = 1;

        if (q.createdBy() != null) {
            jpql.append(" AND createdBy = ?").append(idx++);
            params.add(q.createdBy());
        }
        if (q.complete() != null) {
            jpql.append(" AND complete = ?").append(idx++);
            params.add(q.complete());
        }

        return dataRepo.list(jpql.toString(), params.toArray());
    }

    @Override
    @WithTransaction
    public Uni<ArtefactClaim> putClaim(ArtefactClaim claim) {
        return claimRepo.persist(claim);
    }

    @Override
    @WithTransaction
    public Uni<Void> deleteClaim(UUID artefactId, UUID instanceId) {
        return claimRepo.delete("artefactId = ?1 AND instanceId = ?2", artefactId, instanceId)
                .replaceWithVoid();
    }

    @Override
    public Uni<Integer> countClaims(UUID artefactId) {
        return claimRepo.count("artefactId", artefactId).map(Long::intValue);
    }

    @Override
    public Uni<Boolean> hasClaim(UUID artefactId, UUID instanceId) {
        return claimRepo.count("artefactId = ?1 AND instanceId = ?2", artefactId, instanceId)
                .map(c -> c > 0);
    }

    @Override
    @WithTransaction
    public Uni<Void> delete(UUID id) {
        return claimRepo.delete("artefactId", id)
                .flatMap(ignored -> dataRepo.deleteById(id))
                .replaceWithVoid();
    }
}
