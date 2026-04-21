package io.quarkiverse.qhorus.testing.contract;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.runtime.instance.Instance;
import io.quarkiverse.qhorus.runtime.store.query.InstanceQuery;

public abstract class InstanceStoreContractTest {

    protected abstract Instance put(Instance instance);

    protected abstract Optional<Instance> find(UUID id);

    protected abstract Optional<Instance> findByInstanceId(String instanceId);

    protected abstract List<Instance> scan(InstanceQuery query);

    protected abstract void reset();

    @BeforeEach
    void beforeEach() {
        reset();
    }

    @Test
    void put_assignsId_whenNull() {
        assertNotNull(put(instance("inst-" + UUID.randomUUID())).id);
    }

    @Test
    void find_returnsInstance_whenPresent() {
        Instance saved = put(instance("inst-find-" + UUID.randomUUID()));
        assertTrue(find(saved.id).isPresent());
    }

    @Test
    void find_returnsEmpty_whenAbsent() {
        assertTrue(find(UUID.randomUUID()).isEmpty());
    }

    @Test
    void findByInstanceId_returnsInstance_whenExists() {
        String iid = "iid-" + UUID.randomUUID();
        put(instance(iid));
        Optional<Instance> found = findByInstanceId(iid);
        assertTrue(found.isPresent());
        assertEquals(iid, found.get().instanceId);
    }

    @Test
    void findByInstanceId_returnsEmpty_whenNotFound() {
        assertTrue(findByInstanceId("no-such-" + UUID.randomUUID()).isEmpty());
    }

    @Test
    void scan_all_returnsAllInstances() {
        put(instance("scan-a-" + UUID.randomUUID()));
        put(instance("scan-b-" + UUID.randomUUID()));
        assertTrue(scan(InstanceQuery.all()).size() >= 2);
    }

    protected Instance instance(String instanceId) {
        Instance i = new Instance();
        i.instanceId = instanceId;
        i.description = "test";
        i.status = "online";
        i.lastSeen = Instant.now();
        return i;
    }
}
