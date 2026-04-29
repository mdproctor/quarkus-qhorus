package io.quarkiverse.qhorus.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.api.channel.ChannelSemantic;
import io.quarkiverse.qhorus.runtime.channel.ChannelService;
import io.quarkiverse.qhorus.runtime.instance.Instance;
import io.quarkiverse.qhorus.runtime.instance.InstanceService;
import io.quarkiverse.qhorus.runtime.mcp.QhorusMcpTools;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class InstanceToolTest {

    @Inject
    QhorusMcpTools tools;

    @Inject
    ChannelService channelService;

    @Inject
    InstanceService instanceService;

    @Test
    @TestTransaction
    void registerCreatesInstanceAndReturnsContextSnapshot() {
        channelService.create("welcome-ch", "General channel", ChannelSemantic.APPEND, null);

        QhorusMcpTools.RegisterResponse resp = tools.register(
                "test-agent", "A test agent", List.of("code-review", "java"), null);

        assertEquals("test-agent", resp.instanceId());
        assertTrue(resp.activeChannels().stream().anyMatch(c -> "welcome-ch".equals(c.name())),
                "register response should include active channels");
        assertTrue(resp.onlineInstances().stream().anyMatch(i -> "test-agent".equals(i.instanceId())),
                "register response should include the registered instance in online list");
    }

    @Test
    @TestTransaction
    void registerUpsertsSameInstanceIdWithoutDuplicate() {
        tools.register("upsert-agent", "First", List.of("python"), null);
        QhorusMcpTools.RegisterResponse second = tools.register(
                "upsert-agent", "Updated description", List.of("ml"), null);

        assertEquals("upsert-agent", second.instanceId());
        long count = second.onlineInstances().stream()
                .filter(i -> "upsert-agent".equals(i.instanceId())).count();
        assertEquals(1, count, "re-registering same instance_id should not create duplicates");
    }

    @Test
    @TestTransaction
    void registerReplacesCapabilityTagsOnUpsert() {
        tools.register("cap-upsert-agent", "Agent", List.of("python"), null);

        // Re-register with different tags
        tools.register("cap-upsert-agent", "Agent", List.of("ml"), null);

        // Old tag must be gone, new tag must be present
        assertTrue(instanceService.findByCapability("python").isEmpty(),
                "'python' tag should be removed after re-register");
        assertEquals(1, instanceService.findByCapability("ml").size(),
                "'ml' tag should be present after re-register");
    }

    @Test
    @TestTransaction
    void registerStoresClaudonySessionId() {
        tools.register("claudony-agent", "Claudony-managed", List.of(), "claudony-session-xyz");

        // Verify the claudonySessionId was actually persisted to the entity
        Instance inst = instanceService.findByInstanceId("claudony-agent").orElseThrow();
        assertEquals("claudony-session-xyz", inst.claudonySessionId,
                "claudonySessionId should be persisted when provided");
    }

    @Test
    @TestTransaction
    void registerWithNoClaudonySessionIdLeavesFieldNull() {
        tools.register("plain-agent", "No claudony", List.of(), null);

        Instance inst = instanceService.findByInstanceId("plain-agent").orElseThrow();
        assertNull(inst.claudonySessionId,
                "claudonySessionId should be null when not provided");
    }

    @Test
    @TestTransaction
    void listInstancesReturnsAllOnline() {
        tools.register("l-agent-1", "Agent 1", List.of("skill-a"), null);
        tools.register("l-agent-2", "Agent 2", List.of("skill-b"), null);

        List<QhorusMcpTools.InstanceInfo> all = tools.listInstances(null);

        assertTrue(all.stream().anyMatch(i -> "l-agent-1".equals(i.instanceId())));
        assertTrue(all.stream().anyMatch(i -> "l-agent-2".equals(i.instanceId())));
    }

    @Test
    @TestTransaction
    void listInstancesFiltersByCapabilityTag() {
        tools.register("py-agent", "Python expert", List.of("python"), null);
        tools.register("jv-agent", "Java expert", List.of("java"), null);

        List<QhorusMcpTools.InstanceInfo> pythonOnly = tools.listInstances("python");

        assertEquals(1, pythonOnly.size());
        assertEquals("py-agent", pythonOnly.get(0).instanceId());
    }

    @Test
    @TestTransaction
    void listInstancesIncludesCapabilitiesOnEachEntry() {
        tools.register("multi-agent", "Multi-skill", List.of("code-review", "testing"), null);

        List<QhorusMcpTools.InstanceInfo> all = tools.listInstances(null);
        QhorusMcpTools.InstanceInfo agent = all.stream()
                .filter(i -> "multi-agent".equals(i.instanceId()))
                .findFirst().orElseThrow();

        assertTrue(agent.capabilities().contains("code-review"));
        assertTrue(agent.capabilities().contains("testing"));
    }

    @Test
    @TestTransaction
    void listInstancesWithNoMatchingCapabilityReturnsEmpty() {
        tools.register("solo-agent", "Solo", List.of("python"), null);

        List<QhorusMcpTools.InstanceInfo> result = tools.listInstances("no-such-cap");

        assertTrue(result.isEmpty());
    }
}
