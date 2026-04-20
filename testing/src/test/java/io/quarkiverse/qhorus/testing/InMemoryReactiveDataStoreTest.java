package io.quarkiverse.qhorus.testing;

import static org.assertj.core.api.Assertions.*;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.runtime.data.ArtefactClaim;
import io.quarkiverse.qhorus.runtime.data.SharedData;

class InMemoryReactiveDataStoreTest {

    private final InMemoryReactiveDataStore store = new InMemoryReactiveDataStore();

    @BeforeEach
    void reset() {
        store.clear();
    }

    @Test
    void put_assignsIdAndReturns() {
        SharedData data = sharedData("key-" + UUID.randomUUID());
        SharedData saved = store.put(data).await().indefinitely();
        assertThat(saved.id).isNotNull();
    }

    @Test
    void findByKey_returnsEmpty_whenNotFound() {
        assertThat(store.findByKey("missing").await().indefinitely()).isEmpty();
    }

    @Test
    void findByKey_returnsData_whenExists() {
        SharedData data = sharedData("lookup-" + UUID.randomUUID());
        store.put(data).await().indefinitely();

        assertThat(store.findByKey(data.key).await().indefinitely()).isPresent();
    }

    @Test
    void putClaim_andCountClaims() {
        SharedData data = sharedData("claim-key-" + UUID.randomUUID());
        store.put(data).await().indefinitely();

        UUID instanceId = UUID.randomUUID();
        ArtefactClaim claim = new ArtefactClaim();
        claim.artefactId = data.id;
        claim.instanceId = instanceId;
        store.putClaim(claim).await().indefinitely();

        assertThat(store.countClaims(data.id).await().indefinitely()).isEqualTo(1);
    }

    @Test
    void deleteClaim_reducesCount() {
        SharedData data = sharedData("dc-key-" + UUID.randomUUID());
        store.put(data).await().indefinitely();

        UUID inst1 = UUID.randomUUID();
        ArtefactClaim c = new ArtefactClaim();
        c.artefactId = data.id;
        c.instanceId = inst1;
        store.putClaim(c).await().indefinitely();

        store.deleteClaim(data.id, inst1).await().indefinitely();

        assertThat(store.countClaims(data.id).await().indefinitely()).isZero();
    }

    private SharedData sharedData(String key) {
        SharedData d = new SharedData();
        d.key = key;
        d.createdBy = "test-agent";
        return d;
    }
}
