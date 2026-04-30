package io.casehub.qhorus.mcp;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase.CheckResult;
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase.MessageResult;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class CollectSemanticTest {

    @Inject
    QhorusMcpTools tools;

    @Test
    @TestTransaction
    void collectDeliversAllContributionsAtomically() {
        tools.createChannel("col-1", "COLLECT channel", "COLLECT", null);
        tools.sendMessage("col-1", "alice", "status", "alice's finding", null, null);
        tools.sendMessage("col-1", "bob", "status", "bob's finding", null, null);
        tools.sendMessage("col-1", "carol", "status", "carol's finding", null, null);

        CheckResult result = tools.checkMessages("col-1", 0L, 10, null);

        assertEquals(3, result.messages().size(),
                "COLLECT should deliver all accumulated messages");
    }

    @Test
    @TestTransaction
    void collectClearsChannelAfterDelivery() {
        tools.createChannel("col-2", "COLLECT channel", "COLLECT", null);
        tools.sendMessage("col-2", "alice", "status", "contribution", null, null);
        tools.checkMessages("col-2", 0L, 10, null); // Deliver and clear

        CheckResult second = tools.checkMessages("col-2", 0L, 10, null);
        assertTrue(second.messages().isEmpty(),
                "COLLECT channel should be empty after delivery");
    }

    @Test
    @TestTransaction
    void collectIgnoresAfterIdCursorDeliversAll() {
        tools.createChannel("col-3", "COLLECT channel", "COLLECT", null);
        MessageResult m1 = tools.sendMessage("col-3", "alice", "status", "first", null, null);
        tools.sendMessage("col-3", "bob", "status", "second", null, null);

        // Even with afterId past the first message, COLLECT delivers everything pending
        CheckResult result = tools.checkMessages("col-3", m1.messageId(), 10, null);

        assertEquals(2, result.messages().size(),
                "COLLECT should deliver all messages regardless of afterId cursor");
    }

    @Test
    @TestTransaction
    void collectNewCycleAfterClear() {
        tools.createChannel("col-4", "COLLECT channel", "COLLECT", null);
        tools.sendMessage("col-4", "alice", "status", "cycle-1 data", null, null);
        tools.checkMessages("col-4", 0L, 10, null); // Clear cycle 1

        // New writes start cycle 2
        tools.sendMessage("col-4", "bob", "status", "cycle-2 data", null, null);
        CheckResult result = tools.checkMessages("col-4", 0L, 10, null);

        assertEquals(1, result.messages().size());
        assertEquals("cycle-2 data", result.messages().get(0).content());
    }

    @Test
    @TestTransaction
    void collectExcludesEventMessages() {
        tools.createChannel("col-5", "COLLECT channel", "COLLECT", null);
        tools.sendMessage("col-5", "alice", "status", "visible", null, null);
        tools.sendMessage("col-5", "system", "event", "telemetry", null, null);
        tools.sendMessage("col-5", "bob", "status", "also visible", null, null);

        CheckResult result = tools.checkMessages("col-5", 0L, 10, null);

        assertEquals(2, result.messages().size(),
                "COLLECT should exclude EVENT messages from delivery");
        assertTrue(result.messages().stream().noneMatch(m -> "event".equalsIgnoreCase(m.messageType())));
    }

    @Test
    @TestTransaction
    void appendChannelUnaffectedByCollectLogic() {
        tools.createChannel("append-col", "APPEND channel", "APPEND", null);
        tools.sendMessage("append-col", "alice", "status", "persistent", null, null);
        tools.checkMessages("append-col", 0L, 10, null);

        CheckResult second = tools.checkMessages("append-col", 0L, 10, null);
        assertEquals(1, second.messages().size(),
                "APPEND channel messages should not be cleared on read");
    }
}
