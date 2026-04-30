package io.casehub.qhorus.runtime.store;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.casehub.qhorus.runtime.data.ArtefactClaim;
import io.casehub.qhorus.runtime.data.SharedData;
import io.casehub.qhorus.runtime.store.query.DataQuery;
import io.smallrye.mutiny.Uni;

public interface ReactiveDataStore {
    Uni<SharedData> put(SharedData data);

    Uni<Optional<SharedData>> find(UUID id);

    Uni<Optional<SharedData>> findByKey(String key);

    Uni<List<SharedData>> scan(DataQuery query);

    Uni<ArtefactClaim> putClaim(ArtefactClaim claim);

    Uni<Void> deleteClaim(UUID artefactId, UUID instanceId);

    Uni<Integer> countClaims(UUID artefactId);

    Uni<Boolean> hasClaim(UUID artefactId, UUID instanceId);

    Uni<Void> delete(UUID id);
}
