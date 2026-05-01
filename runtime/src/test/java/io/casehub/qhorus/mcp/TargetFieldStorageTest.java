package io.casehub.qhorus.mcp;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.mcp.server.ToolCallException;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Issue #28 — Add target field to Message: schema, storage, and format validation.
 *
 * <p>
 * Verifies that:
 * <ul>
 * <li>send_message accepts a target in instance:*, capability:*, role:* format</li>
 * <li>null/blank target is stored as null (backward-compat: all readers see the message)</li>
 * <li>malformed targets are rejected with IllegalArgumentException</li>
 * <li>target flows through to MessageResult and MessageSummary</li>
 * </ul>
 *
 * <p>
 * No dispatch logic — just storage and validation. Refs #28, Epic #27.
 */
@QuarkusTest
class TargetFieldStorageTest {

    @Inject
    QhorusMcpTools tools;

    // -------------------------------------------------------------------------
    // Null / blank target — backward compat
    // -------------------------------------------------------------------------

    @Test
    @TestTransaction
    void nullTargetIsStoredAsNullInResult() {
        tools.createChannel("tgt-null-1", "Test", null, null, null, null, null, null, null);
        QhorusMcpTools.MessageResult result = tools.sendMessage("tgt-null-1", "alice", "status", "msg", null, null, null, null, null);
        assertNull(result.target(), "null target should be stored and returned as null");
    }

    @Test
    @TestTransaction
    void blankTargetIsTreatedAsNull() {
        tools.createChannel("tgt-blank-1", "Test", null, null, null, null, null, null, null);
        QhorusMcpTools.MessageResult result = tools.sendMessage("tgt-blank-1", "alice", "status", "msg", null, null, null, "", null);
        assertNull(result.target(), "blank target should be normalised to null");
    }

    // -------------------------------------------------------------------------
    // Valid target formats — stored and returned
    // -------------------------------------------------------------------------

    @Test
    @TestTransaction
    void instanceTargetIsStoredAndReturnedInResult() {
        tools.createChannel("tgt-inst-1", "Test", null, null, null, null, null, null, null);
        QhorusMcpTools.MessageResult result = tools.sendMessage("tgt-inst-1", "alice", "status", "msg", null, null, null, "instance:bob", null);
        assertEquals("instance:bob", result.target());
    }

    @Test
    @TestTransaction
    void capabilityTargetIsStoredAndReturnedInResult() {
        tools.createChannel("tgt-cap-1", "Test", null, null, null, null, null, null, null);
        QhorusMcpTools.MessageResult result = tools.sendMessage("tgt-cap-1", "alice", "command", "msg", null, null, null, "capability:code-review", null);
        assertEquals("capability:code-review", result.target());
    }

    @Test
    @TestTransaction
    void roleTargetIsStoredAndReturnedInResult() {
        tools.createChannel("tgt-role-1", "Test", null, null, null, null, null, null, null);
        QhorusMcpTools.MessageResult result = tools.sendMessage("tgt-role-1", "alice", "command", "msg", null, null, null, "role:reviewer", null);
        assertEquals("role:reviewer", result.target());
    }

    // -------------------------------------------------------------------------
    // Target appears in MessageSummary via check_messages
    // -------------------------------------------------------------------------

    @Test
    @TestTransaction
    void targetFlowsThroughToCheckMessagesMessageSummary() {
        tools.createChannel("tgt-chk-1", "Test", null, null, null, null, null, null, null);
        tools.sendMessage("tgt-chk-1", "alice", "status", "targeted msg", null, null, null, "instance:bob", null);

        QhorusMcpTools.CheckResult check = tools.checkMessages("tgt-chk-1", 0L, 10, null, null, null);
        assertFalse(check.messages().isEmpty());
        QhorusMcpTools.MessageSummary summary = check.messages().get(0);
        assertEquals("instance:bob", summary.target(),
                "target should be present in MessageSummary from check_messages");
    }

    @Test
    @TestTransaction
    void nullTargetFlowsThroughToCheckMessagesMessageSummary() {
        tools.createChannel("tgt-chk-2", "Test", null, null, null, null, null, null, null);
        tools.sendMessage("tgt-chk-2", "alice", "status", "broadcast msg", null, null, null, null, null);

        QhorusMcpTools.CheckResult check = tools.checkMessages("tgt-chk-2", 0L, 10, null, null, null);
        assertFalse(check.messages().isEmpty());
        assertNull(check.messages().get(0).target(),
                "null target should be null in MessageSummary");
    }

    // -------------------------------------------------------------------------
    // Malformed target — format validation
    // -------------------------------------------------------------------------

    @Test
    @TestTransaction
    void unknownPrefixThrowsIllegalArgument() {
        tools.createChannel("tgt-bad-1", "Test", null, null, null, null, null, null, null);
        ToolCallException ex = assertThrows(ToolCallException.class,
                () -> tools.sendMessage("tgt-bad-1", "alice", "status", "msg", null, null, null, "garbage:foo", null));
        assertTrue(ex.getMessage().contains("garbage:foo"),
                "Error message should identify the invalid target value");
    }

    @Test
    @TestTransaction
    void bareWordWithoutPrefixThrowsIllegalArgument() {
        tools.createChannel("tgt-bad-2", "Test", null, null, null, null, null, null, null);
        assertThrows(ToolCallException.class,
                () -> tools.sendMessage("tgt-bad-2", "alice", "status", "msg", null, null, null, "alice", null));
    }

    @Test
    @TestTransaction
    void prefixWithoutValueThrowsIllegalArgument() {
        tools.createChannel("tgt-bad-3", "Test", null, null, null, null, null, null, null);
        // "instance:" with no actual id
        assertThrows(ToolCallException.class,
                () -> tools.sendMessage("tgt-bad-3", "alice", "status", "msg", null, null, null, "instance:", null));
    }

    // -------------------------------------------------------------------------
    // Backward-compat: existing 7-arg overload still works (no target)
    // -------------------------------------------------------------------------

    @Test
    @TestTransaction
    void sevenArgOverloadStillWorksWithNullTarget() {
        tools.createChannel("tgt-compat-1", "Test", null, null, null, null, null, null, null);
        // This calls the non-@Tool 7-arg overload — must still compile and succeed
        QhorusMcpTools.MessageResult result = tools.sendMessage("tgt-compat-1", "alice", "status", "msg", null, null, null, null, null);
        assertNull(result.target(), "7-arg overload should default target to null");
    }
}
