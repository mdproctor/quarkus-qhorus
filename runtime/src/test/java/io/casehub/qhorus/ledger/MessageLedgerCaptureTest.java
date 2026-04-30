package io.casehub.qhorus.ledger;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.ledger.MessageLedgerEntry;
import io.casehub.qhorus.runtime.ledger.MessageLedgerEntryRepository;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for the normative ledger — verifies that every message type
 * sent via {@code sendMessage} produces a {@link MessageLedgerEntry} with correct
 * fields, sequence numbers, causal chain links, and filter behaviour.
 *
 * <p>
 * RED phase: tests for non-EVENT types fail until {@code sendMessage} is wired
 * to call {@code LedgerWriteService.record()} for all types (Task 6).
 *
 * <p>
 * Refs #103 — Epic #99.
 */
@QuarkusTest
@TestTransaction
class MessageLedgerCaptureTest {

    @Inject
    QhorusMcpTools tools;

    @Inject
    MessageLedgerEntryRepository ledgerRepo;

    // =========================================================================
    // Happy path — one test per message type
    // =========================================================================

    @Test
    void sendQuery_createsLedgerEntry() {
        setup("mlc-query-1", "agent-a");
        tools.sendMessage("mlc-query-1", "agent-a", "query", "How many orders today?",
                "corr-q1", null, null, null);

        List<MessageLedgerEntry> entries = ledgerRepo.findByChannelId(channelId("mlc-query-1"));
        assertEquals(1, entries.size());
        MessageLedgerEntry e = entries.get(0);
        assertEquals("QUERY", e.messageType);
        assertEquals("agent-a", e.actorId);
        assertEquals("corr-q1", e.correlationId);
        assertEquals("How many orders today?", e.content);
        assertNull(e.toolName);
    }

    @Test
    void sendCommand_createsLedgerEntry() {
        setup("mlc-cmd-1", "agent-a");
        tools.sendMessage("mlc-cmd-1", "agent-a", "command", "Generate the monthly report",
                "corr-c1", null, null, null);

        List<MessageLedgerEntry> entries = ledgerRepo.findByChannelId(channelId("mlc-cmd-1"));
        assertEquals(1, entries.size());
        assertEquals("COMMAND", entries.get(0).messageType);
        assertEquals("Generate the monthly report", entries.get(0).content);
    }

    @Test
    void sendResponse_createsLedgerEntry() {
        setup("mlc-resp-1", "agent-a", "agent-b");
        tools.sendMessage("mlc-resp-1", "agent-a", "query", "Status?", "corr-r1", null, null, null);
        tools.sendMessage("mlc-resp-1", "agent-b", "response", "All good", "corr-r1", null, null, null);

        List<MessageLedgerEntry> entries = ledgerRepo.findByChannelId(channelId("mlc-resp-1"));
        assertEquals(2, entries.size());
        assertEquals("RESPONSE", entries.get(1).messageType);
        assertEquals("All good", entries.get(1).content);
    }

    @Test
    void sendStatus_createsLedgerEntry() {
        setup("mlc-status-1", "agent-a");
        tools.sendMessage("mlc-status-1", "agent-a", "command", "Run migration",
                "corr-s1", null, null, null);
        tools.sendMessage("mlc-status-1", "agent-a", "status", "50% complete",
                "corr-s1", null, null, null);

        List<MessageLedgerEntry> entries = ledgerRepo.findByChannelId(channelId("mlc-status-1"));
        assertEquals(2, entries.size());
        assertEquals("STATUS", entries.get(1).messageType);
    }

    @Test
    void sendDecline_createsLedgerEntry() {
        setup("mlc-dec-1", "agent-a", "agent-b");
        tools.sendMessage("mlc-dec-1", "agent-a", "command", "Delete all records",
                "corr-d1", null, null, null);
        tools.sendMessage("mlc-dec-1", "agent-b", "decline", "I do not have write permissions",
                "corr-d1", null, null, null);

        List<MessageLedgerEntry> entries = ledgerRepo.findByChannelId(channelId("mlc-dec-1"));
        assertEquals(2, entries.size());
        assertEquals("DECLINE", entries.get(1).messageType);
        assertEquals("I do not have write permissions", entries.get(1).content);
    }

    @Test
    void sendHandoff_createsLedgerEntry() {
        setup("mlc-hand-1", "agent-a", "agent-b", "agent-c");
        tools.sendMessage("mlc-hand-1", "agent-a", "command", "Audit the accounts",
                "corr-h1", null, null, null);
        tools.sendMessage("mlc-hand-1", "agent-b", "handoff", null,
                "corr-h1", null, null, "instance:agent-c");

        List<MessageLedgerEntry> entries = ledgerRepo.findByChannelId(channelId("mlc-hand-1"));
        assertEquals(2, entries.size());
        MessageLedgerEntry handoff = entries.get(1);
        assertEquals("HANDOFF", handoff.messageType);
        assertEquals("instance:agent-c", handoff.target);
    }

    @Test
    void sendDone_createsLedgerEntry() {
        setup("mlc-done-1", "agent-a", "agent-b");
        tools.sendMessage("mlc-done-1", "agent-a", "command", "Process refunds",
                "corr-done1", null, null, null);
        tools.sendMessage("mlc-done-1", "agent-b", "done", "All 42 refunds processed",
                "corr-done1", null, null, null);

        List<MessageLedgerEntry> entries = ledgerRepo.findByChannelId(channelId("mlc-done-1"));
        assertEquals(2, entries.size());
        assertEquals("DONE", entries.get(1).messageType);
        assertEquals("All 42 refunds processed", entries.get(1).content);
    }

    @Test
    void sendFailure_createsLedgerEntry() {
        setup("mlc-fail-1", "agent-a", "agent-b");
        tools.sendMessage("mlc-fail-1", "agent-a", "command", "Run batch job",
                "corr-fail1", null, null, null);
        tools.sendMessage("mlc-fail-1", "agent-b", "failure", "Database connection lost",
                "corr-fail1", null, null, null);

        List<MessageLedgerEntry> entries = ledgerRepo.findByChannelId(channelId("mlc-fail-1"));
        assertEquals(2, entries.size());
        assertEquals("FAILURE", entries.get(1).messageType);
        assertEquals("Database connection lost", entries.get(1).content);
    }

    @Test
    void sendEvent_withValidPayload_createsTelemetryEntry() {
        setup("mlc-event-1", "agent-a");
        tools.sendMessage("mlc-event-1", "agent-a", "event",
                "{\"tool_name\":\"read_file\",\"duration_ms\":42,\"token_count\":1200}",
                null, null, null, null);

        List<MessageLedgerEntry> entries = ledgerRepo.findByChannelId(channelId("mlc-event-1"));
        assertEquals(1, entries.size());
        MessageLedgerEntry e = entries.get(0);
        assertEquals("EVENT", e.messageType);
        assertEquals("read_file", e.toolName);
        assertEquals(42L, e.durationMs);
        assertEquals(1200L, e.tokenCount);
        assertNull(e.content);
    }

    @Test
    void sendEvent_malformedJson_entryStillCreated() {
        setup("mlc-event-malformed-1", "agent-a");
        tools.sendMessage("mlc-event-malformed-1", "agent-a", "event",
                "not-json", null, null, null, null);

        List<MessageLedgerEntry> entries = ledgerRepo.findByChannelId(channelId("mlc-event-malformed-1"));
        assertEquals(1, entries.size());
        assertNull(entries.get(0).toolName);
        assertNull(entries.get(0).durationMs);
    }

    @Test
    void sendEvent_missingTelemetryFields_entryStillCreated() {
        setup("mlc-event-partial-1", "agent-a");
        tools.sendMessage("mlc-event-partial-1", "agent-a", "event",
                "{\"duration_ms\":10}", null, null, null, null);

        List<MessageLedgerEntry> entries = ledgerRepo.findByChannelId(channelId("mlc-event-partial-1"));
        assertEquals(1, entries.size());
        assertNull(entries.get(0).toolName);
        assertEquals(10L, entries.get(0).durationMs);
    }

    // =========================================================================
    // Sequence numbering
    // =========================================================================

    @Test
    void multipleMessages_sequenceNumbersIncrement() {
        setup("mlc-seq-1", "agent-a", "agent-b");
        tools.sendMessage("mlc-seq-1", "agent-a", "command", "Go", "corr-seq1", null, null, null);
        tools.sendMessage("mlc-seq-1", "agent-a", "status", "Working", "corr-seq1", null, null, null);
        tools.sendMessage("mlc-seq-1", "agent-b", "done", "Done", "corr-seq1", null, null, null);

        List<MessageLedgerEntry> entries = ledgerRepo.findByChannelId(channelId("mlc-seq-1"));
        assertEquals(3, entries.size());
        assertEquals(1, entries.get(0).sequenceNumber);
        assertEquals(2, entries.get(1).sequenceNumber);
        assertEquals(3, entries.get(2).sequenceNumber);
    }

    @Test
    void sequenceNumbers_independentAcrossChannels() {
        setup("mlc-seq-2a", "agent-a");
        setup("mlc-seq-2b", "agent-b");
        tools.sendMessage("mlc-seq-2a", "agent-a", "command", "X", null, null, null, null);
        tools.sendMessage("mlc-seq-2b", "agent-b", "command", "Y", null, null, null, null);

        List<MessageLedgerEntry> a = ledgerRepo.findByChannelId(channelId("mlc-seq-2a"));
        List<MessageLedgerEntry> b = ledgerRepo.findByChannelId(channelId("mlc-seq-2b"));
        assertEquals(1, a.get(0).sequenceNumber);
        assertEquals(1, b.get(0).sequenceNumber);
    }

    // =========================================================================
    // Causal chain — causedByEntryId
    // =========================================================================

    @Test
    void commandThenDone_donePointsToCommand() {
        setup("mlc-causal-done-1", "agent-a", "agent-b");
        tools.sendMessage("mlc-causal-done-1", "agent-a", "command", "Run report",
                "corr-cd1", null, null, null);
        tools.sendMessage("mlc-causal-done-1", "agent-b", "done", "Report delivered",
                "corr-cd1", null, null, null);

        List<MessageLedgerEntry> entries = ledgerRepo.findByChannelId(channelId("mlc-causal-done-1"));
        assertEquals(2, entries.size());
        MessageLedgerEntry cmd = entries.get(0);
        MessageLedgerEntry done = entries.get(1);
        assertEquals("COMMAND", cmd.messageType);
        assertEquals("DONE", done.messageType);
        assertNotNull(done.causedByEntryId);
        assertEquals(cmd.id, done.causedByEntryId);
    }

    @Test
    void commandThenFailure_failurePointsToCommand() {
        setup("mlc-causal-fail-1", "agent-a", "agent-b");
        tools.sendMessage("mlc-causal-fail-1", "agent-a", "command", "Run migration",
                "corr-cf1", null, null, null);
        tools.sendMessage("mlc-causal-fail-1", "agent-b", "failure", "DB error",
                "corr-cf1", null, null, null);

        List<MessageLedgerEntry> entries = ledgerRepo.findByChannelId(channelId("mlc-causal-fail-1"));
        assertEquals(cmd(entries).id, terminal(entries, "FAILURE").causedByEntryId);
    }

    @Test
    void commandThenDecline_declinePointsToCommand() {
        setup("mlc-causal-dec-1", "agent-a", "agent-b");
        tools.sendMessage("mlc-causal-dec-1", "agent-a", "command", "Delete everything",
                "corr-cdec1", null, null, null);
        tools.sendMessage("mlc-causal-dec-1", "agent-b", "decline", "Out of scope",
                "corr-cdec1", null, null, null);

        List<MessageLedgerEntry> entries = ledgerRepo.findByChannelId(channelId("mlc-causal-dec-1"));
        assertEquals(cmd(entries).id, terminal(entries, "DECLINE").causedByEntryId);
    }

    @Test
    void commandHandoffDone_fullChain() {
        setup("mlc-causal-chain-1", "agent-a", "agent-b", "agent-c");
        tools.sendMessage("mlc-causal-chain-1", "agent-a", "command", "Audit",
                "corr-chain1", null, null, null);
        tools.sendMessage("mlc-causal-chain-1", "agent-b", "handoff", null,
                "corr-chain1", null, null, "instance:agent-c");
        tools.sendMessage("mlc-causal-chain-1", "agent-c", "done", "Audit complete",
                "corr-chain1", null, null, null);

        List<MessageLedgerEntry> entries = ledgerRepo.findByChannelId(channelId("mlc-causal-chain-1"));
        assertEquals(3, entries.size());
        MessageLedgerEntry cmd = entries.get(0);
        MessageLedgerEntry handoff = entries.get(1);
        MessageLedgerEntry done = entries.get(2);
        assertEquals("COMMAND", cmd.messageType);
        assertEquals("HANDOFF", handoff.messageType);
        assertEquals("DONE", done.messageType);
        assertEquals(cmd.id, handoff.causedByEntryId);
        assertEquals(handoff.id, done.causedByEntryId);
    }

    @Test
    void doneWithNoCorrelationId_causedByEntryIdNull() {
        setup("mlc-causal-nocorr-1", "agent-a");
        tools.sendMessage("mlc-causal-nocorr-1", "agent-a", "done", "Done with no correlation",
                null, null, null, null);

        List<MessageLedgerEntry> entries = ledgerRepo.findByChannelId(channelId("mlc-causal-nocorr-1"));
        assertEquals(1, entries.size());
        assertNull(entries.get(0).causedByEntryId);
    }

    // =========================================================================
    // listEntries filter correctness
    // =========================================================================

    @Test
    void listEntries_typeFilter_commandAndDone_excludesOtherTypes() {
        setup("mlc-filter-type-1", "agent-a", "agent-b");
        tools.sendMessage("mlc-filter-type-1", "agent-a", "command", "Go", "corr-ft1", null, null, null);
        tools.sendMessage("mlc-filter-type-1", "agent-a", "status", "Working", "corr-ft1", null, null, null);
        tools.sendMessage("mlc-filter-type-1", "agent-b", "done", "Done", "corr-ft1", null, null, null);

        UUID chId = channelId("mlc-filter-type-1");
        List<MessageLedgerEntry> entries = ledgerRepo.listEntries(
                chId, Set.of("COMMAND", "DONE"), null, null, null, 20);
        assertEquals(2, entries.size());
        assertTrue(entries.stream().allMatch(e -> Set.of("COMMAND", "DONE").contains(e.messageType)));
    }

    @Test
    void listEntries_agentFilter_returnsOnlyThatAgent() {
        setup("mlc-filter-agent-1", "agent-a", "agent-b");
        tools.sendMessage("mlc-filter-agent-1", "agent-a", "command", "Go", "corr-fa1", null, null, null);
        tools.sendMessage("mlc-filter-agent-1", "agent-b", "done", "Done", "corr-fa1", null, null, null);

        UUID chId = channelId("mlc-filter-agent-1");
        List<MessageLedgerEntry> entries = ledgerRepo.listEntries(chId, null, null, "agent-a", null, 20);
        assertEquals(1, entries.size());
        assertEquals("agent-a", entries.get(0).actorId);
    }

    @Test
    void listEntries_afterSequenceCursor_returnsLaterEntries() {
        setup("mlc-cursor-1", "agent-a", "agent-b");
        tools.sendMessage("mlc-cursor-1", "agent-a", "command", "Go", "corr-cur1", null, null, null);
        tools.sendMessage("mlc-cursor-1", "agent-a", "status", "Working", "corr-cur1", null, null, null);
        tools.sendMessage("mlc-cursor-1", "agent-b", "done", "Done", "corr-cur1", null, null, null);

        UUID chId = channelId("mlc-cursor-1");
        List<MessageLedgerEntry> page2 = ledgerRepo.listEntries(chId, null, 1L, null, null, 20);
        assertEquals(2, page2.size());
        assertTrue(page2.stream().allMatch(e -> e.sequenceNumber > 1));
    }

    @Test
    void listEntries_limit_capsResults() {
        setup("mlc-limit-1", "agent-a");
        for (int i = 0; i < 5; i++) {
            tools.sendMessage("mlc-limit-1", "agent-a", "event",
                    "{\"tool_name\":\"t\",\"duration_ms\":1}", null, null, null, null);
        }
        UUID chId = channelId("mlc-limit-1");
        List<MessageLedgerEntry> entries = ledgerRepo.listEntries(chId, null, null, null, null, 3);
        assertEquals(3, entries.size());
    }

    // =========================================================================
    // Robustness
    // =========================================================================

    @Test
    void sendMessage_eventWithEmptyContent_doesNotThrow() {
        setup("mlc-robust-1", "agent-a");
        assertDoesNotThrow(() -> tools.sendMessage("mlc-robust-1", "agent-a", "event", "", null, null, null, null));

        List<MessageLedgerEntry> entries = ledgerRepo.findByChannelId(channelId("mlc-robust-1"));
        assertEquals(1, entries.size());
    }

    @Test
    void sendMessage_allTypesProduceLedgerEntry() {
        setup("mlc-all-types-1", "agent-a", "agent-b");
        String corr = "corr-all";
        tools.sendMessage("mlc-all-types-1", "agent-a", "query", "Status?", corr, null, null, null);
        tools.sendMessage("mlc-all-types-1", "agent-b", "response", "Good", corr, null, null, null);
        tools.sendMessage("mlc-all-types-1", "agent-a", "command", "Go", corr, null, null, null);
        tools.sendMessage("mlc-all-types-1", "agent-b", "status", "Working", corr, null, null, null);
        tools.sendMessage("mlc-all-types-1", "agent-b", "done", "Done", corr, null, null, null);
        tools.sendMessage("mlc-all-types-1", "agent-a", "event",
                "{\"tool_name\":\"t\",\"duration_ms\":1}", null, null, null, null);

        List<MessageLedgerEntry> entries = ledgerRepo.findByChannelId(channelId("mlc-all-types-1"));
        assertEquals(6, entries.size());
    }

    // =========================================================================
    // Fixtures
    // =========================================================================

    private void setup(final String channel, final String... agents) {
        tools.createChannel(channel, "APPEND", null, null);
        for (final String agent : agents) {
            tools.registerInstance(channel, agent, null, null, null);
        }
    }

    private UUID channelId(final String channelName) {
        return Channel.<Channel> find("name", channelName)
                .firstResultOptional()
                .map(ch -> ch.id)
                .orElseThrow(() -> new IllegalStateException("Channel not found: " + channelName));
    }

    private MessageLedgerEntry cmd(final List<MessageLedgerEntry> entries) {
        return entries.stream().filter(e -> "COMMAND".equals(e.messageType)).findFirst()
                .orElseThrow(() -> new AssertionError("No COMMAND entry found"));
    }

    private MessageLedgerEntry terminal(final List<MessageLedgerEntry> entries, final String type) {
        return entries.stream().filter(e -> type.equals(e.messageType)).findFirst()
                .orElseThrow(() -> new AssertionError("No " + type + " entry found"));
    }
}
