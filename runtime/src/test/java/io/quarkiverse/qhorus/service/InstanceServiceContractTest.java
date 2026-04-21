package io.quarkiverse.qhorus.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.runtime.instance.Instance;

public abstract class InstanceServiceContractTest {

    protected abstract Instance register(String instanceId, String desc);

    protected abstract Optional<Instance> findByInstanceId(String instanceId);

    protected abstract List<Instance> listAll();

    @Test
    void register_persistsInstance() {
        String iid = "svc-reg-" + UUID.randomUUID();
        Instance i = register(iid, "test agent");
        assertNotNull(i.id);
        assertEquals(iid, i.instanceId);
        assertEquals("online", i.status);
    }

    @Test
    void findByInstanceId_returnsInstance_whenExists() {
        String iid = "svc-find-" + UUID.randomUUID();
        register(iid, "desc");
        assertTrue(findByInstanceId(iid).isPresent());
    }

    @Test
    void findByInstanceId_returnsEmpty_whenNotFound() {
        assertTrue(findByInstanceId("no-such-" + UUID.randomUUID()).isEmpty());
    }

    @Test
    void listAll_includesRegisteredInstances() {
        register("list-a-" + UUID.randomUUID(), "agent a");
        register("list-b-" + UUID.randomUUID(), "agent b");
        assertTrue(listAll().size() >= 2);
    }
}
