package io.casehub.qhorus.ledger;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.mcp.server.ToolCallException;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase.CausalChainEntry;
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase.ObligationChainSummary;
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase.ObligationStats;
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase.StalledObligation;
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase.TelemetrySummary;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for the 6 new ledger query capabilities (Epic #110).
 *
 * <p>
 * Covers: enhanced {@code list_ledger_entries} (correlation_id + sort),
 * {@code get_obligation_chain}, {@code get_causal_chain},
 * {@code list_stalled_obligations}, {@code get_obligation_stats},
 * {@code get_telemetry_summary}.
 *
 * <p>
 * Test taxonomy per epic requirements:
 * <ul>
 * <li>Happy path — one per tool / variant</li>
 * <li>Correctness — computed values match expected semantics</li>
 * <li>Robustness — empty channel, unknown args, malformed input</li>
 * </ul>
 *
 * <p>
 * Refs #112–#116, Epic #110.
 */
@QuarkusTest
@TestTransaction
class LedgerQueryToolsTest {

    @Inject
    QhorusMcpTools tools;

    // =========================================================================
    // list_ledger_entries — correlation_id filter (§4.1)
    // =========================================================================

    @Test
    void listLedgerEntries_correlationIdFilter_returnsOnlyMatchingEntries() {
        setup("lle-corr-1", "agent-a", "agent-b");
        tools.sendMessage("lle-corr-1", "agent-a", "command", "Do A", "corr-A", null, null, null, null);
        tools.sendMessage("lle-corr-1", "agent-b", "done", "Done A", "corr-A", null, null, null, null);
        tools.sendMessage("lle-corr-1", "agent-a", "command", "Do B", "corr-B", null, null, null, null);

        final List<Map<String, Object>> entries = tools.listLedgerEntries("lle-corr-1", null, null, null, null, "corr-A", null,
                20);

        assertEquals(2, entries.size());
        assertTrue(entries.stream().allMatch(e -> "corr-A".equals(e.get("correlation_id"))));
    }

    @Test
    void listLedgerEntries_correlationIdFilter_noMatch_returnsEmpty() {
        setup("lle-corr-2", "agent-a");
        tools.sendMessage("lle-corr-2", "agent-a", "command", "Do X", "corr-X", null, null, null, null);

        final List<Map<String, Object>> entries = tools.listLedgerEntries("lle-corr-2", null, null, null, null, "no-match",
                null, 20);

        assertTrue(entries.isEmpty());
    }

    @Test
    void listLedgerEntries_sortDesc_returnsNewestFirst() {
        setup("lle-sort-1", "agent-a");
        tools.sendMessage("lle-sort-1", "agent-a", "command", "first", "c-s1", null, null, null, null);
        tools.sendMessage("lle-sort-1", "agent-a", "status", "middle", "c-s1", null, null, null, null);
        tools.sendMessage("lle-sort-1", "agent-a", "done", "last", "c-s1", null, null, null, null);

        final List<Map<String, Object>> entries = tools.listLedgerEntries("lle-sort-1", null, null, null, null, null, "desc",
                20);

        assertEquals(3, entries.size());
        final long seq0 = (Long) (Object) entries.get(0).get("sequence_number");
        final long seq1 = (Long) (Object) entries.get(1).get("sequence_number");
        assertTrue(seq0 > seq1, "desc sort must return newest first");
    }

    @Test
    void listLedgerEntries_sortAsc_returnsOldestFirst() {
        setup("lle-sort-2", "agent-a");
        tools.sendMessage("lle-sort-2", "agent-a", "command", "first", "c-s2", null, null, null, null);
        tools.sendMessage("lle-sort-2", "agent-a", "done", "last", "c-s2", null, null, null, null);

        final List<Map<String, Object>> entries = tools.listLedgerEntries("lle-sort-2", null, null, null, null, null, "asc",
                20);

        assertEquals(2, entries.size());
        final long seq0 = (Long) (Object) entries.get(0).get("sequence_number");
        final long seq1 = (Long) (Object) entries.get(1).get("sequence_number");
        assertTrue(seq0 < seq1, "asc sort must return oldest first");
    }

    @Test
    void listLedgerEntries_nullSort_defaultsToAsc() {
        setup("lle-sort-3", "agent-a");
        tools.sendMessage("lle-sort-3", "agent-a", "command", "first", "c-s3", null, null, null, null);
        tools.sendMessage("lle-sort-3", "agent-a", "done", "second", "c-s3", null, null, null, null);

        final List<Map<String, Object>> entries = tools.listLedgerEntries("lle-sort-3", null, null, null, null, null, null, 20);

        final long seq0 = (Long) (Object) entries.get(0).get("sequence_number");
        final long seq1 = (Long) (Object) entries.get(1).get("sequence_number");
        assertTrue(seq0 < seq1, "null sort must default to ascending");
    }

    @Test
    void listLedgerEntries_invalidSort_throws() {
        setup("lle-sort-bad-1", "agent-a");
        assertThrows(ToolCallException.class,
                () -> tools.listLedgerEntries("lle-sort-bad-1", null, null, null, null, null, "sideways", 20));
    }

    // =========================================================================
    // get_obligation_chain (§4.2)
    // =========================================================================

    @Test
    void getObligationChain_commandToDone_correctSummary() {
        setup("goc-1", "coordinator", "assessor");
        tools.sendMessage("goc-1", "coordinator", "command", "Assess damage", "corr-goc1", null, null, null, null);
        tools.sendMessage("goc-1", "assessor", "done", "Assessment done", "corr-goc1", null, null, null, null);

        final ObligationChainSummary summary = tools.getObligationChain("goc-1", "corr-goc1");

        assertEquals("corr-goc1", summary.correlationId());
        assertEquals("coordinator", summary.initiator());
        assertEquals("DONE", summary.resolution());
        assertTrue(summary.participants().contains("coordinator"));
        assertTrue(summary.participants().contains("assessor"));
        assertEquals(0, summary.handoffCount());
        assertNotNull(summary.createdAt());
        assertNotNull(summary.resolvedAt());
        assertNotNull(summary.elapsedSeconds());
        assertTrue(summary.elapsedSeconds() >= 0);
    }

    @Test
    void getObligationChain_commandToHandoffToDone_handoffCountAndParticipants() {
        setup("goc-2", "coordinator", "first-responder", "senior");
        tools.sendMessage("goc-2", "coordinator", "command", "Handle claim", "corr-goc2", null, null, null, null);
        tools.sendMessage("goc-2", "first-responder", "handoff", "Escalating", "corr-goc2", null, null, "instance:senior", null);
        tools.sendMessage("goc-2", "senior", "done", "Resolved", "corr-goc2", null, null, null, null);

        final ObligationChainSummary summary = tools.getObligationChain("goc-2", "corr-goc2");

        assertEquals(1, summary.handoffCount());
        assertEquals(3, summary.participants().size());
        // participants in encounter order: coordinator, first-responder, senior
        assertEquals("coordinator", summary.participants().get(0));
        assertEquals("first-responder", summary.participants().get(1));
        assertEquals("senior", summary.participants().get(2));
    }

    @Test
    void getObligationChain_commandToDecline_resolutionDeclined() {
        setup("goc-3", "coordinator", "assessor");
        tools.sendMessage("goc-3", "coordinator", "command", "Assess risk", "corr-goc3", null, null, null, null);
        tools.sendMessage("goc-3", "assessor", "decline", "Insufficient info", "corr-goc3", null, null, null, null);

        final ObligationChainSummary summary = tools.getObligationChain("goc-3", "corr-goc3");

        assertEquals("DECLINE", summary.resolution());
        assertNotNull(summary.resolvedAt());
    }

    @Test
    void getObligationChain_unknownCorrelationId_returnsNullFields() {
        setup("goc-4", "agent-a");

        final ObligationChainSummary summary = tools.getObligationChain("goc-4", "no-such-corr");

        assertNull(summary.initiator());
        assertNull(summary.resolution());
        assertTrue(summary.participants().isEmpty());
        assertEquals(0, summary.handoffCount());
    }

    @Test
    void getObligationChain_unknownChannel_throws() {
        assertThrows(ToolCallException.class,
                () -> tools.getObligationChain("no-such-channel", "corr-x"));
    }

    @Test
    void getObligationChain_openObligation_resolutionNull() {
        setup("goc-5", "coordinator");
        tools.sendMessage("goc-5", "coordinator", "command", "Pending work", "corr-goc5", null, null, null, null);

        final ObligationChainSummary summary = tools.getObligationChain("goc-5", "corr-goc5");

        assertNull(summary.resolution(), "Open obligation must have null resolution");
        assertNull(summary.resolvedAt());
        assertNull(summary.elapsedSeconds());
    }

    // =========================================================================
    // get_causal_chain (§4.3)
    // =========================================================================

    @Test
    void getCausalChain_commandToDone_chainLengthTwo() {
        setup("gcc-1", "coordinator", "worker");
        final var cmd = tools.sendMessage("gcc-1", "coordinator", "command", "Work", "corr-gcc1", null, null, null, null);
        tools.sendMessage("gcc-1", "worker", "done", "Done", "corr-gcc1", null, null, null, null);

        // Retrieve ledger entries to get the DONE entry's UUID
        final var entries = tools.listLedgerEntries("gcc-1", null, null, null, null, "corr-gcc1", null, 10);
        assertEquals(2, entries.size());
        final String doneEntryId = (String) entries.get(1).get("entry_id");
        assertNotNull(doneEntryId, "entry_id must be present in ledger entries");

        final List<CausalChainEntry> chain = tools.getCausalChain("gcc-1", doneEntryId);

        assertEquals(2, chain.size());
        assertEquals("COMMAND", chain.get(0).messageType());
        assertEquals("DONE", chain.get(1).messageType());
        assertNull(chain.get(0).causedByEntryId(), "Root must have no ancestor");
        assertNotNull(chain.get(1).causedByEntryId());
    }

    @Test
    void getCausalChain_commandHandoffDone_chainLengthThree() {
        setup("gcc-2", "coordinator", "first-responder", "senior");
        tools.sendMessage("gcc-2", "coordinator", "command", "Handle", "corr-gcc2", null, null, null, null);
        tools.sendMessage("gcc-2", "first-responder", "handoff", "Escalate", "corr-gcc2", null, null, "instance:senior", null);
        tools.sendMessage("gcc-2", "senior", "done", "Resolved", "corr-gcc2", null, null, null, null);

        final var entries = tools.listLedgerEntries("gcc-2", null, null, null, null, "corr-gcc2", null, 10);
        final String doneId = (String) entries.get(2).get("entry_id");

        final List<CausalChainEntry> chain = tools.getCausalChain("gcc-2", doneId);

        assertEquals(3, chain.size(), "COMMAND → HANDOFF → DONE = 3 entries");
        assertEquals("COMMAND", chain.get(0).messageType());
        assertEquals("HANDOFF", chain.get(1).messageType());
        assertEquals("DONE", chain.get(2).messageType());
    }

    @Test
    void getCausalChain_rootEntry_returnsSingleEntry() {
        setup("gcc-3", "coordinator");
        tools.sendMessage("gcc-3", "coordinator", "command", "Start", "corr-gcc3", null, null, null, null);

        final var entries = tools.listLedgerEntries("gcc-3", null, null, null, null, null, null, 10);
        final String cmdId = (String) entries.get(0).get("entry_id");

        final List<CausalChainEntry> chain = tools.getCausalChain("gcc-3", cmdId);

        assertEquals(1, chain.size());
        assertNull(chain.get(0).causedByEntryId());
    }

    @Test
    void getCausalChain_unknownEntryId_returnsEmpty() {
        setup("gcc-4", "agent-a");

        final List<CausalChainEntry> chain = tools.getCausalChain("gcc-4", "00000000-0000-0000-0000-000000000000");

        assertTrue(chain.isEmpty());
    }

    @Test
    void getCausalChain_invalidUuid_throws() {
        setup("gcc-5", "agent-a");
        assertThrows(ToolCallException.class,
                () -> tools.getCausalChain("gcc-5", "not-a-uuid"));
    }

    @Test
    void getCausalChain_unknownChannel_throws() {
        assertThrows(ToolCallException.class,
                () -> tools.getCausalChain("no-channel", "00000000-0000-0000-0000-000000000000"));
    }

    // =========================================================================
    // list_stalled_obligations (§4.4)
    // =========================================================================

    @Test
    void listStalledObligations_openCommandNoTerminal_returnedAsStalled() throws InterruptedException {
        setup("lso-1", "coordinator");
        tools.sendMessage("lso-1", "coordinator", "command", "Pending work", "corr-lso1", null, null, null, null);

        // Use threshold of 0 seconds so even a just-sent message is stalled
        final List<StalledObligation> stalled = tools.listStalledObligations("lso-1", 0);

        assertEquals(1, stalled.size());
        assertEquals("corr-lso1", stalled.get(0).correlationId());
        assertEquals("coordinator", stalled.get(0).actorId());
        assertTrue(stalled.get(0).stalledForSeconds() >= 0);
    }

    @Test
    void listStalledObligations_commandWithDone_notStalled() {
        setup("lso-2", "coordinator", "worker");
        tools.sendMessage("lso-2", "coordinator", "command", "Work", "corr-lso2", null, null, null, null);
        tools.sendMessage("lso-2", "worker", "done", "Done", "corr-lso2", null, null, null, null);

        final List<StalledObligation> stalled = tools.listStalledObligations("lso-2", 0);

        assertTrue(stalled.isEmpty());
    }

    @Test
    void listStalledObligations_commandWithFailure_notStalled() {
        setup("lso-3", "coordinator", "worker");
        tools.sendMessage("lso-3", "coordinator", "command", "Work", "corr-lso3", null, null, null, null);
        tools.sendMessage("lso-3", "worker", "failure", "Failed badly", "corr-lso3", null, null, null, null);

        final List<StalledObligation> stalled = tools.listStalledObligations("lso-3", 0);

        assertTrue(stalled.isEmpty());
    }

    @Test
    void listStalledObligations_commandWithDecline_notStalled() {
        setup("lso-4", "coordinator", "worker");
        tools.sendMessage("lso-4", "coordinator", "command", "Work", "corr-lso4", null, null, null, null);
        tools.sendMessage("lso-4", "worker", "decline", "Cannot do", "corr-lso4", null, null, null, null);

        final List<StalledObligation> stalled = tools.listStalledObligations("lso-4", 0);

        assertTrue(stalled.isEmpty());
    }

    @Test
    void listStalledObligations_defaultThreshold_recentCommandNotStalled() {
        setup("lso-5", "coordinator");
        // A just-sent COMMAND should NOT be stalled at the default 30s threshold
        tools.sendMessage("lso-5", "coordinator", "command", "Brand new", "corr-lso5", null, null, null, null);

        final List<StalledObligation> stalled = tools.listStalledObligations("lso-5", null);

        assertTrue(stalled.isEmpty(), "A brand-new COMMAND must not be stalled at 30s threshold");
    }

    @Test
    void listStalledObligations_emptyChannel_returnsEmpty() {
        setup("lso-6", "agent-a");

        final List<StalledObligation> stalled = tools.listStalledObligations("lso-6", null);

        assertTrue(stalled.isEmpty());
    }

    @Test
    void listStalledObligations_unknownChannel_throws() {
        assertThrows(ToolCallException.class,
                () -> tools.listStalledObligations("no-channel", null));
    }

    @Test
    void listStalledObligations_result_containsExpectedFields() {
        setup("lso-7", "coordinator");
        tools.sendMessage("lso-7", "coordinator", "command", "My content", "corr-lso7", null, null, null, null);

        final List<StalledObligation> stalled = tools.listStalledObligations("lso-7", 0);

        assertEquals(1, stalled.size());
        final StalledObligation s = stalled.get(0);
        assertNotNull(s.occurredAt());
        assertNotNull(s.content());
        assertTrue(s.stalledForSeconds() >= 0);
    }

    // =========================================================================
    // get_obligation_stats (§4.5)
    // =========================================================================

    @Test
    void getObligationStats_mixedOutcomes_correctRates() {
        setup("gos-1", "coordinator", "worker");
        // 3 COMMANDs: 2 DONE, 1 FAILURE
        tools.sendMessage("gos-1", "coordinator", "command", "Work 1", "corr-gos1a", null, null, null, null);
        tools.sendMessage("gos-1", "worker", "done", "Done 1", "corr-gos1a", null, null, null, null);
        tools.sendMessage("gos-1", "coordinator", "command", "Work 2", "corr-gos1b", null, null, null, null);
        tools.sendMessage("gos-1", "worker", "done", "Done 2", "corr-gos1b", null, null, null, null);
        tools.sendMessage("gos-1", "coordinator", "command", "Work 3", "corr-gos1c", null, null, null, null);
        tools.sendMessage("gos-1", "worker", "failure", "Broke", "corr-gos1c", null, null, null, null);

        final ObligationStats stats = tools.getObligationStats("gos-1");

        assertEquals(3, stats.totalCommands());
        assertEquals(2, stats.fulfilled());
        assertEquals(1, stats.failed());
        assertEquals(0, stats.declined());
        assertEquals(0, stats.delegated());
        assertEquals(0, stats.stillOpen());
        assertApprox(2.0 / 3.0, stats.fulfillmentRate());
    }

    @Test
    void getObligationStats_openCommand_countedInStillOpen() {
        setup("gos-2", "coordinator");
        tools.sendMessage("gos-2", "coordinator", "command", "Pending", "corr-gos2", null, null, null, null);

        final ObligationStats stats = tools.getObligationStats("gos-2");

        assertEquals(1, stats.totalCommands());
        assertEquals(0, stats.fulfilled());
        assertEquals(1, stats.stillOpen());
    }

    @Test
    void getObligationStats_declinedAndDelegated_counted() {
        setup("gos-3", "coordinator", "worker-a", "worker-b");
        tools.sendMessage("gos-3", "coordinator", "command", "Task A", "corr-gos3a", null, null, null, null);
        tools.sendMessage("gos-3", "worker-a", "decline", "Cannot", "corr-gos3a", null, null, null, null);
        tools.sendMessage("gos-3", "coordinator", "command", "Task B", "corr-gos3b", null, null, null, null);
        tools.sendMessage("gos-3", "worker-a", "handoff", "Delegating", "corr-gos3b", null, null, "instance:worker-b", null);

        final ObligationStats stats = tools.getObligationStats("gos-3");

        assertEquals(1, stats.declined());
        assertEquals(1, stats.delegated());
    }

    @Test
    void getObligationStats_emptyChannel_allZerosNanRate() {
        setup("gos-4", "agent-a");

        final ObligationStats stats = tools.getObligationStats("gos-4");

        assertEquals(0, stats.totalCommands());
        assertEquals(0.0, stats.fulfillmentRate());
    }

    @Test
    void getObligationStats_unknownChannel_throws() {
        assertThrows(ToolCallException.class,
                () -> tools.getObligationStats("no-channel"));
    }

    // =========================================================================
    // get_telemetry_summary (§4.6)
    // =========================================================================

    @Test
    void getTelemetrySummary_multipleEventsPerTool_aggregatesCorrectly() {
        setup("gts-1", "agent-a");
        tools.sendMessage("gts-1", "agent-a", "event", "{\"tool_name\":\"ml-score\",\"duration_ms\":200,\"token_count\":100}", null, null, null, null, null);
        tools.sendMessage("gts-1", "agent-a", "event", "{\"tool_name\":\"ml-score\",\"duration_ms\":400,\"token_count\":200}", null, null, null, null, null);
        tools.sendMessage("gts-1", "agent-a", "event", "{\"tool_name\":\"sanctions\",\"duration_ms\":50,\"token_count\":0}", null, null, null, null, null);

        final TelemetrySummary summary = tools.getTelemetrySummary("gts-1", null);

        assertEquals(3, summary.totalEvents());
        assertEquals(300, summary.totalTokens());
        assertEquals(650, summary.totalDurationMs());

        final var mlScore = summary.byTool().get("ml-score");
        assertNotNull(mlScore);
        assertEquals(2, mlScore.count());
        assertEquals(300, mlScore.avgDurationMs(), "avg of 200 and 400");
        assertEquals(300, mlScore.totalTokens());

        final var sanctions = summary.byTool().get("sanctions");
        assertNotNull(sanctions);
        assertEquals(1, sanctions.count());
        assertEquals(50, sanctions.avgDurationMs());
    }

    @Test
    void getTelemetrySummary_missingToolName_countedUnderNull() {
        setup("gts-2", "agent-a");
        tools.sendMessage("gts-2", "agent-a", "event", "{\"duration_ms\":10}", null, null, null, null, null); // no tool_name

        final TelemetrySummary summary = tools.getTelemetrySummary("gts-2", null);

        assertEquals(1, summary.totalEvents());
        assertTrue(summary.byTool().containsKey(null), "Null tool_name should be a key in byTool");
    }

    @Test
    void getTelemetrySummary_nonEventMessagesExcluded() {
        setup("gts-3", "agent-a", "agent-b");
        tools.sendMessage("gts-3", "agent-a", "command", "Do X", "c-gts3", null, null, null, null);
        tools.sendMessage("gts-3", "agent-b", "done", "Done", "c-gts3", null, null, null, null);
        tools.sendMessage("gts-3", "agent-a", "event", "{\"tool_name\":\"t\",\"duration_ms\":5,\"token_count\":10}", null, null, null, null, null);

        final TelemetrySummary summary = tools.getTelemetrySummary("gts-3", null);

        assertEquals(1, summary.totalEvents(), "COMMAND and DONE must not count as events");
    }

    @Test
    void getTelemetrySummary_emptyChannel_allZeros() {
        setup("gts-4", "agent-a");

        final TelemetrySummary summary = tools.getTelemetrySummary("gts-4", null);

        assertEquals(0, summary.totalEvents());
        assertTrue(summary.byTool().isEmpty());
        assertEquals(0, summary.totalTokens());
        assertEquals(0, summary.totalDurationMs());
    }

    @Test
    void getTelemetrySummary_unknownChannel_throws() {
        assertThrows(ToolCallException.class,
                () -> tools.getTelemetrySummary("no-channel", null));
    }

    @Test
    void getTelemetrySummary_sinceFilter_excludesOlderEvents() {
        // This test verifies the since parameter reaches the DB query — behaviour
        // already unit-tested in LedgerQueryRepoTest. Here we just verify the
        // tool wires it through correctly (no exception, correct type returned).
        setup("gts-5", "agent-a");
        tools.sendMessage("gts-5", "agent-a", "event", "{\"tool_name\":\"t\",\"duration_ms\":1,\"token_count\":1}", null, null, null, null, null);

        // since = far future → excludes the event
        final TelemetrySummary summary = tools.getTelemetrySummary("gts-5", "2099-01-01T00:00:00Z");

        assertEquals(0, summary.totalEvents());
    }

    // =========================================================================
    // Fixture
    // =========================================================================

    private void setup(final String channel, final String... agents) {
        tools.createChannel(channel, "Test channel", "APPEND", null, null, null, null, null, null);
        for (final String agent : agents) {
            tools.registerInstance(channel, agent, null, null, null);
        }
    }

    private static void assertApprox(final double expected, final double actual) {
        assertEquals(expected, actual, 0.001);
    }
}
