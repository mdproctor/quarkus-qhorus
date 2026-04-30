package io.casehub.qhorus.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.runtime.data.SharedData;

public abstract class DataServiceContractTest {

    protected abstract SharedData store(String key, String content);

    protected abstract Optional<SharedData> getByKey(String key);

    protected abstract List<SharedData> listAll();

    @Test
    void store_persistsArtefact() {
        String key = "svc-store-" + UUID.randomUUID();
        SharedData d = store(key, "content");
        assertNotNull(d.id);
        assertEquals(key, d.key);
        assertTrue(d.complete);
    }

    @Test
    void getByKey_returnsData_whenExists() {
        String key = "svc-get-" + UUID.randomUUID();
        store(key, "data");
        Optional<SharedData> found = getByKey(key);
        assertTrue(found.isPresent());
        assertEquals("data", found.get().content);
    }

    @Test
    void getByKey_returnsEmpty_whenAbsent() {
        assertTrue(getByKey("no-such-" + UUID.randomUUID()).isEmpty());
    }

    @Test
    void listAll_includesStoredArtefacts() {
        store("list-a-" + UUID.randomUUID(), "c1");
        store("list-b-" + UUID.randomUUID(), "c2");
        assertTrue(listAll().size() >= 2);
    }
}
