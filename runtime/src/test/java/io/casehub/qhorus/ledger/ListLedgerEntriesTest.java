package io.casehub.qhorus.ledger;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.mcp.server.ToolCallException;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * End-to-end tests for the {@code list_ledger_entries} MCP tool.
 *
 * <p>
 * Verifies the full stack: sendMessage → LedgerWriteService.record() →
 * MessageLedgerEntryRepository → list_ledger_entries response shape.
 *
 * <p>
 * Refs #104 — Epic #99.
 */
@QuarkusTest
@TestTransaction
class ListLedgerEntriesTest {

    @Inject
    QhorusMcpTools tools;

    // =========================================================================
    // Happy path — basic retrieval
    // =========================================================================

    @Test
    void listLedgerEntries_allTypes_returnsAll() {
        setup("lle-basic-1", "agent-a", "agent-b");
        tools.sendMessage("lle-basic-1", "agent-a", "command", "Run audit", "corr-1", null, null, null, null);
        tools.sendMessage("lle-basic-1", "agent-b", "done", "Audit done", "corr-1", null, null, null, null);
        tools.sendMessage("lle-basic-1", "agent-a", "event", "{\"tool_name\":\"read\",\"duration_ms\":10}", null, null, null, null, null);

        List<Map<String, Object>> entries = tools.listLedgerEntries("lle-basic-1", null, null, null, null, null, null, 20);

        assertEquals(3, entries.size());
    }

    @Test
    void listLedgerEntries_returnsRequiredFields() {
        setup("lle-fields-1", "agent-a");
        tools.sendMessage("lle-fields-1", "agent-a", "command", "Do X", "corr-f1", null, null, null, null);

        List<Map<String, Object>> entries = tools.listLedgerEntries("lle-fields-1", null, null, null, null, null, null, 20);

        assertEquals(1, entries.size());
        Map<String, Object> e = entries.get(0);
        assertEquals("COMMAND", e.get("message_type"));
        assertEquals("agent-a", e.get("actor_id"));
        assertEquals("corr-f1", e.get("correlation_id"));
        assertEquals("Do X", e.get("content"));
        assertNotNull(e.get("sequence_number"));
        assertNotNull(e.get("occurred_at"));
        assertNotNull(e.get("message_id"));
    }

    @Test
    void listLedgerEntries_eventEntry_includesTelemetryFields() {
        setup("lle-telemetry-1", "agent-a");
        tools.sendMessage("lle-telemetry-1", "agent-a", "event", "{\"tool_name\":\"analyze\",\"duration_ms\":42,\"token_count\":500}", null, null, null, null, null);

        List<Map<String, Object>> entries = tools.listLedgerEntries("lle-telemetry-1", null, null, null, null, null, null, 20);

        Map<String, Object> e = entries.get(0);
        assertEquals("EVENT", e.get("message_type"));
        assertEquals("analyze", e.get("tool_name"));
        assertEquals(42L, e.get("duration_ms"));
        assertEquals(500L, e.get("token_count"));
        assertNull(e.get("content"));
    }

    @Test
    void listLedgerEntries_nonEventEntry_doesNotIncludeTelemetryKeys() {
        setup("lle-no-telemetry-1", "agent-a");
        tools.sendMessage("lle-no-telemetry-1", "agent-a", "command", "Do it", null, null, null, null, null);

        List<Map<String, Object>> entries = tools.listLedgerEntries("lle-no-telemetry-1", null, null, null, null, null, null, 20);

        Map<String, Object> e = entries.get(0);
        assertFalse(e.containsKey("tool_name"), "Non-EVENT entries must not include tool_name key");
        assertFalse(e.containsKey("duration_ms"), "Non-EVENT entries must not include duration_ms key");
    }

    @Test
    void listLedgerEntries_returnedInChronologicalOrder() {
        setup("lle-order-1", "agent-a", "agent-b");
        tools.sendMessage("lle-order-1", "agent-a", "command", "first", "corr-ord", null, null, null, null);
        tools.sendMessage("lle-order-1", "agent-a", "status", "second", "corr-ord", null, null, null, null);
        tools.sendMessage("lle-order-1", "agent-b", "done", "third", "corr-ord", null, null, null, null);

        List<Map<String, Object>> entries = tools.listLedgerEntries("lle-order-1", null, null, null, null, null, null, 20);

        assertEquals(1L, entries.get(0).get("sequence_number"));
        assertEquals(2L, entries.get(1).get("sequence_number"));
        assertEquals(3L, entries.get(2).get("sequence_number"));
    }

    @Test
    void listLedgerEntries_unknownChannel_throws() {
        assertThrows(ToolCallException.class,
                () -> tools.listLedgerEntries("no-such-channel", null, null, null, null, null, null, 20));
    }

    // =========================================================================
    // type_filter
    // =========================================================================

    @Test
    void listLedgerEntries_typeFilter_obligationLifecycle() {
        setup("lle-type-1", "agent-a", "agent-b");
        tools.sendMessage("lle-type-1", "agent-a", "command", "Go", "corr-tf1", null, null, null, null);
        tools.sendMessage("lle-type-1", "agent-a", "status", "Working", "corr-tf1", null, null, null, null);
        tools.sendMessage("lle-type-1", "agent-b", "done", "Done", "corr-tf1", null, null, null, null);
        tools.sendMessage("lle-type-1", "agent-a", "event", "{\"tool_name\":\"t\",\"duration_ms\":1}", null, null, null, null, null);

        List<Map<String, Object>> entries = tools.listLedgerEntries("lle-type-1", "COMMAND,DONE", null, null, null, null, null, 20);

        assertEquals(2, entries.size());
        assertTrue(entries.stream()
                .allMatch(e -> "COMMAND".equals(e.get("message_type")) || "DONE".equals(e.get("message_type"))));
    }

    @Test
    void listLedgerEntries_typeFilter_eventOnly() {
        setup("lle-type-event-1", "agent-a");
        tools.sendMessage("lle-type-event-1", "agent-a", "command", "Go", null, null, null, null, null);
        tools.sendMessage("lle-type-event-1", "agent-a", "event", "{\"tool_name\":\"t\",\"duration_ms\":1}", null, null, null, null, null);

        List<Map<String, Object>> entries = tools.listLedgerEntries("lle-type-event-1", "EVENT", null, null, null, null, null, 20);

        assertEquals(1, entries.size());
        assertEquals("EVENT", entries.get(0).get("message_type"));
    }

    @Test
    void listLedgerEntries_typeFilter_noMatch_returnsEmpty() {
        setup("lle-type-empty-1", "agent-a");
        tools.sendMessage("lle-type-empty-1", "agent-a", "command", "Go", null, null, null, null, null);

        List<Map<String, Object>> entries = tools.listLedgerEntries("lle-type-empty-1", "DECLINE", null, null, null, null, null, 20);

        assertTrue(entries.isEmpty());
    }

    // =========================================================================
    // agent_id filter
    // =========================================================================

    @Test
    void listLedgerEntries_agentFilter_returnsOnlyThatAgent() {
        setup("lle-agent-1", "agent-a", "agent-b");
        tools.sendMessage("lle-agent-1", "agent-a", "command", "Go", "corr-ag1", null, null, null, null);
        tools.sendMessage("lle-agent-1", "agent-b", "done", "Done", "corr-ag1", null, null, null, null);

        List<Map<String, Object>> entries = tools.listLedgerEntries("lle-agent-1", null, "agent-a", null, null, null, null, 20);

        assertEquals(1, entries.size());
        assertEquals("agent-a", entries.get(0).get("actor_id"));
    }

    // =========================================================================
    // Cursor pagination
    // =========================================================================

    @Test
    void listLedgerEntries_limit_capsResults() {
        setup("lle-limit-1", "agent-a");
        for (int i = 0; i < 5; i++) {
            tools.sendMessage("lle-limit-1", "agent-a", "event", "{\"tool_name\":\"t\",\"duration_ms\":1}", null, null, null, null, null);
        }

        List<Map<String, Object>> page = tools.listLedgerEntries("lle-limit-1", null, null, null, null, null, null, 3);

        assertEquals(3, page.size());
    }

    @Test
    void listLedgerEntries_afterId_returnsNextPage() {
        setup("lle-cursor-1", "agent-a", "agent-b");
        tools.sendMessage("lle-cursor-1", "agent-a", "command", "Go", "corr-cur", null, null, null, null);
        tools.sendMessage("lle-cursor-1", "agent-a", "status", "Working", "corr-cur", null, null, null, null);
        tools.sendMessage("lle-cursor-1", "agent-b", "done", "Done", "corr-cur", null, null, null, null);

        List<Map<String, Object>> page1 = tools.listLedgerEntries("lle-cursor-1", null, null, null, null, null, null, 2);
        assertEquals(2, page1.size());

        Long cursor = (Long) (Object) page1.get(1).get("sequence_number");
        List<Map<String, Object>> page2 = tools.listLedgerEntries("lle-cursor-1", null, null, null, cursor, null, null, 2);

        assertEquals(1, page2.size());
        assertEquals("DONE", page2.get(0).get("message_type"));
    }

    // =========================================================================
    // Causal chain visible in MCP response
    // =========================================================================

    @Test
    void listLedgerEntries_causalChain_causedByEntryIdPopulated() {
        setup("lle-causal-1", "agent-a", "agent-b");
        tools.sendMessage("lle-causal-1", "agent-a", "command", "Run", "corr-c1", null, null, null, null);
        tools.sendMessage("lle-causal-1", "agent-b", "done", "Done", "corr-c1", null, null, null, null);

        List<Map<String, Object>> entries = tools.listLedgerEntries("lle-causal-1", null, null, null, null, null, null, 20);
        assertEquals(2, entries.size());

        Map<String, Object> cmd = entries.get(0);
        Map<String, Object> done = entries.get(1);

        assertNull(cmd.get("caused_by_entry_id"), "COMMAND should have no causal predecessor");
        assertNotNull(done.get("caused_by_entry_id"), "DONE should point to its COMMAND");
    }

    // =========================================================================
    // Invalid since parameter
    // =========================================================================

    @Test
    void listLedgerEntries_invalidSinceTimestamp_throws() {
        setup("lle-since-bad-1", "agent-a");

        assertThrows(ToolCallException.class,
                () -> tools.listLedgerEntries("lle-since-bad-1", null, null, "not-a-date", null, null, null, 20));
    }

    // =========================================================================
    // Fixture
    // =========================================================================

    private void setup(final String channel, final String... agents) {
        tools.createChannel(channel, "APPEND", null, null, null, null, null, null, null);
        for (final String agent : agents) {
            tools.registerInstance(channel, agent, null, null, null);
        }
    }
}
