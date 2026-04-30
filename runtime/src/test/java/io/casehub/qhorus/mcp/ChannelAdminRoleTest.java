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
 * Issue #47 — Admin role: designate an instance as channel admin with management authority.
 *
 * <p>
 * Three levels of testing:
 * <ul>
 * <li>Unit: admin check on each management tool; open governance (no admin list)</li>
 * <li>Integration: set_channel_admins; ChannelDetail; create_channel with admin_instances</li>
 * <li>E2E: multi-agent scenarios — admin manages, non-admins blocked; ACL coexistence</li>
 * </ul>
 *
 * <p>
 * Admin check applies to: {@code pause_channel}, {@code resume_channel},
 * {@code force_release_channel}, {@code clear_channel}.
 * When {@code admin_instances} is null/empty the tools remain open to any caller.
 *
 * <p>
 * Refs #47, Epic #45.
 */
@QuarkusTest
class ChannelAdminRoleTest {

    @Inject
    QhorusMcpTools tools;

    // =========================================================================
    // Unit — open governance (no admin list)
    // =========================================================================

    @Test
    @TestTransaction
    void openChannelWithNoAdminListAllowsAnyCallerToPause() {
        tools.createChannel("ar-open-1", "Open", null, null, null, null);

        assertDoesNotThrow(
                () -> tools.pauseChannel("ar-open-1", "anyone"),
                "channel with no admin_instances should accept any caller for pause_channel");
    }

    @Test
    @TestTransaction
    void openChannelWithNoAdminListAllowsAnyCallerToResume() {
        tools.createChannel("ar-open-2", "Open", null, null, null, null);
        tools.pauseChannel("ar-open-2", "someone");

        assertDoesNotThrow(
                () -> tools.resumeChannel("ar-open-2", "anyone"),
                "channel with no admin_instances should accept any caller for resume_channel");
    }

    @Test
    @TestTransaction
    void openChannelWithNoAdminListAllowsAnyCallerToForceRelease() {
        tools.createChannel("ar-open-3", "Open", "BARRIER", "alice,bob", null, null);

        assertDoesNotThrow(
                () -> tools.forceReleaseChannel("ar-open-3", "testing", "anyone"),
                "channel with no admin_instances should accept any caller for force_release_channel");
    }

    @Test
    @TestTransaction
    void openChannelWithNoAdminListAllowsAnyCallerToClear() {
        tools.createChannel("ar-open-4", "Open", null, null, null, null);
        tools.sendMessage("ar-open-4", "alice", "status", "msg", null, null, null, null);

        assertDoesNotThrow(
                () -> tools.clearChannel("ar-open-4", "anyone"),
                "channel with no admin_instances should accept any caller for clear_channel");
    }

    @Test
    @TestTransaction
    void createChannelDetailHasNullAdminInstances() {
        QhorusMcpTools.ChannelDetail detail = tools.createChannel(
                "ar-open-5", "Open", null, null, null, null);

        assertNull(detail.adminInstances(),
                "channel created without admin_instances should have null adminInstances in detail");
    }

    // =========================================================================
    // Unit — null caller treated as no admin check (backward compat)
    // =========================================================================

    @Test
    @TestTransaction
    void nullCallerIdBypasesAdminCheckForOpenChannel() {
        tools.createChannel("ar-null-1", "Open", null, null, null, null);

        // Original 1-arg overloads (no caller ID) must still work unchanged
        assertDoesNotThrow(() -> tools.pauseChannel("ar-null-1"),
                "original pause_channel with no caller_id should still work on open channel");
        assertDoesNotThrow(() -> tools.resumeChannel("ar-null-1"),
                "original resume_channel with no caller_id should still work on open channel");
    }

    // =========================================================================
    // Unit — listed admin can invoke management tools
    // =========================================================================

    @Test
    @TestTransaction
    void listedAdminCanPauseChannel() {
        tools.createChannel("ar-admin-1", "Admin gated", null, null, null, "alice-admin");

        assertDoesNotThrow(
                () -> tools.pauseChannel("ar-admin-1", "alice-admin"),
                "listed admin should be able to pause the channel");
    }

    @Test
    @TestTransaction
    void listedAdminCanResumeChannel() {
        tools.createChannel("ar-admin-2", "Admin gated", null, null, null, "alice-admin");
        tools.pauseChannel("ar-admin-2", "alice-admin");

        assertDoesNotThrow(
                () -> tools.resumeChannel("ar-admin-2", "alice-admin"),
                "listed admin should be able to resume the channel");
    }

    @Test
    @TestTransaction
    void listedAdminCanForceReleaseChannel() {
        tools.createChannel("ar-admin-3", "Admin gated", "BARRIER", "alice,bob", null, "alice-admin");

        assertDoesNotThrow(
                () -> tools.forceReleaseChannel("ar-admin-3", "admin override", "alice-admin"),
                "listed admin should be able to force_release_channel");
    }

    @Test
    @TestTransaction
    void listedAdminCanClearChannel() {
        tools.createChannel("ar-admin-4", "Admin gated", null, null, null, "alice-admin");
        tools.sendMessage("ar-admin-4", "alice-admin", "status", "msg", null, null, null, null);

        assertDoesNotThrow(
                () -> tools.clearChannel("ar-admin-4", "alice-admin"),
                "listed admin should be able to clear_channel");
    }

    @Test
    @TestTransaction
    void multipleAdminsAnyOneCanManage() {
        tools.createChannel("ar-admin-5", "Multi-admin", null, null, null, "alice-admin,bob-admin");

        assertDoesNotThrow(
                () -> tools.pauseChannel("ar-admin-5", "bob-admin"),
                "any listed admin (not just the first) should be accepted");
    }

    // =========================================================================
    // Unit — non-admin caller rejected
    // =========================================================================

    @Test
    @TestTransaction
    void nonAdminCannotPauseChannel() {
        tools.createChannel("ar-deny-1", "Admin gated", null, null, null, "alice-admin");

        ToolCallException ex = assertThrows(ToolCallException.class,
                () -> tools.pauseChannel("ar-deny-1", "mallory"),
                "non-admin should be rejected from pause_channel");

        String msg = ex.getMessage();
        assertTrue(msg.contains("mallory"),
                "error should name the rejected caller, was: " + msg);
        assertTrue(msg.toLowerCase().contains("admin") || msg.toLowerCase().contains("not permitted"),
                "error should indicate admin restriction, was: " + msg);
    }

    @Test
    @TestTransaction
    void nonAdminCannotResumeChannel() {
        tools.createChannel("ar-deny-2", "Admin gated", null, null, null, "alice-admin");
        tools.pauseChannel("ar-deny-2", "alice-admin");

        assertThrows(ToolCallException.class,
                () -> tools.resumeChannel("ar-deny-2", "mallory"),
                "non-admin should be rejected from resume_channel");
    }

    @Test
    @TestTransaction
    void nonAdminCannotForceReleaseChannel() {
        tools.createChannel("ar-deny-3", "Admin gated", "BARRIER", "alice,bob", null, "alice-admin");

        assertThrows(ToolCallException.class,
                () -> tools.forceReleaseChannel("ar-deny-3", "reason", "mallory"),
                "non-admin should be rejected from force_release_channel");
    }

    @Test
    @TestTransaction
    void nonAdminCannotClearChannel() {
        tools.createChannel("ar-deny-4", "Admin gated", null, null, null, "alice-admin");

        assertThrows(ToolCallException.class,
                () -> tools.clearChannel("ar-deny-4", "mallory"),
                "non-admin should be rejected from clear_channel");
    }

    // =========================================================================
    // Integration — set_channel_admins
    // =========================================================================

    @Test
    @TestTransaction
    void setChannelAdminsAppliesAdminListToExistingChannel() {
        tools.createChannel("ar-scа-1", "Open initially", null, null, null, null);
        // Before: any caller can manage
        assertDoesNotThrow(() -> tools.pauseChannel("ar-scа-1", "mallory"));
        tools.resumeChannel("ar-scа-1", "mallory");

        // Apply admin list
        QhorusMcpTools.ChannelDetail updated = tools.setChannelAdmins("ar-scа-1", "alice-admin");
        assertEquals("alice-admin", updated.adminInstances(),
                "setChannelAdmins should return ChannelDetail with the new adminInstances");

        // Now mallory is blocked
        assertThrows(ToolCallException.class,
                () -> tools.pauseChannel("ar-scа-1", "mallory"));
        // Alice is allowed
        assertDoesNotThrow(() -> tools.pauseChannel("ar-scа-1", "alice-admin"));
    }

    @Test
    @TestTransaction
    void setChannelAdminsToNullClearsAdminList() {
        tools.createChannel("ar-sca-2", "Admin gated", null, null, null, "alice-admin");

        // Non-admin is blocked
        assertThrows(ToolCallException.class,
                () -> tools.pauseChannel("ar-sca-2", "bob"));

        // Clear admin list
        QhorusMcpTools.ChannelDetail cleared = tools.setChannelAdmins("ar-sca-2", null);
        assertNull(cleared.adminInstances(), "clearing admin list should result in null adminInstances");

        // Bob now allowed
        assertDoesNotThrow(() -> tools.pauseChannel("ar-sca-2", "bob"));
    }

    @Test
    @TestTransaction
    void setChannelAdminsOnUnknownChannelThrows() {
        assertThrows(ToolCallException.class,
                () -> tools.setChannelAdmins("no-such-channel", "alice-admin"),
                "setChannelAdmins on non-existent channel should throw IllegalArgumentException");
    }

    // =========================================================================
    // Integration — ChannelDetail reflects adminInstances
    // =========================================================================

    @Test
    @TestTransaction
    void createChannelDetailIncludesAdminInstances() {
        QhorusMcpTools.ChannelDetail detail = tools.createChannel(
                "ar-det-1", "Admin channel", null, null, null, "carol-admin");

        assertEquals("carol-admin", detail.adminInstances(),
                "ChannelDetail from createChannel should expose adminInstances");
    }

    @Test
    @TestTransaction
    void listChannelsIncludesAdminInstances() {
        tools.createChannel("ar-det-2", "Admin channel", null, null, null, "dave-admin");

        QhorusMcpTools.ChannelDetail found = tools.listChannels().stream()
                .filter(d -> "ar-det-2".equals(d.name()))
                .findFirst().orElseThrow();

        assertEquals("dave-admin", found.adminInstances(),
                "listChannels ChannelDetail should include adminInstances");
    }

    // =========================================================================
    // Integration — allowed_writers and admin_instances are independent
    // =========================================================================

    @Test
    @TestTransaction
    void allowedWritersAndAdminInstancesAreIndependent() {
        // alice can write (allowed_writers); bob is admin (admin_instances)
        tools.createChannel("ar-ind-1", "Dual ACL", null, null, "alice", "bob-admin");

        // alice can write but cannot manage (not an admin)
        assertDoesNotThrow(() -> tools.sendMessage("ar-ind-1", "alice", "status", "hi", null, null, null, null));
        assertThrows(ToolCallException.class,
                () -> tools.pauseChannel("ar-ind-1", "alice"),
                "alice is an allowed writer but not an admin — should be rejected from managing");

        // bob can manage but cannot write (not in allowed_writers)
        assertDoesNotThrow(() -> tools.pauseChannel("ar-ind-1", "bob-admin"));
        assertThrows(ToolCallException.class,
                () -> tools.sendMessage("ar-ind-1", "bob-admin", "status", "hi", null, null, null, null),
                "bob is an admin but not an allowed writer — should be rejected from writing");
    }

    // =========================================================================
    // E2E — three agents: one admin, two non-admins
    // =========================================================================

    @Test
    @TestTransaction
    void e2eOnlyAdminCanManageChannel() {
        tools.createChannel("ar-e2e-1", "Governed channel", "APPEND", null, null, "admin-agent");
        tools.register("admin-agent", "Admin", List.of(), null);
        tools.register("worker-a", "Worker A", List.of(), null);
        tools.register("worker-b", "Worker B", List.of(), null);

        // Workers can send messages (no write ACL)
        assertDoesNotThrow(() -> tools.sendMessage("ar-e2e-1", "worker-a", "status", "work", null, null, null, null));
        assertDoesNotThrow(() -> tools.sendMessage("ar-e2e-1", "worker-b", "status", "work", null, null, null, null));

        // Neither worker can pause
        assertThrows(ToolCallException.class, () -> tools.pauseChannel("ar-e2e-1", "worker-a"));
        assertThrows(ToolCallException.class, () -> tools.pauseChannel("ar-e2e-1", "worker-b"));

        // Admin pauses successfully
        assertDoesNotThrow(() -> tools.pauseChannel("ar-e2e-1", "admin-agent"));

        // Workers cannot resume
        assertThrows(ToolCallException.class, () -> tools.resumeChannel("ar-e2e-1", "worker-a"));

        // Admin resumes
        assertDoesNotThrow(() -> tools.resumeChannel("ar-e2e-1", "admin-agent"));

        // Admin clears
        QhorusMcpTools.ClearChannelResult cleared = tools.clearChannel("ar-e2e-1", "admin-agent");
        assertTrue(cleared.cleared());
        assertEquals(2, cleared.messagesDeleted(), "both worker messages should be cleared");
    }

    // =========================================================================
    // E2E — admin role coexists with pause/resume state correctly
    // =========================================================================

    @Test
    @TestTransaction
    void e2eAdminCanPauseResumeCycleWhileNonAdminsAreBlocked() {
        tools.createChannel("ar-e2e-2", "Cycle test", "APPEND", null, null, "admin-agent");

        // Workers can write before pause
        tools.sendMessage("ar-e2e-2", "worker", "status", "before", null, null, null, null);

        // Admin pauses
        tools.pauseChannel("ar-e2e-2", "admin-agent");

        // Worker cannot write (paused) AND cannot resume (not admin)
        assertThrows(ToolCallException.class,
                () -> tools.sendMessage("ar-e2e-2", "worker", "status", "during", null, null, null, null));
        assertThrows(ToolCallException.class,
                () -> tools.resumeChannel("ar-e2e-2", "worker"));

        // Admin resumes
        tools.resumeChannel("ar-e2e-2", "admin-agent");

        // Worker can write again
        tools.sendMessage("ar-e2e-2", "worker", "status", "after", null, null, null, null);

        QhorusMcpTools.CheckResult result = tools.checkMessages("ar-e2e-2", 0L, 10, null);
        assertEquals(2, result.messages().size(), "before and after messages both present");
    }
}
