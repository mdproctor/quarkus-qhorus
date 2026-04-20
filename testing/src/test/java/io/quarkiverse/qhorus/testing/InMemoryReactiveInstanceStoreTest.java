package io.quarkiverse.qhorus.testing;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.runtime.instance.Instance;
import io.quarkiverse.qhorus.runtime.store.query.InstanceQuery;

class InMemoryReactiveInstanceStoreTest {

    private final InMemoryReactiveInstanceStore store = new InMemoryReactiveInstanceStore();

    @BeforeEach
    void reset() {
        store.clear();
    }

    @Test
    void put_assignsIdAndReturns() {
        Instance inst = instance("agent-" + UUID.randomUUID());
        Instance saved = store.put(inst).await().indefinitely();
        assertThat(saved.id).isNotNull();
    }

    @Test
    void findByInstanceId_returnsEmpty_whenNotFound() {
        assertThat(store.findByInstanceId("missing").await().indefinitely()).isEmpty();
    }

    @Test
    void putCapabilities_andFindCapabilities() {
        Instance inst = instance("cap-agent-" + UUID.randomUUID());
        store.put(inst).await().indefinitely();

        store.putCapabilities(inst.id, List.of("code-review", "planning")).await().indefinitely();

        List<String> caps = store.findCapabilities(inst.id).await().indefinitely();
        assertThat(caps).containsExactlyInAnyOrder("code-review", "planning");
    }

    @Test
    void deleteCapabilities_clearsAll() {
        Instance inst = instance("dc-agent-" + UUID.randomUUID());
        store.put(inst).await().indefinitely();
        store.putCapabilities(inst.id, List.of("a", "b")).await().indefinitely();

        store.deleteCapabilities(inst.id).await().indefinitely();

        assertThat(store.findCapabilities(inst.id).await().indefinitely()).isEmpty();
    }

    @Test
    void scan_byCapability_returnsMatchingInstances() {
        Instance a = instance("a-" + UUID.randomUUID());
        Instance b = instance("b-" + UUID.randomUUID());
        store.put(a).await().indefinitely();
        store.put(b).await().indefinitely();
        store.putCapabilities(a.id, List.of("search")).await().indefinitely();

        List<Instance> results = store.scan(InstanceQuery.byCapability("search")).await().indefinitely();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).instanceId).isEqualTo(a.instanceId);
    }

    private Instance instance(String instanceId) {
        Instance i = new Instance();
        i.instanceId = instanceId;
        i.status = "online";
        return i;
    }
}
