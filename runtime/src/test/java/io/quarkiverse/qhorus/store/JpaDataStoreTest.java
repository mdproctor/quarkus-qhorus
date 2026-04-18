package io.quarkiverse.qhorus.store;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.runtime.data.ArtefactClaim;
import io.quarkiverse.qhorus.runtime.data.SharedData;
import io.quarkiverse.qhorus.runtime.store.DataStore;
import io.quarkiverse.qhorus.runtime.store.query.DataQuery;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class JpaDataStoreTest {

    @Inject
    DataStore dataStore;

    private SharedData buildData(String key, boolean complete) {
        SharedData d = new SharedData();
        d.key = key;
        d.content = "content-" + UUID.randomUUID();
        d.createdBy = "agent-a";
        d.complete = complete;
        d.sizeBytes = d.content.length();
        return d;
    }

    private ArtefactClaim buildClaim(UUID artefactId, UUID instanceId) {
        ArtefactClaim claim = new ArtefactClaim();
        claim.artefactId = artefactId;
        claim.instanceId = instanceId;
        return claim;
    }

    @Test
    @TestTransaction
    void put_persistsDataAndAssignsId() {
        SharedData d = buildData("put-test-" + UUID.randomUUID(), true);

        SharedData saved = dataStore.put(d);

        assertNotNull(saved.id);
        assertEquals(d.key, saved.key);
    }

    @Test
    @TestTransaction
    void find_returnsData_whenExists() {
        SharedData d = buildData("find-test-" + UUID.randomUUID(), true);
        dataStore.put(d);

        Optional<SharedData> found = dataStore.find(d.id);

        assertTrue(found.isPresent());
        assertEquals(d.id, found.get().id);
    }

    @Test
    @TestTransaction
    void find_returnsEmpty_whenNotFound() {
        assertTrue(dataStore.find(UUID.randomUUID()).isEmpty());
    }

    @Test
    @TestTransaction
    void findByKey_returnsData_whenExists() {
        String key = "key-test-" + UUID.randomUUID();
        SharedData d = buildData(key, true);
        dataStore.put(d);

        Optional<SharedData> found = dataStore.findByKey(key);

        assertTrue(found.isPresent());
        assertEquals(key, found.get().key);
    }

    @Test
    @TestTransaction
    void findByKey_returnsEmpty_whenNotFound() {
        assertTrue(dataStore.findByKey("no-such-key-" + UUID.randomUUID()).isEmpty());
    }

    @Test
    @TestTransaction
    void scan_completeOnly_returnsOnlyCompleteData() {
        String suffix = UUID.randomUUID().toString();
        SharedData complete = buildData("complete-" + suffix, true);
        dataStore.put(complete);

        SharedData incomplete = buildData("incomplete-" + suffix, false);
        dataStore.put(incomplete);

        List<SharedData> results = dataStore.scan(DataQuery.completeOnly());

        assertTrue(results.stream().anyMatch(d -> d.key.equals(complete.key)));
        assertTrue(results.stream().noneMatch(d -> d.key.equals(incomplete.key)));
    }

    @Test
    @TestTransaction
    void scan_byCreator_returnsMatchingOnly() {
        String suffix = UUID.randomUUID().toString();
        SharedData mine = buildData("mine-" + suffix, true);
        mine.createdBy = "agent-x-" + suffix;
        dataStore.put(mine);

        SharedData theirs = buildData("theirs-" + suffix, true);
        theirs.createdBy = "agent-y-" + suffix;
        dataStore.put(theirs);

        List<SharedData> results = dataStore.scan(DataQuery.byCreator("agent-x-" + suffix));

        assertTrue(results.stream().anyMatch(d -> d.key.equals(mine.key)));
        assertTrue(results.stream().noneMatch(d -> d.key.equals(theirs.key)));
    }

    @Test
    @TestTransaction
    void putClaim_andCountClaims_roundTrip() {
        SharedData d = buildData("claim-test-" + UUID.randomUUID(), true);
        dataStore.put(d);

        UUID instanceId1 = UUID.randomUUID();
        UUID instanceId2 = UUID.randomUUID();
        dataStore.putClaim(buildClaim(d.id, instanceId1));
        dataStore.putClaim(buildClaim(d.id, instanceId2));

        assertEquals(2, dataStore.countClaims(d.id));
    }

    @Test
    @TestTransaction
    void deleteClaim_reducesCount() {
        SharedData d = buildData("del-claim-test-" + UUID.randomUUID(), true);
        dataStore.put(d);

        UUID instanceId = UUID.randomUUID();
        dataStore.putClaim(buildClaim(d.id, instanceId));
        assertEquals(1, dataStore.countClaims(d.id));

        dataStore.deleteClaim(d.id, instanceId);
        assertEquals(0, dataStore.countClaims(d.id));
    }

    @Test
    @TestTransaction
    void delete_removesDataAndClaims() {
        SharedData d = buildData("delete-test-" + UUID.randomUUID(), true);
        dataStore.put(d);
        dataStore.putClaim(buildClaim(d.id, UUID.randomUUID()));

        dataStore.delete(d.id);

        assertTrue(dataStore.find(d.id).isEmpty());
        assertEquals(0, dataStore.countClaims(d.id));
    }
}
