package io.casehub.qhorus.mcp;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.mcp.server.ToolCallException;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase.CheckResult;
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase.MessageResult;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class LastWriteSemanticTest {

    @Inject
    QhorusMcpTools tools;

    @Test
    @TestTransaction
    void lastWriteFirstMessageSucceeds() {
        tools.createChannel("lw-1", "LAST_WRITE channel", "LAST_WRITE", null, null, null, null, null, null);

        MessageResult result = tools.sendMessage("lw-1", "alice", "status", "v1", null, null, null, null, null);

        assertNotNull(result.messageId());
    }

    @Test
    @TestTransaction
    void lastWriteSameSenderOverwritesInPlace() {
        tools.createChannel("lw-2", "LAST_WRITE channel", "LAST_WRITE", null, null, null, null, null, null);
        MessageResult first = tools.sendMessage("lw-2", "alice", "status", "v1", null, null, null, null, null);

        MessageResult second = tools.sendMessage("lw-2", "alice", "status", "v2", null, null, null, null, null);

        // Overwrite in place — same message ID
        assertEquals(first.messageId(), second.messageId(),
                "LAST_WRITE same-sender write should update the existing message, not insert a new one");

        // Channel has exactly one message with updated content
        CheckResult messages = tools.checkMessages("lw-2", 0L, 10, null, null, null);
        assertEquals(1, messages.messages().size());
        assertEquals("v2", messages.messages().get(0).content());
    }

    @Test
    @TestTransaction
    void lastWriteChannelHasExactlyOneMessageAfterMultipleWrites() {
        tools.createChannel("lw-3", "LAST_WRITE channel", "LAST_WRITE", null, null, null, null, null, null);
        tools.sendMessage("lw-3", "alice", "status", "v1", null, null, null, null, null);
        tools.sendMessage("lw-3", "alice", "status", "v2", null, null, null, null, null);
        tools.sendMessage("lw-3", "alice", "status", "v3", null, null, null, null, null);

        CheckResult messages = tools.checkMessages("lw-3", 0L, 10, null, null, null);

        assertEquals(1, messages.messages().size());
        assertEquals("v3", messages.messages().get(0).content());
    }

    @Test
    @TestTransaction
    void lastWriteDifferentSenderIsRejected() {
        tools.createChannel("lw-4", "LAST_WRITE channel", "LAST_WRITE", null, null, null, null, null, null);
        tools.sendMessage("lw-4", "alice", "status", "alice owns this", null, null, null, null, null);

        assertThrows(ToolCallException.class, () -> tools.sendMessage("lw-4", "bob", "status", "bob tries", null, null, null, null, null),
                "LAST_WRITE channel should reject a second sender");
    }

    @Test
    @TestTransaction
    void lastWriteRejectionMessageIdentifiesCurrentWriter() {
        tools.createChannel("lw-5", "LAST_WRITE channel", "LAST_WRITE", null, null, null, null, null, null);
        tools.sendMessage("lw-5", "alice", "status", "alice owns this", null, null, null, null, null);

        ToolCallException ex = assertThrows(ToolCallException.class,
                () -> tools.sendMessage("lw-5", "bob", "status", "bob tries", null, null, null, null, null));

        assertTrue(ex.getMessage().contains("alice"),
                "rejection message should identify the current writer");
    }

    @Test
    @TestTransaction
    void lastWriteOverwriteUpdatesMessageType() {
        tools.createChannel("lw-6", "LAST_WRITE channel", "LAST_WRITE", null, null, null, null, null, null);
        tools.sendMessage("lw-6", "alice", "status", "initial state", null, null, null, null, null);

        // Overwrite with a different type — should be reflected in the stored message
        MessageResult overwrite = tools.sendMessage("lw-6", "alice", "command", "updated", null, null, null, null, null);

        assertEquals("COMMAND", overwrite.messageType(),
                "LAST_WRITE overwrite should replace messageType, not retain the original");
        CheckResult messages = tools.checkMessages("lw-6", 0L, 10, null, null, null);
        assertEquals(1, messages.messages().size());
        assertEquals("COMMAND", messages.messages().get(0).messageType());
    }

    @Test
    @TestTransaction
    void lastWriteOverwriteUpdatesCorrelationId() {
        tools.createChannel("lw-7", "LAST_WRITE channel", "LAST_WRITE", null, null, null, null, null, null);
        tools.sendMessage("lw-7", "alice", "status", "v1", "corr-original", null, null, null, null);

        MessageResult overwrite = tools.sendMessage("lw-7", "alice", "status", "v2", "corr-updated", null, null, null, null);

        assertEquals("corr-updated", overwrite.correlationId(),
                "LAST_WRITE overwrite should replace correlationId, not retain the original");
        CheckResult messages = tools.checkMessages("lw-7", 0L, 10, null, null, null);
        assertEquals("corr-updated", messages.messages().get(0).correlationId());
    }

    @Test
    @TestTransaction
    void appendChannelAllowsMultipleSendersUnaffected() {
        tools.createChannel("append-lw", "APPEND channel", "APPEND", null, null, null, null, null, null);
        MessageResult m1 = tools.sendMessage("append-lw", "alice", "status", "first", null, null, null, null, null);
        MessageResult m2 = tools.sendMessage("append-lw", "bob", "status", "second", null, null, null, null, null);

        // APPEND creates distinct messages, different IDs
        assertNotEquals(m1.messageId(), m2.messageId(),
                "APPEND channel should not apply LAST_WRITE logic");

        CheckResult messages = tools.checkMessages("append-lw", 0L, 10, null, null, null);
        assertEquals(2, messages.messages().size());
    }
}
