package io.casehub.qhorus.runtime.store.jpa;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import io.casehub.qhorus.runtime.data.ArtefactClaim;
import io.casehub.qhorus.runtime.data.SharedData;
import io.casehub.qhorus.runtime.store.DataStore;
import io.casehub.qhorus.runtime.store.query.DataQuery;

@ApplicationScoped
public class JpaDataStore implements DataStore {

    @Override
    @Transactional
    public SharedData put(SharedData data) {
        data.persistAndFlush();
        return data;
    }

    @Override
    public Optional<SharedData> find(UUID id) {
        return Optional.ofNullable(SharedData.findById(id));
    }

    @Override
    public Optional<SharedData> findByKey(String key) {
        return SharedData.find("key", key).firstResultOptional();
    }

    @Override
    public List<SharedData> scan(DataQuery q) {
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

        return SharedData.list(jpql.toString(), params.toArray());
    }

    @Override
    @Transactional
    public ArtefactClaim putClaim(ArtefactClaim claim) {
        claim.persistAndFlush();
        return claim;
    }

    @Override
    @Transactional
    public void deleteClaim(UUID artefactId, UUID instanceId) {
        ArtefactClaim.delete("artefactId = ?1 AND instanceId = ?2", artefactId, instanceId);
    }

    @Override
    public int countClaims(UUID artefactId) {
        return (int) ArtefactClaim.count("artefactId", artefactId);
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        ArtefactClaim.delete("artefactId", id);
        SharedData.deleteById(id);
    }
}
