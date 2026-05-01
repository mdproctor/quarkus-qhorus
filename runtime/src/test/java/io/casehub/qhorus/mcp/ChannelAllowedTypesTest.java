package io.casehub.qhorus.mcp;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase.ChannelDetail;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class ChannelAllowedTypesTest {

    @Inject
    QhorusMcpTools tools;

    @Test
    @TestTransaction
    void createChannel_withAllowedTypes_roundtripsInDetail() {
        ChannelDetail detail = tools.createChannel(
                "oversight-" + System.nanoTime(), "Human governance", "APPEND",
                null, null, null, null, null, "QUERY,COMMAND");
        assertEquals("QUERY,COMMAND", detail.allowedTypes());
    }

    @Test
    @TestTransaction
    void createChannel_nullAllowedTypes_detailShowsNull() {
        ChannelDetail detail = tools.createChannel(
                "open-" + System.nanoTime(), "Open channel", "APPEND",
                null, null, null, null, null, null);
        assertNull(detail.allowedTypes());
    }

    @Test
    @TestTransaction
    void createChannel_existingFourParamOverload_detailShowsNull() {
        ChannelDetail detail = tools.createChannel("legacy-" + System.nanoTime(), "Legacy call", null, null, null, null, null, null, null);
        assertNull(detail.allowedTypes());
    }

    @Test
    @TestTransaction
    void sendMessage_rejectsDisallowedType_clientSide() {
        String name = "observe-enforce-" + System.nanoTime();
        tools.createChannel(name, "Telemetry only", "APPEND",
                null, null, null, null, null, "EVENT");
        assertThrows(Exception.class, () -> tools.sendMessage(name, "agent-1", "QUERY", "hello?",
                null, null, null, null, null));
    }

    @Test
    @TestTransaction
    void sendMessage_permitsAllowedType_clientSide() {
        String name = "observe-ok-" + System.nanoTime();
        tools.createChannel(name, "Telemetry only", "APPEND",
                null, null, null, null, null, "EVENT");
        assertDoesNotThrow(() -> tools.sendMessage(name, "agent-1", "EVENT", "{\"tool\":\"read\"}",
                null, null, null, null, null));
    }

    @Test
    @TestTransaction
    void sendMessage_openChannel_permitsAllTypes() {
        String name = "open-all-" + System.nanoTime();
        tools.createChannel(name, "Open", "APPEND", null, null, null, null, null, null);
        assertDoesNotThrow(() -> tools.sendMessage(name, "agent-1", "COMMAND", "do something",
                null, null, null, null, null));
    }

    @Test
    @TestTransaction
    void violationError_mentionsChannelAndType() {
        String name = "oversight-block-" + System.nanoTime();
        tools.createChannel(name, "Governance", "APPEND",
                null, null, null, null, null, "QUERY,COMMAND");
        Exception ex = assertThrows(Exception.class, () -> tools.sendMessage(name, "agent-1", "EVENT", "{\"tool\":\"read\"}",
                null, null, null, null, null));
        assertTrue(ex.getMessage().contains("EVENT"));
    }
}
