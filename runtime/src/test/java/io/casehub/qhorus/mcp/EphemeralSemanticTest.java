package io.casehub.qhorus.mcp;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase.CheckResult;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class EphemeralSemanticTest {

    @Inject
    QhorusMcpTools tools;

    @Test
    @TestTransaction
    void ephemeralMessageDeliveredOnFirstRead() {
        tools.createChannel("eph-1", "EPHEMERAL channel", "EPHEMERAL", null, null, null, null, null, null);
        tools.sendMessage("eph-1", "alice", "status", "routing hint", null, null, null, null, null);

        CheckResult result = tools.checkMessages("eph-1", 0L, 10, null, null, null);

        assertEquals(1, result.messages().size());
        assertEquals("routing hint", result.messages().get(0).content());
    }

    @Test
    @TestTransaction
    void ephemeralSecondReadReturnsEmpty() {
        tools.createChannel("eph-2", "EPHEMERAL channel", "EPHEMERAL", null, null, null, null, null, null);
        tools.sendMessage("eph-2", "alice", "status", "transient context", null, null, null, null, null);

        tools.checkMessages("eph-2", 0L, 10, null, null, null); // First read — consumes

        CheckResult second = tools.checkMessages("eph-2", 0L, 10, null, null, null);
        assertTrue(second.messages().isEmpty(),
                "EPHEMERAL messages should be gone after first read");
    }

    @Test
    @TestTransaction
    void ephemeralNewWriteAfterReadIsDeliveredOnNextRead() {
        tools.createChannel("eph-3", "EPHEMERAL channel", "EPHEMERAL", null, null, null, null, null, null);
        tools.sendMessage("eph-3", "alice", "status", "first hint", null, null, null, null, null);
        tools.checkMessages("eph-3", 0L, 10, null, null, null); // Consume first

        tools.sendMessage("eph-3", "alice", "status", "second hint", null, null, null, null, null);
        CheckResult result = tools.checkMessages("eph-3", 0L, 10, null, null, null);

        assertEquals(1, result.messages().size());
        assertEquals("second hint", result.messages().get(0).content());
    }

    @Test
    @TestTransaction
    void ephemeralMultipleMessagesAllConsumedOnFirstRead() {
        tools.createChannel("eph-4", "EPHEMERAL channel", "EPHEMERAL", null, null, null, null, null, null);
        tools.sendMessage("eph-4", "alice", "status", "hint-1", null, null, null, null, null);
        tools.sendMessage("eph-4", "bob", "status", "hint-2", null, null, null, null, null);
        tools.sendMessage("eph-4", "carol", "status", "hint-3", null, null, null, null, null);

        CheckResult first = tools.checkMessages("eph-4", 0L, 10, null, null, null);
        assertEquals(3, first.messages().size());

        CheckResult second = tools.checkMessages("eph-4", 0L, 10, null, null, null);
        assertTrue(second.messages().isEmpty());
    }

    @Test
    @TestTransaction
    void ephemeralLimitOnlyDeletesDeliveredMessages() {
        tools.createChannel("eph-5", "EPHEMERAL channel", "EPHEMERAL", null, null, null, null, null, null);
        tools.sendMessage("eph-5", "alice", "status", "msg-1", null, null, null, null, null);
        tools.sendMessage("eph-5", "alice", "status", "msg-2", null, null, null, null, null);
        tools.sendMessage("eph-5", "alice", "status", "msg-3", null, null, null, null, null);

        // Read with limit=2 — only the first two should be delivered and deleted
        CheckResult first = tools.checkMessages("eph-5", 0L, 2, null, null, null);
        assertEquals(2, first.messages().size(), "limit=2 should deliver 2 messages");

        // Third message was NOT delivered, so it should still be available
        CheckResult second = tools.checkMessages("eph-5", 0L, 10, null, null, null);
        assertEquals(1, second.messages().size(),
                "The undelivered third EPHEMERAL message should survive a partial first read");
        assertEquals("msg-3", second.messages().get(0).content());
    }

    @Test
    @TestTransaction
    void appendChannelUnaffectedByEphemeralLogic() {
        tools.createChannel("append-eph", "APPEND channel", "APPEND", null, null, null, null, null, null);
        tools.sendMessage("append-eph", "alice", "status", "persistent", null, null, null, null, null);
        tools.checkMessages("append-eph", 0L, 10, null, null, null);

        // APPEND messages survive reads
        CheckResult second = tools.checkMessages("append-eph", 0L, 10, null, null, null);
        assertEquals(1, second.messages().size(),
                "APPEND channel messages should not be deleted on read");
    }
}
