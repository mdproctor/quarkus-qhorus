package io.quarkiverse.qhorus.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.runtime.mcp.QhorusMcpTools;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Issue #49 — Read-only observer mode: subscribe to event messages without joining the instance registry.
 *
 * <p>
 * Three levels of testing:
 * <ul>
 * <li>Unit: registration contract (no Instance row, excluded from list_instances); send rejection</li>
 * <li>Integration: observer reads event messages; deregister lifecycle; channel subscription scope</li>
 * <li>E2E: full dashboard-observer scenario — agents work, observer watches events only</li>
 * </ul>
 *
 * <p>
 * Observer registrations are in-memory (ephemeral — reset on restart, documented).
 * Observer IDs must be distinct from instance IDs to prevent confusion.
 *
 * <p>
 * Refs #49, Epic #45.
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
    void registerObserverReturnsSuccessResult() {
        tools.createChannel("obs-reg-1", "Test channel", null, null);

        QhorusMcpTools.ObserverRegistration result = tools.registerObserver(
                "dashboard-obs", List.of("obs-reg-1"));

        assertNotNull(result, "registerObserver should return a result");
        assertEquals("dashboard-obs", result.observerId());
        assertTrue(result.channelNames().contains("obs-reg-1"));
    }

    @Test
    @TestTransaction
    void registerObserverDoesNotCreateInstanceRecord() {
        tools.createChannel("obs-reg-2", "Test channel", null, null);
        tools.registerObserver("obs-hidden", List.of("obs-reg-2"));

        // The observer should NOT appear in list_instances
        List<QhorusMcpTools.InstanceInfo> instances = tools.listInstances(null);
        boolean observerInList = instances.stream()
                .anyMatch(i -> "obs-hidden".equals(i.instanceId()));
        assertFalse(observerInList,
                "registerObserver must not create an Instance record — observer should not appear in list_instances");
    }

    @Test
    @TestTransaction
    void listInstancesExcludesRegisteredObserver() {
        // Register a regular instance AND an observer
        tools.register("regular-agent", "Normal agent", List.of(), null);
        tools.createChannel("obs-list-1", "Test", null, null);
        tools.registerObserver("monitor-obs", List.of("obs-list-1"));

        List<QhorusMcpTools.InstanceInfo> instances = tools.listInstances(null);

        assertTrue(instances.stream().anyMatch(i -> "regular-agent".equals(i.instanceId())),
                "regular agent should appear in list_instances");
        assertFalse(instances.stream().anyMatch(i -> "monitor-obs".equals(i.instanceId())),
                "observer should NOT appear in list_instances");
    }

    // =========================================================================
    // Unit — observers cannot send messages
    // =========================================================================

    @Test
    @TestTransaction
    void observerCannotSendMessages() {
        tools.createChannel("obs-send-1", "Test", null, null);
        tools.registerObserver("readonly-obs", List.of("obs-send-1"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> tools.sendMessage("obs-send-1", "readonly-obs", "status", "intrude", null, null, null, null),
                "registered observer should be rejected from send_message");

        String msg = ex.getMessage().toLowerCase();
        assertTrue(msg.contains("observer") || msg.contains("read-only") || msg.contains("not permitted"),
                "error should indicate observer cannot write, was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("readonly-obs"),
                "error should name the observer, was: " + ex.getMessage());
    }

    @Test
    @TestTransaction
    void observerCannotSendEventMessages() {
        tools.createChannel("obs-send-2", "Test", null, null);
        tools.registerObserver("readonly-obs-2", List.of("obs-send-2"));

        // Even EVENT messages cannot be sent by observers
        assertThrows(IllegalStateException.class,
                () -> tools.sendMessage("obs-send-2", "readonly-obs-2", "event", "audit", null, null, null, null),
                "observer should be rejected even for EVENT type messages");
    }

    // =========================================================================
    // Integration — reading event messages
    // =========================================================================

    @Test
    @TestTransaction
    void observerCanReadEventMessagesFromSubscribedChannel() {
        tools.createChannel("obs-read-1", "Monitored channel", null, null);
        tools.registerObserver("watcher-obs", List.of("obs-read-1"));

        // Agents post messages — only EVENT ones visible to observer
        tools.sendMessage("obs-read-1", "agent-a", "status", "status update", null, null, null, null);
        tools.sendMessage("obs-read-1", "agent-b", "event", "telemetry ping", null, null, null, null);
        tools.sendMessage("obs-read-1", "agent-a", "request", "some request", null, null, null, null);
        tools.sendMessage("obs-read-1", "system", "event", "audit log entry", null, null, null, null);

        // Observer reads — should only receive EVENT messages
        QhorusMcpTools.CheckResult result = tools.readObserverEvents("watcher-obs", "obs-read-1", 0L, 20);

        assertEquals(2, result.messages().size(),
                "observer should see exactly the 2 EVENT messages");
        assertTrue(result.messages().stream().allMatch(m -> "event".equalsIgnoreCase(m.messageType())),
                "all messages returned to observer must be of type EVENT");
    }

    @Test
    @TestTransaction
    void observerCanReadEventsWithCursorPagination() {
        tools.createChannel("obs-read-2", "Paginated", null, null);
        tools.registerObserver("pager-obs", List.of("obs-read-2"));

        tools.sendMessage("obs-read-2", "system", "event", "event-1", null, null, null, null);
        tools.sendMessage("obs-read-2", "system", "event", "event-2", null, null, null, null);
        tools.sendMessage("obs-read-2", "system", "event", "event-3", null, null, null, null);

        // Read first batch
        QhorusMcpTools.CheckResult first = tools.readObserverEvents("pager-obs", "obs-read-2", 0L, 2);
        assertEquals(2, first.messages().size());

        // Read remainder using lastId cursor
        QhorusMcpTools.CheckResult second = tools.readObserverEvents(
                "pager-obs", "obs-read-2", first.lastId(), 10);
        assertEquals(1, second.messages().size());
    }

    @Test
    @TestTransaction
    void unregisteredObserverCannotReadEvents() {
        tools.createChannel("obs-read-3", "Test", null, null);
        // "ghost-obs" is never registered

        assertThrows(IllegalArgumentException.class,
                () -> tools.readObserverEvents("ghost-obs", "obs-read-3", 0L, 10),
                "unregistered observer ID should be rejected");
    }

    @Test
    @TestTransaction
    void observerCannotReadEventsFromUnsubscribedChannel() {
        tools.createChannel("obs-sub-1", "Subscribed", null, null);
        tools.createChannel("obs-sub-2", "Not subscribed", null, null);
        tools.registerObserver("limited-obs", List.of("obs-sub-1")); // only obs-sub-1

        // Can read from subscribed channel
        assertDoesNotThrow(
                () -> tools.readObserverEvents("limited-obs", "obs-sub-1", 0L, 10));

        // Cannot read from unsubscribed channel
        assertThrows(IllegalArgumentException.class,
                () -> tools.readObserverEvents("limited-obs", "obs-sub-2", 0L, 10),
                "observer should be rejected from channels they are not subscribed to");
    }

    // =========================================================================
    // Integration — deregister lifecycle
    // =========================================================================

    @Test
    @TestTransaction
    void deregisterObserverRemovesRegistration() {
        tools.createChannel("obs-dereg-1", "Test", null, null);
        tools.registerObserver("temp-obs", List.of("obs-dereg-1"));

        // Observer is registered — can read events
        assertDoesNotThrow(() -> tools.readObserverEvents("temp-obs", "obs-dereg-1", 0L, 10));

        // Deregister
        QhorusMcpTools.DeregisterObserverResult result = tools.deregisterObserver("temp-obs");
        assertTrue(result.deregistered(), "deregisterObserver should confirm removal");
        assertEquals("temp-obs", result.observerId());

        // No longer recognized as observer
        assertThrows(IllegalArgumentException.class,
                () -> tools.readObserverEvents("temp-obs", "obs-dereg-1", 0L, 10),
                "deregistered observer should be rejected");
    }

    @Test
    @TestTransaction
    void deregisterObserverAllowsSenderToWriteAgain() {
        tools.createChannel("obs-dereg-2", "Test", null, null);
        tools.registerObserver("was-observer", List.of("obs-dereg-2"));

        // Blocked as observer
        assertThrows(IllegalStateException.class,
                () -> tools.sendMessage("obs-dereg-2", "was-observer", "status", "blocked", null, null, null, null));

        // Deregister
        tools.deregisterObserver("was-observer");

        // Now free to send (no longer blocked as observer)
        assertDoesNotThrow(
                () -> tools.sendMessage("obs-dereg-2", "was-observer", "status", "now allowed", null, null, null,
                        null),
                "after deregistering, the ID should be allowed to send messages normally");
    }

    @Test
    @TestTransaction
    void deregisterUnknownObserverReturnsNotDeregistered() {
        QhorusMcpTools.DeregisterObserverResult result = tools.deregisterObserver("nonexistent-obs");
        assertFalse(result.deregistered(), "deregistering unknown observer should return deregistered=false");
    }

    // =========================================================================
    // Integration — multi-channel subscription
    // =========================================================================

    @Test
    @TestTransaction
    void observerCanSubscribeToMultipleChannels() {
        tools.createChannel("obs-multi-1", "Channel A", null, null);
        tools.createChannel("obs-multi-2", "Channel B", null, null);
        tools.registerObserver("multi-obs", List.of("obs-multi-1", "obs-multi-2"));

        tools.sendMessage("obs-multi-1", "system", "event", "event-on-A", null, null, null, null);
        tools.sendMessage("obs-multi-2", "system", "event", "event-on-B", null, null, null, null);

        // Can read events from both channels
        QhorusMcpTools.CheckResult eventsA = tools.readObserverEvents("multi-obs", "obs-multi-1", 0L, 10);
        QhorusMcpTools.CheckResult eventsB = tools.readObserverEvents("multi-obs", "obs-multi-2", 0L, 10);

        assertEquals(1, eventsA.messages().size());
        assertEquals(1, eventsB.messages().size());
    }

    // =========================================================================
    // E2E — dashboard observer watching active agent work
    // =========================================================================

    @Test
    @TestTransaction
    void e2eDashboardObserverWatchesAgentWorkflow() {
        tools.createChannel("obs-e2e-1", "Agent work channel", "APPEND", null);
        tools.register("agent-alpha", "Alpha agent", List.of("capability:worker"), null);
        tools.register("agent-beta", "Beta agent", List.of("capability:worker"), null);

        // Dashboard observer registers — does not create an Instance record
        tools.registerObserver("dashboard", List.of("obs-e2e-1"));

        // Agents work normally
        tools.sendMessage("obs-e2e-1", "agent-alpha", "request", "job A", null, null, null, null);
        tools.sendMessage("obs-e2e-1", "agent-beta", "response", "job A done", null, null, null, null);
        tools.sendMessage("obs-e2e-1", "system", "event", "audit: alpha→beta handoff", null, null, null, null);
        tools.sendMessage("obs-e2e-1", "agent-alpha", "status", "starting job B", null, null, null, null);
        tools.sendMessage("obs-e2e-1", "system", "event", "audit: job B started", null, null, null, null);

        // Dashboard sees only 2 audit events — not the agent messages
        QhorusMcpTools.CheckResult dashResult = tools.readObserverEvents("dashboard", "obs-e2e-1", 0L, 20);
        assertEquals(2, dashResult.messages().size(),
                "dashboard should see only EVENT messages");
        assertTrue(dashResult.messages().stream().allMatch(m -> "event".equalsIgnoreCase(m.messageType())));

        // Agents see 3 non-event messages (EVENT messages are excluded from agent delivery paths by design)
        QhorusMcpTools.CheckResult agentResult = tools.checkMessages("obs-e2e-1", 0L, 20, null);
        assertEquals(3, agentResult.messages().size(),
                "agents see only non-event messages via check_messages (EVENT excluded by design)");

        // Dashboard is invisible — not in list_instances
        List<QhorusMcpTools.InstanceInfo> instances = tools.listInstances(null);
        assertEquals(2, instances.size(), "only alpha and beta should be in the registry");
        assertTrue(instances.stream().anyMatch(i -> "agent-alpha".equals(i.instanceId())));
        assertTrue(instances.stream().anyMatch(i -> "agent-beta".equals(i.instanceId())));
        assertFalse(instances.stream().anyMatch(i -> "dashboard".equals(i.instanceId())));
    }

    // =========================================================================
    // E2E — observer send blocked but regular senders not affected
    // =========================================================================

    @Test
    @TestTransaction
    void e2eObserverBlockedDoesNotAffectRegularSenders() {
        tools.createChannel("obs-e2e-2", "Mixed channel", null, null);
        tools.register("worker", "Worker agent", List.of(), null);
        tools.registerObserver("watcher", List.of("obs-e2e-2"));

        // Worker sends freely
        tools.sendMessage("obs-e2e-2", "worker", "request", "task", null, null, null, null);
        tools.sendMessage("obs-e2e-2", "worker", "status", "in progress", null, null, null, null);

        // Watcher cannot send
        assertThrows(IllegalStateException.class,
                () -> tools.sendMessage("obs-e2e-2", "watcher", "status", "observer intrusion", null, null, null,
                        null));

        // Worker messages still there
        QhorusMcpTools.CheckResult result = tools.checkMessages("obs-e2e-2", 0L, 10, null);
        assertEquals(2, result.messages().size(),
                "rejected observer send should not affect the channel or existing messages");
    }
}
