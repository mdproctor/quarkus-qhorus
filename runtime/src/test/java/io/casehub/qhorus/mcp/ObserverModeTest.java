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
 * Issue #49, #121-G — Read-only observer mode via read_only flag on Instance.
 *
 * <p>
 * Three levels of testing:
 * <ul>
 * <li>Unit: registration with read_only=true (creates Instance row, visible in list_instances); send rejection</li>
 * <li>Integration: read_only instance reads EVENT messages via check_messages(include_events=true); lifecycle</li>
 * <li>E2E: full dashboard-observer scenario — agents work, read_only instance watches events only</li>
 * </ul>
 *
 * <p>
 * Replaces the former ObserverRegistry (in-memory, ephemeral) with persistent read_only flag on Instance.
 *
 * <p>
 * Refs #49, Epic #45, Refs #121.
 */
@QuarkusTest
class ObserverModeTest {

    @Inject
    QhorusMcpTools tools;

    // =========================================================================
    // Unit — registration contract
    // =========================================================================

    @Test
    @TestTransaction
    void registerReadOnlyInstanceCreatesInstanceRecord() {
        tools.createChannel("obs-reg-1", "Test channel", null, null);

        QhorusMcpTools.RegisterResponse result = tools.register(
                "dashboard-obs", "Dashboard observer", List.of(), null, true);

        assertNotNull(result, "register should return a result");
        assertEquals("dashboard-obs", result.instanceId());
    }

    @Test
    @TestTransaction
    void readOnlyInstanceAppearsInListInstances() {
        tools.createChannel("obs-reg-2", "Test channel", null, null);
        tools.register("obs-visible", "Visible observer", List.of(), null, true);

        // The read_only instance SHOULD appear in list_instances (unlike old ObserverRegistry)
        List<QhorusMcpTools.InstanceInfo> instances = tools.listInstances(null);
        assertTrue(instances.stream()
                .anyMatch(i -> "obs-visible".equals(i.instanceId()) && i.readOnly()),
                "read_only instance should appear in list_instances with readOnly=true");
    }

    @Test
    @TestTransaction
    void listInstancesShowsReadOnlyFlag() {
        // Register a regular instance AND a read_only instance
        tools.register("regular-agent", "Normal agent", List.of(), null, false);
        tools.register("monitor-obs", "Monitor", List.of(), null, true);

        List<QhorusMcpTools.InstanceInfo> instances = tools.listInstances(null);

        QhorusMcpTools.InstanceInfo regular = instances.stream()
                .filter(i -> "regular-agent".equals(i.instanceId()))
                .findFirst().orElseThrow();
        assertFalse(regular.readOnly(), "regular agent should not be read_only");

        QhorusMcpTools.InstanceInfo observer = instances.stream()
                .filter(i -> "monitor-obs".equals(i.instanceId()))
                .findFirst().orElseThrow();
        assertTrue(observer.readOnly(), "observer instance should be read_only");
    }

    // =========================================================================
    // Unit — read_only instances cannot send messages
    // =========================================================================

    @Test
    @TestTransaction
    void readOnlyInstanceCannotSendMessages() {
        tools.createChannel("obs-send-1", "Test", null, null);
        tools.register("readonly-obs", "Read-only observer", List.of(), null, true);

        ToolCallException ex = assertThrows(ToolCallException.class,
                () -> tools.sendMessage("obs-send-1", "readonly-obs", "status", "intrude", null, null, null, null),
                "read_only instance should be rejected from send_message");

        String msg = ex.getMessage().toLowerCase();
        assertTrue(msg.contains("read-only") || msg.contains("read_only") || msg.contains("not permitted"),
                "error should indicate read_only cannot write, was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("readonly-obs"),
                "error should name the instance, was: " + ex.getMessage());
    }

    @Test
    @TestTransaction
    void readOnlyInstanceCannotSendEventMessages() {
        tools.createChannel("obs-send-2", "Test", null, null);
        tools.register("readonly-obs-2", "Read-only observer", List.of(), null, true);

        // Even EVENT messages cannot be sent by read_only instances
        assertThrows(ToolCallException.class,
                () -> tools.sendMessage("obs-send-2", "readonly-obs-2", "event", "audit", null, null, null, null),
                "read_only instance should be rejected even for EVENT type messages");
    }

    // =========================================================================
    // Integration — reading event messages via check_messages(include_events=true)
    // =========================================================================

    @Test
    @TestTransaction
    void readOnlyInstanceCanReadEventMessagesViaIncludeEvents() {
        tools.createChannel("obs-read-1", "Monitored channel", null, null);
        tools.register("watcher-obs", "Watcher", List.of(), null, true);

        // Agents post messages — only EVENT ones visible with include_events=true
        tools.sendMessage("obs-read-1", "agent-a", "status", "status update", null, null, null, null);
        tools.sendMessage("obs-read-1", "agent-b", "event", "telemetry ping", null, null, null, null);
        tools.sendMessage("obs-read-1", "agent-a", "command", "some request", null, null, null, null);
        tools.sendMessage("obs-read-1", "system", "event", "audit log entry", null, null, null, null);

        // With include_events=true, all messages including EVENTs are returned
        QhorusMcpTools.CheckResult result = tools.checkMessages("obs-read-1", 0L, 20, null, null, true);

        // Should see all 4 messages (status + event + command + event) since include_events includes everything
        assertEquals(4, result.messages().size(),
                "include_events=true should return all messages including EVENTs");
    }

    @Test
    @TestTransaction
    void checkMessagesWithoutIncludeEventsExcludesEvents() {
        tools.createChannel("obs-read-2", "Test", null, null);

        tools.sendMessage("obs-read-2", "system", "event", "event-1", null, null, null, null);
        tools.sendMessage("obs-read-2", "system", "status", "status-1", null, null, null, null);
        tools.sendMessage("obs-read-2", "system", "event", "event-2", null, null, null, null);

        // Default behavior (include_events=null/false) excludes EVENTs
        QhorusMcpTools.CheckResult result = tools.checkMessages("obs-read-2", 0L, 20, null);
        assertEquals(1, result.messages().size(),
                "default check_messages should exclude EVENT messages");
        assertEquals("STATUS", result.messages().get(0).messageType());
    }

    @Test
    @TestTransaction
    void checkMessagesWithIncludeEventsPagination() {
        tools.createChannel("obs-read-3", "Paginated", null, null);

        tools.sendMessage("obs-read-3", "system", "event", "event-1", null, null, null, null);
        tools.sendMessage("obs-read-3", "system", "event", "event-2", null, null, null, null);
        tools.sendMessage("obs-read-3", "system", "event", "event-3", null, null, null, null);

        // Read first batch
        QhorusMcpTools.CheckResult first = tools.checkMessages("obs-read-3", 0L, 2, null, null, true);
        assertEquals(2, first.messages().size());

        // Read remainder using lastId cursor
        QhorusMcpTools.CheckResult second = tools.checkMessages("obs-read-3", first.lastId(), 10, null, null, true);
        assertEquals(1, second.messages().size());
    }

    // =========================================================================
    // Integration — re-register to change read_only flag
    // =========================================================================

    @Test
    @TestTransaction
    void reRegisterClearsReadOnlyFlag() {
        tools.createChannel("obs-dereg-2", "Test", null, null);
        tools.register("was-observer", "Was observer", List.of(), null, true);

        // Blocked as read_only
        assertThrows(ToolCallException.class,
                () -> tools.sendMessage("obs-dereg-2", "was-observer", "status", "blocked", null, null, null, null));

        // Re-register as not read_only
        tools.register("was-observer", "Now active", List.of(), null, false);

        // Now free to send (no longer read_only)
        assertDoesNotThrow(
                () -> tools.sendMessage("obs-dereg-2", "was-observer", "status", "now allowed", null, null, null,
                        null),
                "after re-registering with read_only=false, the instance should be allowed to send messages");
    }

    // =========================================================================
    // E2E — dashboard observer watching active agent work
    // =========================================================================

    @Test
    @TestTransaction
    void e2eDashboardObserverWatchesAgentWorkflow() {
        tools.createChannel("obs-e2e-1", "Agent work channel", "APPEND", null);
        tools.register("agent-alpha", "Alpha agent", List.of("capability:worker"), null, false);
        tools.register("agent-beta", "Beta agent", List.of("capability:worker"), null, false);

        // Dashboard registers as read_only — creates a visible Instance record
        tools.register("dashboard", "Dashboard", List.of(), null, true);

        // Agents work normally
        tools.sendMessage("obs-e2e-1", "agent-alpha", "command", "job A", null, null, null, null);
        tools.sendMessage("obs-e2e-1", "agent-beta", "response", "job A done", null, null, null, null);
        tools.sendMessage("obs-e2e-1", "system", "event", "audit: alpha->beta handoff", null, null, null, null);
        tools.sendMessage("obs-e2e-1", "agent-alpha", "status", "starting job B", null, null, null, null);
        tools.sendMessage("obs-e2e-1", "system", "event", "audit: job B started", null, null, null, null);

        // Dashboard uses include_events=true to see all messages including events
        QhorusMcpTools.CheckResult dashResult = tools.checkMessages("obs-e2e-1", 0L, 20, null, null, true);
        assertEquals(5, dashResult.messages().size(),
                "dashboard with include_events=true should see all 5 messages");

        // Regular agents see 3 non-event messages (EVENT messages excluded by default)
        QhorusMcpTools.CheckResult agentResult = tools.checkMessages("obs-e2e-1", 0L, 20, null);
        assertEquals(3, agentResult.messages().size(),
                "agents see only non-event messages via check_messages (EVENT excluded by default)");

        // Dashboard IS visible in list_instances (unlike old ObserverRegistry which hid observers)
        List<QhorusMcpTools.InstanceInfo> instances = tools.listInstances(null);
        assertEquals(3, instances.size(), "alpha, beta, and dashboard should all be in the registry");
        assertTrue(instances.stream().anyMatch(i -> "dashboard".equals(i.instanceId()) && i.readOnly()));
    }

    // =========================================================================
    // E2E — read_only send blocked but regular senders not affected
    // =========================================================================

    @Test
    @TestTransaction
    void e2eReadOnlyBlockedDoesNotAffectRegularSenders() {
        tools.createChannel("obs-e2e-2", "Mixed channel", null, null);
        tools.register("worker", "Worker agent", List.of(), null, false);
        tools.register("watcher", "Watcher", List.of(), null, true);

        // Worker sends freely
        tools.sendMessage("obs-e2e-2", "worker", "command", "task", null, null, null, null);
        tools.sendMessage("obs-e2e-2", "worker", "status", "in progress", null, null, null, null);

        // Watcher cannot send
        assertThrows(ToolCallException.class,
                () -> tools.sendMessage("obs-e2e-2", "watcher", "status", "observer intrusion", null, null, null,
                        null));

        // Worker messages still there
        QhorusMcpTools.CheckResult result = tools.checkMessages("obs-e2e-2", 0L, 10, null);
        assertEquals(2, result.messages().size(),
                "rejected read_only send should not affect the channel or existing messages");
    }
}
