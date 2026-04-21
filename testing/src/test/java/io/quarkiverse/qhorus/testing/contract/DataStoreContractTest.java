package io.quarkiverse.qhorus.testing.contract;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.runtime.data.ArtefactClaim;
import io.quarkiverse.qhorus.runtime.data.SharedData;
import io.quarkiverse.qhorus.runtime.store.query.DataQuery;

public abstract class DataStoreContractTest {

    protected abstract SharedData put(SharedData data);

    protected abstract Optional<SharedData> find(UUID id);

    protected abstract Optional<SharedData> findByKey(String key);

    protected abstract List<SharedData> scan(DataQuery query);

    protected abstract ArtefactClaim putClaim(ArtefactClaim claim);

    protected abstract void deleteClaim(UUID artefactId, UUID instanceId);

    protected abstract int countClaims(UUID artefactId);

    protected abstract void reset();

    @BeforeEach
    void beforeEach() {
        reset();
    }

    @Test
    void put_assignsId_whenNull() {
        assertNotNull(put(data("key-" + UUID.randomUUID())).id);
    }

    @Test
    void findByKey_returnsData_whenExists() {
        String key = "key-" + UUID.randomUUID();
        put(data(key));
        assertTrue(findByKey(key).isPresent());
    }

    @Test
    void findByKey_returnsEmpty_whenAbsent() {
        assertTrue(findByKey("no-such-" + UUID.randomUUID()).isEmpty());
    }

    @Test
    void scan_all_returnsAll() {
        put(data("k1-" + UUID.randomUUID()));
        put(data("k2-" + UUID.randomUUID()));
        assertTrue(scan(DataQuery.all()).size() >= 2);
    }

    @Test
    void claim_and_countClaims() {
        SharedData d = put(data("claim-" + UUID.randomUUID()));
        UUID instanceId = UUID.randomUUID();
        ArtefactClaim claim = new ArtefactClaim();
        claim.artefactId = d.id;
        claim.instanceId = instanceId;
        putClaim(claim);
        assertEquals(1, countClaims(d.id));
        deleteClaim(d.id, instanceId);
        assertEquals(0, countClaims(d.id));
    }

    protected SharedData data(String key) {
        SharedData d = new SharedData();
        d.key = key;
        d.createdBy = "test";
        d.content = "content";
        d.complete = true;
        d.sizeBytes = 7;
        d.updatedAt = Instant.now();
        return d;
    }
}
