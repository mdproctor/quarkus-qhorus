package io.casehub.qhorus.runtime.message;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.mcp.server.ToolCallException;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase.MessageResult;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class MessageTypeValidationTest {

    @Inject
    QhorusMcpTools tools;

    // -----------------------------------------------------------------------
    // Content / target validation via send_message
    // -----------------------------------------------------------------------

    @Test
    @TestTransaction
    void declineWithEmptyContentIsRejected() {
        tools.createChannel("validate-decline-empty", "Test", null, null, null, null, null, null, null);

        assertThrows(ToolCallException.class,
                () -> tools.sendMessage("validate-decline-empty", "alice", "decline", "", null, null, null, null, null));
    }

    @Test
    @TestTransaction
    void failureWithBlankContentIsRejected() {
        tools.createChannel("validate-failure-blank", "Test", null, null, null, null, null, null, null);

        assertThrows(ToolCallException.class,
                () -> tools.sendMessage("validate-failure-blank", "alice", "failure", "   ", null, null, null, null, null));
    }

    @Test
    @TestTransaction
    void handoffWithoutTargetIsRejected() {
        tools.createChannel("validate-handoff-notarget", "Test", null, null, null, null, null, null, null);

        assertThrows(ToolCallException.class,
                () -> tools.sendMessage("validate-handoff-notarget", "alice", "handoff", "Please handle this", null, null, null, null, null));
    }

    // -----------------------------------------------------------------------
    // Auto-generated correlationId for QUERY and COMMAND
    // -----------------------------------------------------------------------

    @Test
    @TestTransaction
    void queryAutoGeneratesCorrelationId() {
        tools.createChannel("validate-query-corr", "Test", null, null, null, null, null, null, null);

        MessageResult result = tools.sendMessage("validate-query-corr", "alice", "query", "What is the status?", null, null, null, null, null);

        assertNotNull(result.correlationId(), "QUERY with no correlation_id supplied should auto-generate one");
        assertFalse(result.correlationId().isBlank(), "auto-generated correlationId must not be blank");
    }

    @Test
    @TestTransaction
    void commandAutoGeneratesCorrelationId() {
        tools.createChannel("validate-command-corr", "Test", null, null, null, null, null, null, null);

        MessageResult result = tools.sendMessage("validate-command-corr", "alice", "command", "Execute the task", null, null, null, null, null);

        assertNotNull(result.correlationId(), "COMMAND with no correlation_id supplied should auto-generate one");
        assertFalse(result.correlationId().isBlank(), "auto-generated correlationId must not be blank");
    }

    // -----------------------------------------------------------------------
    // Pure enum behaviour — no DB needed
    // -----------------------------------------------------------------------

    @Test
    void isAgentVisibleReturnsFalseForEventOnly() {
        for (MessageType t : MessageType.values()) {
            if (t == MessageType.EVENT) {
                assertFalse(t.isAgentVisible(), "EVENT should NOT be agent-visible");
            } else {
                assertTrue(t.isAgentVisible(), t.name() + " should be agent-visible");
            }
        }
    }

    @Test
    void requiresCorrelationIdForQueryAndCommandOnly() {
        assertTrue(MessageType.QUERY.requiresCorrelationId(), "QUERY must require correlationId");
        assertTrue(MessageType.COMMAND.requiresCorrelationId(), "COMMAND must require correlationId");

        for (MessageType t : MessageType.values()) {
            if (t != MessageType.QUERY && t != MessageType.COMMAND) {
                assertFalse(t.requiresCorrelationId(), t.name() + " must NOT require correlationId");
            }
        }
    }
}
