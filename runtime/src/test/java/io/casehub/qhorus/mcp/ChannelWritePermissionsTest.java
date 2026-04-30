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
 * Issue #46 — Per-channel write permissions: allowed_writers ACL on Channel.
 *
 * <p>
 * Three levels of testing:
 * <ul>
 * <li>Unit: ACL enforcement on send_message — instance ID, capability:tag, role:tag</li>
 * <li>Integration: set_channel_writers mid-stream; clear ACL reopens channel; ChannelDetail</li>
 * <li>E2E: full multi-agent scenarios exercising ACL lifecycle</li>
 * </ul>
 *
 * <p>
 * ACL entry formats (comma-separated in allowed_writers):
 * <ul>
 * <li>Bare instance ID: {@code alice} — matches sender == "alice"</li>
 * <li>Capability tag: {@code capability:code-review} — matches sender registered with that tag</li>
 * <li>Role tag: {@code role:reviewer} — matches sender registered with that tag</li>
 * </ul>
 *
 * <p>
 * Refs #46, Epic #45.
 */
@QuarkusTest
class ChannelWritePermissionsTest {

    @Inject
    QhorusMcpTools tools;

    // =========================================================================
    // Unit — no ACL (open channel)
    // =========================================================================

    @Test
    @TestTransaction
    void openChannelWithNoAclAllowsAnyWriter() {
        tools.createChannel("wp-open-1", "Open channel", null, null, null);

        assertDoesNotThrow(
                () -> tools.sendMessage("wp-open-1", "anyone", "status", "hello", null, null, null, null),
                "channel with no allowed_writers should accept any sender");
    }

    @Test
    @TestTransaction
    void openChannelDetailHasNullAllowedWriters() {
        QhorusMcpTools.ChannelDetail detail = tools.createChannel("wp-open-2", "Open", null, null, null);

        assertNull(detail.allowedWriters(),
                "channel created without allowed_writers should have null allowedWriters in detail");
    }

    // =========================================================================
    // Unit — instance ID match
    // =========================================================================

    @Test
    @TestTransaction
    void listedInstanceIdCanSend() {
        tools.createChannel("wp-iid-1", "ACL by ID", null, null, "alice,bob");

        assertDoesNotThrow(
                () -> tools.sendMessage("wp-iid-1", "alice", "status", "hello", null, null, null, null),
                "sender in allowed_writers should be accepted");
    }

    @Test
    @TestTransaction
    void secondListedInstanceIdCanSend() {
        tools.createChannel("wp-iid-2", "ACL by ID", null, null, "alice,bob");

        assertDoesNotThrow(
                () -> tools.sendMessage("wp-iid-2", "bob", "status", "hello", null, null, null, null),
                "second sender in allowed_writers should be accepted");
    }

    @Test
    @TestTransaction
    void unlistedSenderRejectedWithClearError() {
        tools.createChannel("wp-iid-3", "ACL by ID", null, null, "alice,bob");

        ToolCallException ex = assertThrows(ToolCallException.class,
                () -> tools.sendMessage("wp-iid-3", "mallory", "status", "intrude", null, null, null, null),
                "sender not in allowed_writers should be rejected");

        String msg = ex.getMessage().toLowerCase();
        assertTrue(msg.contains("not permitted") || msg.contains("not allowed") || msg.contains("denied"),
                "error message should indicate the sender is not permitted, was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("mallory"),
                "error message should name the rejected sender, was: " + ex.getMessage());
    }

    @Test
    @TestTransaction
    void allowedWritersEntriesAreStripped() {
        // Spaces around entries should not prevent matching
        tools.createChannel("wp-iid-4", "ACL trimmed", null, null, " alice , bob ");

        assertDoesNotThrow(
                () -> tools.sendMessage("wp-iid-4", "alice", "status", "hello", null, null, null, null),
                "whitespace around entries should be stripped before matching");
    }

    // =========================================================================
    // Unit — capability:tag match
    // =========================================================================

    @Test
    @TestTransaction
    void senderWithMatchingCapabilityTagCanWrite() {
        tools.createChannel("wp-cap-1", "ACL by capability", null, null, "capability:code-review");
        tools.register("reviewer-alice", "Code reviewer", List.of("capability:code-review"), null);

        assertDoesNotThrow(
                () -> tools.sendMessage("wp-cap-1", "reviewer-alice", "status", "lgtm", null, null, null, null),
                "sender registered with matching capability:tag should be allowed");
    }

    @Test
    @TestTransaction
    void senderWithoutMatchingCapabilityTagIsRejected() {
        tools.createChannel("wp-cap-2", "ACL by capability", null, null, "capability:code-review");
        tools.register("python-bob", "Python dev", List.of("capability:python"), null);

        assertThrows(ToolCallException.class,
                () -> tools.sendMessage("wp-cap-2", "python-bob", "status", "hello", null, null, null, null),
                "sender without the required capability should be rejected");
    }

    @Test
    @TestTransaction
    void unregisteredSenderCannotMatchCapabilityTag() {
        tools.createChannel("wp-cap-3", "ACL by capability", null, null, "capability:code-review");
        // "ghost" is not registered — has no capability tags

        assertThrows(ToolCallException.class,
                () -> tools.sendMessage("wp-cap-3", "ghost", "status", "hello", null, null, null, null),
                "unregistered sender has no capabilities and should be rejected");
    }

    // =========================================================================
    // Unit — role:tag match
    // =========================================================================

    @Test
    @TestTransaction
    void senderWithMatchingRoleTagCanWrite() {
        tools.createChannel("wp-role-1", "ACL by role", null, null, "role:reviewer");
        tools.register("reviewer-carol", "Senior reviewer", List.of("role:reviewer"), null);

        assertDoesNotThrow(
                () -> tools.sendMessage("wp-role-1", "reviewer-carol", "status", "approved", null, null, null, null),
                "sender registered with matching role:tag should be allowed");
    }

    @Test
    @TestTransaction
    void senderWithWrongRoleTagIsRejected() {
        tools.createChannel("wp-role-2", "ACL by role", null, null, "role:reviewer");
        tools.register("junior-dave", "Junior dev", List.of("role:developer"), null);

        assertThrows(ToolCallException.class,
                () -> tools.sendMessage("wp-role-2", "junior-dave", "status", "hi", null, null, null, null),
                "sender with different role should be rejected");
    }

    // =========================================================================
    // Unit — mixed ACL (instance IDs + capability tags)
    // =========================================================================

    @Test
    @TestTransaction
    void mixedAclAcceptsInstanceIdAndCapabilityTag() {
        tools.createChannel("wp-mix-1", "Mixed ACL", null, null, "alice,capability:code-review");
        tools.register("reviewer-eve", "Reviewer", List.of("capability:code-review"), null);

        // Instance ID match
        assertDoesNotThrow(
                () -> tools.sendMessage("wp-mix-1", "alice", "status", "direct", null, null, null, null));
        // Capability match
        assertDoesNotThrow(
                () -> tools.sendMessage("wp-mix-1", "reviewer-eve", "status", "cap", null, null, null, null));
    }

    @Test
    @TestTransaction
    void mixedAclRejectsNonMatchingSender() {
        tools.createChannel("wp-mix-2", "Mixed ACL", null, null, "alice,capability:code-review");
        tools.register("plain-bob", "No capabilities", List.of(), null);

        assertThrows(ToolCallException.class,
                () -> tools.sendMessage("wp-mix-2", "plain-bob", "status", "hi", null, null, null, null),
                "sender matching neither instance ID nor capability should be rejected");
    }

    // =========================================================================
    // Unit — ACL does not block event messages (EVENT bypass)
    // =========================================================================

    @Test
    @TestTransaction
    void eventMessagesPassAclCheck() {
        // EVENT messages are telemetry — they bypass all channel controls including ACL
        tools.createChannel("wp-evt-1", "ACL channel", null, null, "alice");

        assertDoesNotThrow(
                () -> tools.sendMessage("wp-evt-1", "system", "event", "audit entry", null, null, null, null),
                "EVENT messages should bypass the allowed_writers ACL check");
    }

    // =========================================================================
    // Integration — set_channel_writers
    // =========================================================================

    @Test
    @TestTransaction
    void setChannelWritersAppliesAclToExistingChannel() {
        tools.createChannel("wp-scw-1", "Initially open", null, null, null);
        // Send before ACL — succeeds
        tools.sendMessage("wp-scw-1", "mallory", "status", "before acl", null, null, null, null);

        // Apply ACL
        QhorusMcpTools.ChannelDetail updated = tools.setChannelWriters("wp-scw-1", "alice");
        assertEquals("alice", updated.allowedWriters(),
                "setChannelWriters should return updated ChannelDetail with the new ACL");

        // Now mallory is blocked
        assertThrows(ToolCallException.class,
                () -> tools.sendMessage("wp-scw-1", "mallory", "status", "after acl", null, null, null, null));
        // Alice is allowed
        assertDoesNotThrow(
                () -> tools.sendMessage("wp-scw-1", "alice", "status", "allowed", null, null, null, null));
    }

    @Test
    @TestTransaction
    void setChannelWritersToNullOrBlankClearsAcl() {
        tools.createChannel("wp-scw-2", "Starts with ACL", null, null, "alice");

        // Blocked before clearing
        assertThrows(ToolCallException.class,
                () -> tools.sendMessage("wp-scw-2", "bob", "status", "nope", null, null, null, null));

        // Clear ACL
        QhorusMcpTools.ChannelDetail cleared = tools.setChannelWriters("wp-scw-2", null);
        assertNull(cleared.allowedWriters(), "clearing ACL should result in null allowedWriters");

        // Bob now allowed
        assertDoesNotThrow(
                () -> tools.sendMessage("wp-scw-2", "bob", "status", "now allowed", null, null, null, null));
    }

    @Test
    @TestTransaction
    void setChannelWritersOnUnknownChannelThrows() {
        assertThrows(ToolCallException.class,
                () -> tools.setChannelWriters("no-such-channel", "alice"),
                "setChannelWriters on a non-existent channel should throw IllegalArgumentException");
    }

    // =========================================================================
    // Integration — ChannelDetail reflects allowedWriters
    // =========================================================================

    @Test
    @TestTransaction
    void createChannelDetailIncludesAllowedWriters() {
        QhorusMcpTools.ChannelDetail detail = tools.createChannel(
                "wp-det-1", "With ACL", null, null, "alice,bob");

        assertEquals("alice,bob", detail.allowedWriters(),
                "ChannelDetail from createChannel should expose allowedWriters");
    }

    @Test
    @TestTransaction
    void listChannelsIncludesAllowedWriters() {
        tools.createChannel("wp-det-2", "ACL channel", null, null, "carol");

        QhorusMcpTools.ChannelDetail found = tools.listChannels().stream()
                .filter(d -> "wp-det-2".equals(d.name()))
                .findFirst().orElseThrow();

        assertEquals("carol", found.allowedWriters(),
                "listChannels ChannelDetail should include allowedWriters");
    }

    @Test
    @TestTransaction
    void findChannelIncludesAllowedWriters() {
        tools.createChannel("wp-det-3", "Searchable ACL", null, null, "dave");

        QhorusMcpTools.ChannelDetail found = tools.findChannel("wp-det-3").stream()
                .findFirst().orElseThrow();

        assertEquals("dave", found.allowedWriters(),
                "findChannel ChannelDetail should include allowedWriters");
    }

    // =========================================================================
    // E2E — three-agent scenario with instance ID ACL
    // =========================================================================

    @Test
    @TestTransaction
    void e2eThreeAgentsMixedAcl() {
        // Channel: only alice and bob can write, carol cannot
        tools.createChannel("wp-e2e-1", "Restricted channel", "APPEND", null, "alice-agent,bob-agent");
        tools.register("alice-agent", "Alice", List.of(), null);
        tools.register("bob-agent", "Bob", List.of(), null);
        tools.register("carol-agent", "Carol", List.of(), null);

        // Alice and Bob can write
        assertDoesNotThrow(() -> tools.sendMessage("wp-e2e-1", "alice-agent", "command",
                "alice work", null, null, null, null));
        assertDoesNotThrow(() -> tools.sendMessage("wp-e2e-1", "bob-agent", "response",
                "bob response", null, null, null, null));

        // Carol is denied
        ToolCallException ex = assertThrows(ToolCallException.class,
                () -> tools.sendMessage("wp-e2e-1", "carol-agent", "status",
                        "carol intruding", null, null, null, null));
        assertTrue(ex.getMessage().contains("carol-agent"),
                "rejection error should name the rejected sender");

        // Only alice and bob messages exist
        QhorusMcpTools.CheckResult result = tools.checkMessages("wp-e2e-1", 0L, 10, null);
        assertEquals(2, result.messages().size(), "only alice and bob messages should be stored");
        assertTrue(result.messages().stream().allMatch(m -> List.of("alice-agent", "bob-agent").contains(m.sender())));
    }

    // =========================================================================
    // E2E — capability-based ACL: only agents with the right capability can write
    // =========================================================================

    @Test
    @TestTransaction
    void e2eCapabilityBasedAcl() {
        tools.createChannel("wp-e2e-2", "Review channel", "COLLECT", null, "capability:reviewer");
        tools.register("sr-reviewer", "Senior reviewer", List.of("capability:reviewer"), null);
        tools.register("jr-developer", "Junior dev", List.of("capability:developer"), null);

        // SR reviewer can write
        assertDoesNotThrow(() -> tools.sendMessage("wp-e2e-2", "sr-reviewer", "status",
                "lgtm", null, null, null, null));

        // Junior dev cannot
        assertThrows(ToolCallException.class,
                () -> tools.sendMessage("wp-e2e-2", "jr-developer", "status",
                        "me too", null, null, null, null));

        // ACL updated to also allow junior-dev
        tools.setChannelWriters("wp-e2e-2", "capability:reviewer,capability:developer");

        // Junior dev now can write
        assertDoesNotThrow(() -> tools.sendMessage("wp-e2e-2", "jr-developer", "status",
                "now I can", null, null, null, null));
    }

    // =========================================================================
    // E2E — ACL coexists with pause/resume controls
    // =========================================================================

    @Test
    @TestTransaction
    void e2eAclAndPauseAreIndependent() {
        tools.createChannel("wp-e2e-3", "Test", "APPEND", null, "alice");

        // Pause channel — alice (listed) still blocked by pause
        tools.pauseChannel("wp-e2e-3");
        assertThrows(ToolCallException.class,
                () -> tools.sendMessage("wp-e2e-3", "alice", "status", "paused", null, null, null, null));

        // Resume — alice can write, bob still ACL-blocked
        tools.resumeChannel("wp-e2e-3");
        assertDoesNotThrow(
                () -> tools.sendMessage("wp-e2e-3", "alice", "status", "resumed", null, null, null, null));
        assertThrows(ToolCallException.class,
                () -> tools.sendMessage("wp-e2e-3", "bob", "status", "still blocked", null, null, null, null));
    }
}
