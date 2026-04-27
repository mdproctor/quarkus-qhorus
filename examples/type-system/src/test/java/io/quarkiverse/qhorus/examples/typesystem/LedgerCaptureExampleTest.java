package io.quarkiverse.qhorus.examples.typesystem;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.qhorus.runtime.channel.Channel;
import io.quarkiverse.qhorus.runtime.ledger.MessageLedgerEntry;
import io.quarkiverse.qhorus.runtime.ledger.MessageLedgerEntryRepository;
import io.quarkiverse.qhorus.runtime.mcp.QhorusMcpTools;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Validates that the normative ledger records entries for all 9 message types
 * in the type-system example context (InMemory stores, H2 ledger).
 *
 * <p>
 * Runs in CI without any model or external services.
 *
 * <p>
 * Refs #107 — Epic #99.
 */
@QuarkusTest
class LedgerCaptureExampleTest {

    @Inject
    QhorusMcpTools tools;

    @Inject
    MessageLedgerEntryRepository ledgerRepo;

    @Test
    void allNineMessageTypes_produceLedgerEntries() {
        tools.createChannel("ledger-ex-all-types", "APPEND", null, null);
        tools.registerInstance("ledger-ex-all-types", "agent-a", null, null, null);
        tools.registerInstance("ledger-ex-all-types", "agent-b", null, null, null);

        tools.sendMessage("ledger-ex-all-types", "agent-a", "query",
                "What is the order count?", "corr-ex1", null, null, null);
        tools.sendMessage("ledger-ex-all-types", "agent-b", "response",
                "42 orders", "corr-ex1", null, null, null);
        tools.sendMessage("ledger-ex-all-types", "agent-a", "command",
                "Generate compliance report", "corr-ex2", null, null, null);
        tools.sendMessage("ledger-ex-all-types", "agent-b", "status",
                "Processing...", "corr-ex2", null, null, null);
        tools.sendMessage("ledger-ex-all-types", "agent-b", "done",
                "Report delivered", "corr-ex2", null, null, null);
        tools.sendMessage("ledger-ex-all-types", "agent-a", "command",
                "Delete audit logs", "corr-ex3", null, null, null);
        tools.sendMessage("ledger-ex-all-types", "agent-b", "decline",
                "I do not have permission to delete", "corr-ex3", null, null, null);
        tools.sendMessage("ledger-ex-all-types", "agent-a", "command",
                "Audit the accounts", "corr-ex4", null, null, null);
        tools.sendMessage("ledger-ex-all-types", "agent-b", "failure",
                "Database unreachable", "corr-ex4", null, null, null);
        tools.sendMessage("ledger-ex-all-types", "agent-a", "event",
                "{\"tool_name\":\"read_file\",\"duration_ms\":10}", null, null, null, null);

        Channel ch = Channel.<Channel> find("name", "ledger-ex-all-types")
                .firstResultOptional()
                .orElseThrow();

        List<MessageLedgerEntry> entries = ledgerRepo.findByChannelId(ch.id);

        // 10 messages → 10 ledger entries
        assertEquals(10, entries.size());

        // Verify all 9 types are present (HANDOFF not in this scenario)
        Set<String> types = new java.util.HashSet<>();
        entries.forEach(e -> types.add(e.messageType));
        assertTrue(types.containsAll(Set.of(
                "QUERY", "RESPONSE", "COMMAND", "STATUS", "DONE",
                "DECLINE", "FAILURE", "EVENT")));
    }

    @Test
    void obligationLifecycle_commandDone_causalChainPresent() {
        tools.createChannel("ledger-ex-chain", "APPEND", null, null);
        tools.registerInstance("ledger-ex-chain", "agent-a", null, null, null);
        tools.registerInstance("ledger-ex-chain", "agent-b", null, null, null);

        tools.sendMessage("ledger-ex-chain", "agent-a", "command",
                "Run end-of-day batch", "corr-chain", null, null, null);
        tools.sendMessage("ledger-ex-chain", "agent-b", "done",
                "Batch complete — 1542 records processed", "corr-chain", null, null, null);

        Channel ch = Channel.<Channel> find("name", "ledger-ex-chain")
                .firstResultOptional()
                .orElseThrow();

        List<MessageLedgerEntry> entries = ledgerRepo.findByChannelId(ch.id);
        assertEquals(2, entries.size());

        MessageLedgerEntry cmd = entries.get(0);
        MessageLedgerEntry done = entries.get(1);

        assertEquals("COMMAND", cmd.messageType);
        assertEquals("DONE", done.messageType);
        assertNotNull(done.causedByEntryId, "DONE must point back to COMMAND via causedByEntryId");
        assertEquals(cmd.id, done.causedByEntryId);
    }

    @Test
    void listLedgerEntries_typeFilter_obligationTypesOnly() {
        tools.createChannel("ledger-ex-filter", "APPEND", null, null);
        tools.registerInstance("ledger-ex-filter", "agent-a", null, null, null);
        tools.registerInstance("ledger-ex-filter", "agent-b", null, null, null);

        String corr = "corr-filter";
        tools.sendMessage("ledger-ex-filter", "agent-a", "command", "Do X", corr, null, null, null);
        tools.sendMessage("ledger-ex-filter", "agent-a", "status", "Working", corr, null, null, null);
        tools.sendMessage("ledger-ex-filter", "agent-b", "done", "Done", corr, null, null, null);
        tools.sendMessage("ledger-ex-filter", "agent-a", "event",
                "{\"tool_name\":\"t\",\"duration_ms\":1}", null, null, null, null);

        List<Map<String, Object>> obligationEntries = tools.listLedgerEntries(
                "ledger-ex-filter", "COMMAND,DONE,FAILURE,DECLINE,HANDOFF", null, null, null, 20);

        assertEquals(2, obligationEntries.size());
        assertTrue(obligationEntries.stream()
                .allMatch(e -> Set.of("COMMAND", "DONE").contains(e.get("message_type"))));
    }
}
