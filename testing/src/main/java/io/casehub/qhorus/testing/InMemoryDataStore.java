package io.casehub.qhorus.testing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import io.casehub.qhorus.runtime.data.ArtefactClaim;
import io.casehub.qhorus.runtime.data.SharedData;
import io.casehub.qhorus.runtime.store.DataStore;
import io.casehub.qhorus.runtime.store.query.DataQuery;

@Alternative
@Priority(1)
@ApplicationScoped
public class InMemoryDataStore implements DataStore {

    private final Map<UUID, SharedData> store = new LinkedHashMap<>();
    private final List<ArtefactClaim> claims = new ArrayList<>();

    @Override
    public SharedData put(SharedData data) {
        if (data.id == null) {
            data.id = UUID.randomUUID();
        }
        store.put(data.id, data);
        return data;
    }

    @Override
    public Optional<SharedData> find(UUID id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public Optional<SharedData> findByKey(String key) {
        return store.values().stream()
                .filter(d -> key.equals(d.key))
                .findFirst();
    }

    @Override
    public List<SharedData> scan(DataQuery query) {
        return store.values().stream()
                .filter(query::matches)
                .toList();
    }

    @Override
    public ArtefactClaim putClaim(ArtefactClaim claim) {
        if (claim.id == null) {
            claim.id = UUID.randomUUID();
        }
        claims.add(claim);
        return claim;
    }

    @Override
    public void deleteClaim(UUID artefactId, UUID instanceId) {
        claims.removeIf(c -> artefactId.equals(c.artefactId) && instanceId.equals(c.instanceId));
    }

    @Override
    public int countClaims(UUID artefactId) {
        return (int) claims.stream()
                .filter(c -> artefactId.equals(c.artefactId))
                .count();
    }

    public boolean hasClaim(UUID artefactId, UUID instanceId) {
        return claims.stream()
                .anyMatch(c -> artefactId.equals(c.artefactId) && instanceId.equals(c.instanceId));
    }

    @Override
    public void delete(UUID id) {
        claims.removeIf(c -> id.equals(c.artefactId));
        store.remove(id);
    }

    /** Call in @BeforeEach for test isolation. */
    public void clear() {
        store.clear();
        claims.clear();
    }
}
