package io.casehub.qhorus.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.mcp.server.ToolCallException;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Issue #42 — Message and instance management: delete_message, clear_channel, deregister_instance.
 *
 * <p>
 * Three surgical control tools for incident response:
 * <ul>
 * <li>{@code delete_message(message_id)} — removes a single message; event posted</li>
 * <li>{@code clear_channel(channel_name)} — removes all non-event messages; returns count</li>
 * <li>{@code deregister_instance(instance_id)} — force-removes agent and capabilities</li>
 * </ul>
 *
 * <p>
 * Refs #42, Epic #36.
 */
@QuarkusTest
class MessageInstanceManagementTest {

    @Inject
    QhorusMcpTools tools;

    // =========================================================================
    // delete_message
    // =========================================================================

    @Test
    @TestTransaction
    void deleteMessageRemovesItFromChannel() {
        tools.createChannel("mim-del-1", "Test", null, null, null, null, null, null, null);
        QhorusMcpTools.MessageResult msg = tools.sendMessage("mim-del-1", "alice", "status", "bad message", null, null, null, null, null);

        QhorusMcpTools.DeleteMessageResult result = tools.deleteMessage(msg.messageId());

        assertTrue(result.deleted());
        assertEquals(msg.messageId(), result.messageId());

        // Message no longer appears in check_messages
        QhorusMcpTools.CheckResult check = tools.checkMessages("mim-del-1", 0L, 10, null, null, null);
        assertTrue(check.messages().isEmpty(), "deleted message should not appear in check_messages");
    }

    @Test
    @TestTransaction
    void deleteMessageReturnsMetadata() {
        tools.createChannel("mim-del-2", "Test", null, null, null, null, null, null, null);
        QhorusMcpTools.MessageResult msg = tools.sendMessage("mim-del-2", "alice", "status", "the content", null, null, null, null, null);

        QhorusMcpTools.DeleteMessageResult result = tools.deleteMessage(msg.messageId());

        assertTrue(result.deleted());
        assertEquals("alice", result.sender());
        assertEquals("STATUS", result.messageType());
        assertNotNull(result.contentPreview());
    }

    @Test
    @TestTransaction
    void deleteMessageUnknownIdReturnsFalse() {
        QhorusMcpTools.DeleteMessageResult result = tools.deleteMessage(Long.MAX_VALUE);

        assertFalse(result.deleted(), "deleting unknown message id should return deleted=false");
        assertNotNull(result.message());
    }

    @Test
    @TestTransaction
    void deleteMessageDoesNotCascadeToReplies() {
        tools.createChannel("mim-del-3", "Test", null, null, null, null, null, null, null);
        QhorusMcpTools.MessageResult parent = tools.sendMessage("mim-del-3", "alice", "query", "question", null, null, null, null, null);
        tools.sendMessage("mim-del-3", "bob", "response", "answer", null, parent.messageId(), null, null, null);

        // Delete the parent — replies should still exist
        tools.deleteMessage(parent.messageId());

        QhorusMcpTools.CheckResult check = tools.checkMessages("mim-del-3", 0L, 10, null, null, null);
        assertEquals(1, check.messages().size(), "reply should survive parent deletion");
        assertEquals("answer", check.messages().get(0).content());
    }

    // =========================================================================
    // clear_channel
    // =========================================================================

    @Test
    @TestTransaction
    void clearChannelDeletesAllNonEventMessages() {
        tools.createChannel("mim-clear-1", "Test", null, null, null, null, null, null, null);
        tools.sendMessage("mim-clear-1", "alice", "command", "msg1", null, null, null, null, null);
        tools.sendMessage("mim-clear-1", "bob", "response", "msg2", null, null, null, null, null);
        tools.sendMessage("mim-clear-1", "carol", "status", "msg3", null, null, null, null, null);

        QhorusMcpTools.ClearChannelResult result = tools.clearChannel("mim-clear-1", null);

        assertEquals(3, result.messagesDeleted(), "should report 3 messages deleted");
        assertTrue(result.cleared());
    }

    @Test
    @TestTransaction
    void clearChannelMakesCheckMessagesReturnEmpty() {
        tools.createChannel("mim-clear-2", "Test", null, null, null, null, null, null, null);
        tools.sendMessage("mim-clear-2", "alice", "status", "a", null, null, null, null, null);
        tools.sendMessage("mim-clear-2", "bob", "status", "b", null, null, null, null, null);

        tools.clearChannel("mim-clear-2", null);

        QhorusMcpTools.CheckResult check = tools.checkMessages("mim-clear-2", 0L, 10, null, null, null);
        assertTrue(check.messages().isEmpty(), "channel should be empty after clear");
    }

    @Test
    @TestTransaction
    void clearEmptyChannelReturnsZeroCount() {
        tools.createChannel("mim-clear-3", "Test", null, null, null, null, null, null, null);

        QhorusMcpTools.ClearChannelResult result = tools.clearChannel("mim-clear-3", null);

        assertEquals(0, result.messagesDeleted());
        assertTrue(result.cleared());
    }

    @Test
    @TestTransaction
    void clearChannelUnknownChannelThrows() {
        assertThrows(ToolCallException.class,
                () -> tools.clearChannel("no-such-channel", null));
    }

    @Test
    @TestTransaction
    void clearChannelPreservesChannelStructure() {
        tools.createChannel("mim-clear-4", "Test", null, null, null, null, null, null, null);
        tools.sendMessage("mim-clear-4", "alice", "status", "msg", null, null, null, null, null);

        tools.clearChannel("mim-clear-4", null);

        // Channel itself still exists — can still send messages
        assertDoesNotThrow(() -> tools.sendMessage("mim-clear-4", "alice", "status", "new msg", null, null, null, null, null));
    }

    // =========================================================================
    // deregister_instance
    // =========================================================================

    @Test
    @TestTransaction
    void deregisterInstanceRemovesFromRegistry() {
        tools.register("mim-agent-1", "Test agent", List.of(), null, null);

        QhorusMcpTools.DeregisterResult result = tools.deregisterInstance("mim-agent-1");

        assertTrue(result.deregistered());
        assertEquals("mim-agent-1", result.instanceId());

        // No longer in list
        boolean stillPresent = tools.listInstances(null).stream()
                .anyMatch(i -> "mim-agent-1".equals(i.instanceId()));
        assertFalse(stillPresent, "deregistered instance should not appear in list_instances");
    }

    @Test
    @TestTransaction
    void deregisterInstanceRemovesCapabilityTags() {
        tools.register("mim-agent-2", "Agent with caps", List.of("capability:code-review", "role:reviewer"), null, null);

        tools.deregisterInstance("mim-agent-2");

        // Capabilities should be cleaned up
        List<QhorusMcpTools.InstanceInfo> remaining = tools.listInstances("capability:code-review");
        assertFalse(remaining.stream().anyMatch(i -> "mim-agent-2".equals(i.instanceId())),
                "deregistered instance capabilities should be cleaned up");
    }

    @Test
    @TestTransaction
    void deregisterUnknownInstanceReturnsFalse() {
        QhorusMcpTools.DeregisterResult result = tools.deregisterInstance("no-such-agent");

        assertFalse(result.deregistered(), "deregistering unknown instance should return deregistered=false");
        assertNotNull(result.message());
    }

    // =========================================================================
    // Integration
    // =========================================================================

    @Test
    @TestTransaction
    void integrationDeleteBadMessageFromChannel() {
        tools.createChannel("mim-int-1", "Work", null, null, null, null, null, null, null);
        tools.sendMessage("mim-int-1", "alice", "command", "good message", null, null, null, null, null);
        QhorusMcpTools.MessageResult bad = tools.sendMessage("mim-int-1", "alice", "status", "PII: name=John", null, null, null, null, null);
        tools.sendMessage("mim-int-1", "alice", "status", "another good one", null, null, null, null, null);

        tools.deleteMessage(bad.messageId());

        QhorusMcpTools.CheckResult check = tools.checkMessages("mim-int-1", 0L, 10, null, null, null);
        assertEquals(2, check.messages().size(), "only the bad message should be removed");
        assertTrue(check.messages().stream().noneMatch(m -> m.content().contains("PII")));
    }

    // =========================================================================
    // E2E
    // =========================================================================

    @Test
    @TestTransaction
    void e2eHumanDeletesPIIThenClearsChannel() {
        tools.createChannel("mim-e2e-1", "Sensitive Work", null, null, null, null, null, null, null);

        // Agents post work
        QhorusMcpTools.MessageResult piiMsg = tools.sendMessage("mim-e2e-1", "agent-1", "status", "User SSN: 123-45-6789", null, null, null, null, null);
        tools.sendMessage("mim-e2e-1", "agent-2", "status", "Legitimate work output", null, null, null, null, null);

        // Human deletes specific PII message
        tools.deleteMessage(piiMsg.messageId());

        // Only legitimate message remains
        QhorusMcpTools.CheckResult afterDelete = tools.checkMessages("mim-e2e-1", 0L, 10, null, null, null);
        assertEquals(1, afterDelete.messages().size());

        // Human decides to clear the whole channel
        tools.clearChannel("mim-e2e-1", null);
        assertTrue(tools.checkMessages("mim-e2e-1", 0L, 10, null, null, null).messages().isEmpty());
    }

    @Test
    @TestTransaction
    void e2eHumanDeregistersRogueAgent() {
        tools.register("rogue-agent", "Misbehaving agent", List.of("capability:code-review"), null, null);
        tools.createChannel("mim-e2e-2", "Test", null, null, null, null, null, null, null);
        tools.sendMessage("mim-e2e-2", "rogue-agent", "status", "rogue message", null, null, null, null, null);

        // Human deregisters the rogue agent
        QhorusMcpTools.DeregisterResult result = tools.deregisterInstance("rogue-agent");
        assertTrue(result.deregistered());

        // Agent gone from registry
        assertFalse(tools.listInstances(null).stream()
                .anyMatch(i -> "rogue-agent".equals(i.instanceId())));

        // Its past messages still exist (deregister doesn't delete messages)
        QhorusMcpTools.CheckResult check = tools.checkMessages("mim-e2e-2", 0L, 10, null, null, null);
        assertEquals(1, check.messages().size(), "past messages survive deregistration");
    }
}
