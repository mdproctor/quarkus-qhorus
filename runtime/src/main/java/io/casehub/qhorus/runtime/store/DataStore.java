package io.casehub.qhorus.runtime.store;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.casehub.qhorus.runtime.data.ArtefactClaim;
import io.casehub.qhorus.runtime.data.SharedData;
import io.casehub.qhorus.runtime.store.query.DataQuery;

public interface DataStore {
    SharedData put(SharedData data);

    Optional<SharedData> find(UUID id);

    Optional<SharedData> findByKey(String key);

    List<SharedData> scan(DataQuery query);

    ArtefactClaim putClaim(ArtefactClaim claim);

    void deleteClaim(UUID artefactId, UUID instanceId);

    int countClaims(UUID artefactId);

    void delete(UUID id);
}
