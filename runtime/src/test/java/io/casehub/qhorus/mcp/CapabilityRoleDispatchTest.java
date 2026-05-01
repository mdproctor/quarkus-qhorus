package io.casehub.qhorus.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Issue #30 — Implement capability and role dispatch on read side.
 *
 * <p>
 * The full target string is the capability tag stored in the Capability table:
 * <ul>
 * <li>{@code capability:code-review} → matches Capability.tag = {@code "capability:code-review"}</li>
 * <li>{@code role:reviewer} → matches Capability.tag = {@code "role:reviewer"}</li>
 * </ul>
 *
 * <p>
 * Agents register with qualified tags to be addressable:
 * {@code register("alice", "desc", ["capability:code-review", "role:reviewer"])}.
 *
 * <p>
 * Refs #30, Epic #27.
 */
@QuarkusTest
class CapabilityRoleDispatchTest {

    @Inject
    QhorusMcpTools tools;

    // =========================================================================
    // Capability dispatch — capability:X
    // =========================================================================

    @Test
    @TestTransaction
    void capabilityTargetedMessageVisibleToAgentWithMatchingTag() {
        tools.createChannel("crd-cap-1", "Test", "APPEND", null, null, null, null, null, null);
        tools.register("reviewer-alice", "Code reviewer", List.of("capability:code-review"), null, null);
        tools.sendMessage("crd-cap-1", "sender", "command", "review this", null, null, null, "capability:code-review", null);

        QhorusMcpTools.CheckResult result = tools.checkMessages("crd-cap-1", 0L, 10, null, "reviewer-alice", null);
        assertEquals(1, result.messages().size(),
                "agent with capability:code-review tag should see the message");
    }

    @Test
    @TestTransaction
    void capabilityTargetedMessageHiddenFromAgentWithoutMatchingTag() {
        tools.createChannel("crd-cap-2", "Test", "APPEND", null, null, null, null, null, null);
        tools.register("python-bob", "Python dev", List.of("capability:python"), null, null);
        tools.sendMessage("crd-cap-2", "sender", "command", "review this", null, null, null, "capability:code-review", null);

        QhorusMcpTools.CheckResult result = tools.checkMessages("crd-cap-2", 0L, 10, null, "python-bob", null);
        assertTrue(result.messages().isEmpty(),
                "agent without capability:code-review tag should not see the message");
    }

    @Test
    @TestTransaction
    void capabilityTargetedMessageHiddenFromUnregisteredReader() {
        tools.createChannel("crd-cap-3", "Test", "APPEND", null, null, null, null, null, null);
        // No register call — reader is unknown to the system
        tools.sendMessage("crd-cap-3", "sender", "command", "review this", null, null, null, "capability:code-review", null);

        QhorusMcpTools.CheckResult result = tools.checkMessages("crd-cap-3", 0L, 10, null, "ghost-reader", null);
        assertTrue(result.messages().isEmpty(),
                "unregistered reader has no capability tags — should see nothing targeted");
    }

    @Test
    @TestTransaction
    void broadcastMessageVisibleToCapabilityReader() {
        tools.createChannel("crd-cap-4", "Test", "APPEND", null, null, null, null, null, null);
        tools.register("cap-reader", "Agent", List.of("capability:code-review"), null, null);
        tools.sendMessage("crd-cap-4", "sender", "status", "broadcast", null, null, null, null, null);

        QhorusMcpTools.CheckResult result = tools.checkMessages("crd-cap-4", 0L, 10, null, "cap-reader", null);
        assertEquals(1, result.messages().size(), "broadcast message visible to all readers");
    }

    // =========================================================================
    // Role dispatch — role:X (broadcast to all with that role)
    // =========================================================================

    @Test
    @TestTransaction
    void roleTargetedMessageVisibleToAllAgentsWithMatchingRoleTag() {
        tools.createChannel("crd-role-1", "Test", "APPEND", null, null, null, null, null, null);
        tools.register("reviewer-1", "First reviewer", List.of("role:reviewer"), null, null);
        tools.register("reviewer-2", "Second reviewer", List.of("role:reviewer"), null, null);
        tools.sendMessage("crd-role-1", "sender", "command", "needs review", null, null, null, "role:reviewer", null);

        // Both reviewers independently see the message (broadcast)
        QhorusMcpTools.CheckResult r1 = tools.checkMessages("crd-role-1", 0L, 10, null, "reviewer-1", null);
        QhorusMcpTools.CheckResult r2 = tools.checkMessages("crd-role-1", 0L, 10, null, "reviewer-2", null);
        assertEquals(1, r1.messages().size(), "reviewer-1 should see the role-targeted message");
        assertEquals(1, r2.messages().size(), "reviewer-2 should also see the same message");
    }

    @Test
    @TestTransaction
    void roleTargetedMessageHiddenFromAgentWithoutRoleTag() {
        tools.createChannel("crd-role-2", "Test", "APPEND", null, null, null, null, null, null);
        tools.register("non-reviewer", "Not a reviewer", List.of("capability:python"), null, null);
        tools.sendMessage("crd-role-2", "sender", "command", "needs review", null, null, null, "role:reviewer", null);

        QhorusMcpTools.CheckResult result = tools.checkMessages("crd-role-2", 0L, 10, null, "non-reviewer", null);
        assertTrue(result.messages().isEmpty(),
                "agent without role:reviewer tag should not see the message");
    }

    // =========================================================================
    // Multi-tag agent — sees both capability and role targeted messages
    // =========================================================================

    @Test
    @TestTransaction
    void agentWithMultipleTagsSeesAllAddressedMessages() {
        tools.createChannel("crd-multi-1", "Test", "APPEND", null, null, null, null, null, null);
        tools.register("full-alice", "Full-stack agent", List.of("capability:code-review", "role:reviewer"), null, null);
        tools.sendMessage("crd-multi-1", "sender", "command", "cap msg", null, null, null, "capability:code-review", null);
        tools.sendMessage("crd-multi-1", "sender", "command", "role msg", null, null, null, "role:reviewer", null);
        tools.sendMessage("crd-multi-1", "sender", "status", "broadcast", null, null, null, null, null);

        QhorusMcpTools.CheckResult result = tools.checkMessages("crd-multi-1", 0L, 10, null, "full-alice", null);
        assertEquals(3, result.messages().size(),
                "agent with both tags should see cap-targeted, role-targeted, and broadcast messages");
    }

    // =========================================================================
    // Capability dispatch in get_replies
    // =========================================================================

    @Test
    @TestTransaction
    void capabilityTargetedReplyVisibleToAgentWithTag() {
        tools.createChannel("crd-rep-1", "Test", "APPEND", null, null, null, null, null, null);
        tools.register("code-reviewer", "Code reviewer", List.of("capability:code-review"), null, null);
        QhorusMcpTools.MessageResult parent = tools.sendMessage("crd-rep-1", "alice", "query", "question", null, null, null, null, null);
        tools.sendMessage("crd-rep-1", "bob", "response", "answer", null, parent.messageId(), null, "capability:code-review", null);

        List<QhorusMcpTools.MessageSummary> replies = tools.getReplies(parent.messageId(), "code-reviewer", null, null);
        assertEquals(1, replies.size(), "code reviewer should see capability-targeted reply");
    }

    @Test
    @TestTransaction
    void capabilityTargetedReplyHiddenFromAgentWithoutTag() {
        tools.createChannel("crd-rep-2", "Test", "APPEND", null, null, null, null, null, null);
        tools.register("non-reviewer", "Not a reviewer", List.of("capability:python"), null, null);
        QhorusMcpTools.MessageResult parent = tools.sendMessage("crd-rep-2", "alice", "query", "question", null, null, null, null, null);
        tools.sendMessage("crd-rep-2", "bob", "response", "answer", null, parent.messageId(), null, "capability:code-review", null);

        List<QhorusMcpTools.MessageSummary> replies = tools.getReplies(parent.messageId(), "non-reviewer", null, null);
        assertTrue(replies.isEmpty(), "agent without capability:code-review should not see the reply");
    }

    // =========================================================================
    // Capability dispatch in search_messages
    // =========================================================================

    @Test
    @TestTransaction
    void capabilityTargetedMessageFoundBySearchForAgentWithTag() {
        tools.createChannel("crd-srch-1", "Test", "APPEND", null, null, null, null, null, null);
        tools.register("searcher", "Code reviewer", List.of("capability:code-review"), null, null);
        tools.sendMessage("crd-srch-1", "sender", "command", "searchable review request", null, null, null, "capability:code-review", null);

        List<QhorusMcpTools.MessageSummary> results = tools.searchMessages("searchable", "crd-srch-1", 10, "searcher");
        assertEquals(1, results.size());
    }

    @Test
    @TestTransaction
    void capabilityTargetedMessageNotFoundBySearchForAgentWithoutTag() {
        tools.createChannel("crd-srch-2", "Test", "APPEND", null, null, null, null, null, null);
        tools.register("non-searcher", "Python dev", List.of("capability:python"), null, null);
        tools.sendMessage("crd-srch-2", "sender", "command", "searchable review request", null, null, null, "capability:code-review", null);

        List<QhorusMcpTools.MessageSummary> results = tools.searchMessages("searchable", "crd-srch-2", 10, "non-searcher");
        assertTrue(results.isEmpty());
    }
}
