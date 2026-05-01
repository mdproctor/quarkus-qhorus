package io.casehub.qhorus.mcp;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.mcp.server.ToolCallException;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Issue #37 — Channel pause/resume: pause_channel and resume_channel MCP tools.
 *
 * <p>
 * Three levels of testing:
 * <ul>
 * <li>Unit: pause/resume semantics, idempotency, paused field on ChannelDetail</li>
 * <li>Integration: full pause→fail→resume→succeed cycle</li>
 * <li>E2E: agents cannot post to paused channel; can again after resume</li>
 * </ul>
 *
 * <p>
 * Refs #37, Epic #36.
 */
@QuarkusTest
class ChannelPauseResumeTest {

    @Inject
    QhorusMcpTools tools;

    // -------------------------------------------------------------------------
    // Unit — pause semantics
    // -------------------------------------------------------------------------

    @Test
    @TestTransaction
    void newChannelIsNotPaused() {
        QhorusMcpTools.ChannelDetail detail = tools.createChannel("pr-new-1", "Test", null, null, null, null, null, null, null);
        assertFalse(detail.paused(), "newly created channel should not be paused");
    }

    @Test
    @TestTransaction
    void pauseChannelSetsPausedTrue() {
        tools.createChannel("pr-pause-1", "Test", null, null, null, null, null, null, null);
        QhorusMcpTools.ChannelDetail detail = tools.pauseChannel("pr-pause-1", null);
        assertTrue(detail.paused(), "channel should be paused after pause_channel");
    }

    @Test
    @TestTransaction
    void pauseChannelIsIdempotent() {
        tools.createChannel("pr-pause-2", "Test", null, null, null, null, null, null, null);
        tools.pauseChannel("pr-pause-2", null);
        // Second call must not throw
        QhorusMcpTools.ChannelDetail detail = tools.pauseChannel("pr-pause-2", null);
        assertTrue(detail.paused(), "channel should still be paused after second pause call");
    }

    @Test
    @TestTransaction
    void pauseUnknownChannelThrowsIllegalArgument() {
        assertThrows(ToolCallException.class,
                () -> tools.pauseChannel("no-such-channel", null),
                "pausing an unknown channel should throw IllegalArgumentException");
    }

    // -------------------------------------------------------------------------
    // Unit — resume semantics
    // -------------------------------------------------------------------------

    @Test
    @TestTransaction
    void resumeChannelSetsPausedFalse() {
        tools.createChannel("pr-resume-1", "Test", null, null, null, null, null, null, null);
        tools.pauseChannel("pr-resume-1", null);
        QhorusMcpTools.ChannelDetail detail = tools.resumeChannel("pr-resume-1", null);
        assertFalse(detail.paused(), "channel should not be paused after resume_channel");
    }

    @Test
    @TestTransaction
    void resumeUnpausedChannelIsIdempotent() {
        tools.createChannel("pr-resume-2", "Test", null, null, null, null, null, null, null);
        // Resume a channel that was never paused — must not throw
        QhorusMcpTools.ChannelDetail detail = tools.resumeChannel("pr-resume-2", null);
        assertFalse(detail.paused(), "channel should not be paused after resuming an already-active channel");
    }

    @Test
    @TestTransaction
    void resumeUnknownChannelThrowsIllegalArgument() {
        assertThrows(ToolCallException.class,
                () -> tools.resumeChannel("no-such-channel", null));
    }

    // -------------------------------------------------------------------------
    // Unit — send_message blocked on paused channel
    // -------------------------------------------------------------------------

    @Test
    @TestTransaction
    void sendMessageOnPausedChannelThrows() {
        tools.createChannel("pr-send-1", "Test", null, null, null, null, null, null, null);
        tools.pauseChannel("pr-send-1", null);

        ToolCallException ex = assertThrows(ToolCallException.class,
                () -> tools.sendMessage("pr-send-1", "alice", "status", "hello", null, null, null, null, null));
        assertTrue(ex.getMessage().toLowerCase().contains("paused"),
                "error message should mention 'paused'");
    }

    @Test
    @TestTransaction
    void sendMessageOnResumedChannelSucceeds() {
        tools.createChannel("pr-send-2", "Test", null, null, null, null, null, null, null);
        tools.pauseChannel("pr-send-2", null);
        tools.resumeChannel("pr-send-2", null);

        assertDoesNotThrow(
                () -> tools.sendMessage("pr-send-2", "alice", "status", "hello", null, null, null, null, null));
    }

    // -------------------------------------------------------------------------
    // Unit — check_messages on paused channel
    // -------------------------------------------------------------------------

    @Test
    @TestTransaction
    void checkMessagesOnPausedChannelReturnsEmptyWithStatus() {
        tools.createChannel("pr-check-1", "Test", null, null, null, null, null, null, null);
        tools.sendMessage("pr-check-1", "alice", "status", "before pause", null, null, null, null, null);
        tools.pauseChannel("pr-check-1", null);

        QhorusMcpTools.CheckResult result = tools.checkMessages("pr-check-1", 0L, 10, null, null, null);
        assertTrue(result.messages().isEmpty(),
                "check_messages on paused channel should return no messages");
        assertNotNull(result.barrierStatus(), "paused channel should return a non-null status");
        assertTrue(result.barrierStatus().toLowerCase().contains("paused"),
                "status should indicate channel is paused");
    }

    @Test
    @TestTransaction
    void checkMessagesOnResumedChannelReturnsMessages() {
        tools.createChannel("pr-check-2", "Test", null, null, null, null, null, null, null);
        tools.sendMessage("pr-check-2", "alice", "status", "before pause", null, null, null, null, null);
        tools.pauseChannel("pr-check-2", null);
        tools.resumeChannel("pr-check-2", null);

        QhorusMcpTools.CheckResult result = tools.checkMessages("pr-check-2", 0L, 10, null, null, null);
        assertEquals(1, result.messages().size(),
                "check_messages on resumed channel should return messages again");
        assertNull(result.barrierStatus(), "resumed channel should have null barrierStatus");
    }

    // -------------------------------------------------------------------------
    // Unit — ChannelDetail reflects paused state
    // -------------------------------------------------------------------------

    @Test
    @TestTransaction
    void channelDetailPausedFieldReflectsCurrentState() {
        tools.createChannel("pr-detail-1", "Test", null, null, null, null, null, null, null);

        // Initially not paused
        QhorusMcpTools.ChannelDetail before = tools.listChannels().stream()
                .filter(d -> d.name().equals("pr-detail-1"))
                .findFirst().orElseThrow();
        assertFalse(before.paused());

        tools.pauseChannel("pr-detail-1", null);

        QhorusMcpTools.ChannelDetail after = tools.listChannels().stream()
                .filter(d -> d.name().equals("pr-detail-1"))
                .findFirst().orElseThrow();
        assertTrue(after.paused());
    }

    // -------------------------------------------------------------------------
    // Integration — full pause → fail → resume → succeed cycle
    // -------------------------------------------------------------------------

    @Test
    @TestTransaction
    void fullPauseResumeCycle() {
        tools.createChannel("pr-cycle-1", "Test", "APPEND", null, null, null, null, null, null);

        // 1. Send before pause — succeeds
        assertDoesNotThrow(() -> tools.sendMessage("pr-cycle-1", "alice", "status", "msg1", null, null, null, null, null));

        // 2. Pause
        tools.pauseChannel("pr-cycle-1", null);

        // 3. Send while paused — fails
        assertThrows(ToolCallException.class,
                () -> tools.sendMessage("pr-cycle-1", "alice", "status", "msg2", null, null, null, null, null));

        // 4. check_messages while paused — empty + status
        QhorusMcpTools.CheckResult paused = tools.checkMessages("pr-cycle-1", 0L, 10, null, null, null);
        assertTrue(paused.messages().isEmpty());
        assertNotNull(paused.barrierStatus());

        // 5. Resume
        tools.resumeChannel("pr-cycle-1", null);

        // 6. Send after resume — succeeds
        assertDoesNotThrow(() -> tools.sendMessage("pr-cycle-1", "alice", "status", "msg3", null, null, null, null, null));

        // 7. check_messages after resume — shows msg1 and msg3 (msg2 never stored)
        QhorusMcpTools.CheckResult resumed = tools.checkMessages("pr-cycle-1", 0L, 10, null, null, null);
        assertEquals(2, resumed.messages().size());
        assertNull(resumed.barrierStatus());
    }

    // -------------------------------------------------------------------------
    // E2E — multiple agents; pause blocks all; resume unblocks all
    // -------------------------------------------------------------------------

    @Test
    @TestTransaction
    void e2eMultipleAgentsBlockedByPause() {
        tools.createChannel("pr-e2e-1", "Test", "APPEND", null, null, null, null, null, null);
        tools.register("alice-agent", "Alice", java.util.List.of(), null, null);
        tools.register("bob-agent", "Bob", java.util.List.of(), null, null);

        // Both can send before pause
        tools.sendMessage("pr-e2e-1", "alice-agent", "command", "alice work", null, null, null, null, null);
        tools.sendMessage("pr-e2e-1", "bob-agent", "response", "bob work", null, null, null, null, null);

        // Human pauses the channel
        tools.pauseChannel("pr-e2e-1", null);

        // Neither agent can send
        assertThrows(ToolCallException.class,
                () -> tools.sendMessage("pr-e2e-1", "alice-agent", "status", "update", null, null, null, null, null));
        assertThrows(ToolCallException.class,
                () -> tools.sendMessage("pr-e2e-1", "bob-agent", "status", "update", null, null, null, null, null));

        // Human resumes
        tools.resumeChannel("pr-e2e-1", null);

        // Both agents can send again
        assertDoesNotThrow(() -> tools.sendMessage("pr-e2e-1", "alice-agent", "status", "alice resumes", null, null, null, null, null));
        assertDoesNotThrow(() -> tools.sendMessage("pr-e2e-1", "bob-agent", "status", "bob resumes", null, null, null, null, null));

        // All 4 messages visible (2 before pause, 2 after resume)
        QhorusMcpTools.CheckResult result = tools.checkMessages("pr-e2e-1", 0L, 20, null, null, null);
        assertEquals(4, result.messages().size());
    }
}
