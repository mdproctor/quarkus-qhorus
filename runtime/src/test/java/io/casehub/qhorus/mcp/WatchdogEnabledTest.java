package io.casehub.qhorus.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.casehub.qhorus.runtime.message.CommitmentService;
import io.casehub.qhorus.runtime.watchdog.WatchdogEvaluationService;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Issue #44 — Watchdog CRUD and condition evaluation (enabled profile).
 *
 * <p>
 * Evaluation logic is tested by calling {@code WatchdogEvaluationService.evaluateAll()}
 * directly rather than waiting for the Quarkus Scheduler to fire.
 *
 * <p>
 * Refs #44, Epic #36.
 */
@QuarkusTest
@TestProfile(io.casehub.qhorus.api.WatchdogEnabledProfile.class)
class WatchdogEnabledTest {

    @Inject
    QhorusMcpTools tools;

    @Inject
    WatchdogEvaluationService watchdogService;

    @Inject
    CommitmentService commitmentService;

    // -------------------------------------------------------------------------
    // CRUD — register / list / delete
    // -------------------------------------------------------------------------

    @Test
    @TestTransaction
    void registerWatchdogCreatesEntry() {
        tools.createChannel("wd-notif-1", "Alerts", null, null);

        QhorusMcpTools.WatchdogSummary summary = tools.registerWatchdog(
                "BARRIER_STUCK", "any-channel", 300, null, "wd-notif-1", "admin");

        assertNotNull(summary.id());
        assertEquals("BARRIER_STUCK", summary.conditionType());
        assertEquals("any-channel", summary.targetName());
        assertEquals(300, summary.thresholdSeconds());
        assertEquals("wd-notif-1", summary.notificationChannel());
    }

    @Test
    @TestTransaction
    void listWatchdogsShowsRegisteredEntry() {
        tools.createChannel("wd-notif-2", "Alerts", null, null);

        QhorusMcpTools.WatchdogSummary created = tools.registerWatchdog(
                "APPROVAL_PENDING", "*", 60, null, "wd-notif-2", "admin");

        List<QhorusMcpTools.WatchdogSummary> list = tools.listWatchdogs();
        assertTrue(list.stream().anyMatch(w -> created.id().equals(w.id())));
    }

    @Test
    @TestTransaction
    void deleteWatchdogRemovesIt() {
        tools.createChannel("wd-notif-3", "Alerts", null, null);

        QhorusMcpTools.WatchdogSummary created = tools.registerWatchdog(
                "CHANNEL_IDLE", "work-channel", 600, null, "wd-notif-3", "admin");

        tools.deleteWatchdog(created.id());

        List<QhorusMcpTools.WatchdogSummary> list = tools.listWatchdogs();
        assertFalse(list.stream().anyMatch(w -> created.id().equals(w.id())));
    }

    @Test
    @TestTransaction
    void deleteUnknownWatchdogReturnsFalse() {
        QhorusMcpTools.DeleteWatchdogResult result = tools.deleteWatchdog(UUID.randomUUID().toString());
        assertFalse(result.deleted());
    }

    // -------------------------------------------------------------------------
    // Condition evaluation — BARRIER_STUCK
    // -------------------------------------------------------------------------

    @Test
    void barrierStuckWatchdogFiresAlert() {
        tools.createChannel("wd-barrier-1", "Test", "BARRIER", "alice,bob");
        tools.createChannel("wd-notif-b1", "Alerts", null, null);

        // Register watchdog with threshold of 0s (always fires if not released)
        tools.registerWatchdog("BARRIER_STUCK", "wd-barrier-1", 0, null,
                "wd-notif-b1", "admin");

        // Alice writes but barrier is still stuck (bob missing)
        tools.sendMessage("wd-barrier-1", "alice", "response", "alice done",
                null, null, null, null);

        // Evaluate directly
        watchdogService.evaluateAll();

        // Alert should appear in notification channel
        QhorusMcpTools.CheckResult alerts = tools.checkMessages("wd-notif-b1", 0L, 10, null);
        assertFalse(alerts.messages().isEmpty(),
                "BARRIER_STUCK watchdog should post an alert to notification channel");
        assertTrue(alerts.messages().get(0).content().contains("BARRIER_STUCK"));
    }

    // -------------------------------------------------------------------------
    // Condition evaluation — APPROVAL_PENDING
    // -------------------------------------------------------------------------

    @Test
    void approvalPendingWatchdogFiresAlert() {
        tools.createChannel("wd-approval-1", "Approvals", null, null);
        tools.createChannel("wd-notif-a1", "Alerts", null, null);
        String corrId = UUID.randomUUID().toString();

        // Register watchdog with threshold of 0s
        tools.registerWatchdog("APPROVAL_PENDING", "*", 0, null, "wd-notif-a1", "admin");

        // Register a commitment directly — simulates what wait_for_reply does
        var ch = tools.listChannels().stream()
                .filter(c -> "wd-approval-1".equals(c.name()))
                .findFirst().orElseThrow();
        commitmentService.open(UUID.randomUUID(), corrId, ch.channelId(),
                MessageType.QUERY, "test-requester", null, Instant.now().plusSeconds(60));

        // Evaluate
        watchdogService.evaluateAll();

        QhorusMcpTools.CheckResult alerts = tools.checkMessages("wd-notif-a1", 0L, 10, null);
        assertFalse(alerts.messages().isEmpty(),
                "APPROVAL_PENDING watchdog should fire");
        assertTrue(alerts.messages().get(0).content().contains("APPROVAL_PENDING"));
    }

    // -------------------------------------------------------------------------
    // Condition evaluation — CHANNEL_IDLE
    // -------------------------------------------------------------------------

    @Test
    void channelIdleWatchdogFiresAlert() {
        tools.createChannel("wd-idle-1", "Idle Channel", null, null);
        tools.createChannel("wd-notif-i1", "Alerts", null, null);

        // Register watchdog with threshold 0s (always fires for any channel)
        tools.registerWatchdog("CHANNEL_IDLE", "wd-idle-1", 0, null,
                "wd-notif-i1", "admin");

        watchdogService.evaluateAll();

        QhorusMcpTools.CheckResult alerts = tools.checkMessages("wd-notif-i1", 0L, 10, null);
        assertFalse(alerts.messages().isEmpty(), "CHANNEL_IDLE watchdog should fire");
        assertTrue(alerts.messages().get(0).content().contains("CHANNEL_IDLE"));
    }

    // -------------------------------------------------------------------------
    // Condition evaluation — QUEUE_DEPTH
    // -------------------------------------------------------------------------

    @Test
    void queueDepthWatchdogFiresWhenThresholdExceeded() {
        tools.createChannel("wd-queue-1", "Work Queue", "COLLECT", null);
        tools.createChannel("wd-notif-q1", "Alerts", null, null);

        tools.registerWatchdog("QUEUE_DEPTH", "wd-queue-1", null, 2,
                "wd-notif-q1", "admin");

        // Add 3 messages — exceeds threshold of 2
        tools.sendMessage("wd-queue-1", "a1", "status", "m1", null, null, null, null);
        tools.sendMessage("wd-queue-1", "a2", "status", "m2", null, null, null, null);
        tools.sendMessage("wd-queue-1", "a3", "status", "m3", null, null, null, null);

        watchdogService.evaluateAll();

        QhorusMcpTools.CheckResult alerts = tools.checkMessages("wd-notif-q1", 0L, 10, null);
        assertFalse(alerts.messages().isEmpty(), "QUEUE_DEPTH watchdog should fire");
        assertTrue(alerts.messages().get(0).content().contains("QUEUE_DEPTH"));
    }

    @Test
    void queueDepthWatchdogDoesNotFireBelowThreshold() {
        tools.createChannel("wd-queue-2", "Work Queue", "COLLECT", null);
        tools.createChannel("wd-notif-q2", "Alerts", null, null);

        tools.registerWatchdog("QUEUE_DEPTH", "wd-queue-2", null, 5,
                "wd-notif-q2", "admin");

        // Only 2 messages — below threshold of 5
        tools.sendMessage("wd-queue-2", "a1", "status", "m1", null, null, null, null);
        tools.sendMessage("wd-queue-2", "a2", "status", "m2", null, null, null, null);

        watchdogService.evaluateAll();

        QhorusMcpTools.CheckResult alerts = tools.checkMessages("wd-notif-q2", 0L, 10, null);
        assertTrue(alerts.messages().isEmpty(), "QUEUE_DEPTH watchdog should not fire below threshold");
    }

    // -------------------------------------------------------------------------
    // Debounce — doesn't re-fire within threshold window
    // -------------------------------------------------------------------------

    @Test
    void watchdogDoesNotRefireWithinDebounceWindow() {
        tools.createChannel("wd-debounce-1", "Idle", null, null);
        tools.createChannel("wd-notif-d1", "Alerts", null, null);

        // Threshold 0s — fires immediately
        tools.registerWatchdog("CHANNEL_IDLE", "wd-debounce-1", 0, null,
                "wd-notif-d1", "admin");

        // First evaluation — fires
        watchdogService.evaluateAll();

        // Second evaluation — debounce should prevent re-fire
        // (lastFiredAt is now set, and threshold is 0s — allow 1s buffer)
        watchdogService.evaluateAll();

        QhorusMcpTools.CheckResult alerts = tools.checkMessages("wd-notif-d1", 0L, 20, null);
        assertEquals(1, alerts.messages().size(),
                "watchdog should fire exactly once due to debounce");
    }

    // -------------------------------------------------------------------------
    // E2E — register watchdog → condition met → alert posted
    // -------------------------------------------------------------------------

    @Test
    void e2eBarrierWatchdogFullLifecycle() {
        tools.createChannel("wd-e2e-barrier", "Work", "BARRIER", "alice,bob");
        tools.createChannel("wd-e2e-alerts", "Alerts", null, null);

        // 1. Ops team registers a watchdog
        QhorusMcpTools.WatchdogSummary watchdog = tools.registerWatchdog(
                "BARRIER_STUCK", "wd-e2e-barrier", 0, null,
                "wd-e2e-alerts", "ops-team");
        assertNotNull(watchdog.id());

        // 2. Alice writes but bob doesn't — barrier is stuck
        tools.sendMessage("wd-e2e-barrier", "alice", "response", "done",
                null, null, null, null);

        // 3. Watchdog fires
        watchdogService.evaluateAll();

        // 4. Alert visible in notification channel
        QhorusMcpTools.CheckResult alerts = tools.checkMessages("wd-e2e-alerts", 0L, 10, null);
        assertEquals(1, alerts.messages().size());
        assertEquals("watchdog", alerts.messages().get(0).sender());

        // 5. Ops deletes watchdog after acknowledging
        QhorusMcpTools.DeleteWatchdogResult deleted = tools.deleteWatchdog(watchdog.id());
        assertTrue(deleted.deleted());
        assertTrue(tools.listWatchdogs().stream().noneMatch(w -> watchdog.id().equals(w.id())));
    }
}
