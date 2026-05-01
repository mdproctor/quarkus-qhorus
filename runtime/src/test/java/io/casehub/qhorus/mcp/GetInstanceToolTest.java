package io.casehub.qhorus.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.runtime.instance.InstanceService;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase.InstanceInfo;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class GetInstanceToolTest {

    @Inject
    QhorusMcpTools tools;
    @Inject
    InstanceService instanceService;

    @Test
    void getInstance_knownId_returnsCorrectInfo() {
        String instanceId = "inst-get-" + System.nanoTime();
        QuarkusTransaction.requiringNew().run(() -> instanceService.register(instanceId, "Test agent", List.of("search", "analysis"), null));

        InstanceInfo info = QuarkusTransaction.requiringNew().call(() -> tools.getInstance(instanceId));

        assertEquals(instanceId, info.instanceId());
        assertEquals("Test agent", info.description());
        assertTrue(info.capabilities().contains("search"));
        assertTrue(info.capabilities().contains("analysis"));

        // Clean up — deregister so the instance does not pollute listInstances() counts in other tests
        QuarkusTransaction.requiringNew().run(() -> tools.deregisterInstance(instanceId));
    }

    @Test
    void getInstance_unknownId_throwsWithNotFoundMessage() {
        Exception ex = assertThrows(Exception.class,
                () -> QuarkusTransaction.requiringNew().run(() -> tools.getInstance("no-such-instance-" + System.nanoTime())));
        assertTrue(ex.getMessage().toLowerCase().contains("not found"),
                "Error should say 'not found': " + ex.getMessage());
    }
}
