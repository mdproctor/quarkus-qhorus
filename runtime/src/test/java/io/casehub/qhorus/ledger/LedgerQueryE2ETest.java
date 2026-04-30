package io.casehub.qhorus.ledger;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase.CausalChainEntry;
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase.ObligationChainSummary;
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase.ObligationStats;
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase.StalledObligation;
import io.casehub.qhorus.runtime.mcp.QhorusMcpToolsBase.TelemetrySummary;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * End-to-end scenario tests for ledger query capabilities (Epic #110).
 *
 * <p>
 * Each test method drives a full or partial insurance claim scenario inline,
 * following the established pattern of self-contained {@code @TestTransaction}
 * tests (setup inside the test method, not in {@code @BeforeEach}).
 *
 * <p>
 * Covers: all 9 message types, all 4 channels, all 6 query tools.
 * Obligation patterns: QUERY→RESPONSE, COMMAND→STATUS→DONE, COMMAND→DECLINE,
 * COMMAND→HANDOFF→DONE, COMMAND→FAILURE, EVENT telemetry.
 *
 * <p>
 * Refs #117, Epic #110.
 */
@QuarkusTest
@TestTransaction
class LedgerQueryE2ETest {

    @Inject
    QhorusMcpTools tools;

    // =========================================================================
    // All-9-types smoke test
    // =========================================================================

    @Test
    void e2e_allNineMessageTypes_presentInLedger() {
        final String ch = "e2e-all9-1";
        setup(ch, "coordinator", "worker");

        // QUERY + RESPONSE
        tools.sendMessage(ch, "coordinator", "query", "Info?", "corr-q1", null, null, null);
        tools.sendMessage(ch, "worker", "response", "Here.", "corr-q1", null, null, null);
        // COMMAND + STATUS + DONE
        tools.sendMessage(ch, "coordinator", "command", "Work", "corr-c1", null, null, null);
        tools.sendMessage(ch, "worker", "status", "Doing it", "corr-c1", null, null, null);
        tools.sendMessage(ch, "worker", "done", "Done", "corr-c1", null, null, null);
        // DECLINE
        tools.sendMessage(ch, "coordinator", "command", "Do X", "corr-c2", null, null, null);
        tools.sendMessage(ch, "worker", "decline", "Cannot", "corr-c2", null, null, null);
        // HANDOFF
        tools.sendMessage(ch, "coordinator", "command", "Escalate", "corr-c3", null, null, null);
        tools.sendMessage(ch, "worker", "handoff", "Passing on", "corr-c3", null, null, "instance:coordinator");
        // FAILURE
        tools.sendMessage(ch, "coordinator", "command", "Risk it", "corr-c4", null, null, null);
        tools.sendMessage(ch, "worker", "failure", "Failed", "corr-c4", null, null, null);
        // EVENT
        tools.sendMessage(ch, "worker", "event",
                "{\"tool_name\":\"ml-score\",\"duration_ms\":100,\"token_count\":50}",
                null, null, null, null);

        final List<Map<String, Object>> all = tools.listLedgerEntries(ch, null, null, null, null, null, null, 100);
        final java.util.Set<String> types = all.stream()
                .map(e -> (String) e.get("message_type"))
                .collect(java.util.stream.Collectors.toSet());

        final java.util.Set<String> expected = java.util.Set.of(
                "QUERY", "RESPONSE", "COMMAND", "STATUS", "DONE", "DECLINE", "HANDOFF", "FAILURE", "EVENT");
        assertEquals(expected, types, "All 9 message types must appear in the ledger");
    }

    // =========================================================================
    // list_ledger_entries — enhanced filters over scenario data
    // =========================================================================

    @Test
    void e2e_correlationIdFilter_returnsOnlyFraudChain() {
        final String ch = "e2e-corr-filter-1";
        setup(ch, "coordinator", "fraud-detection");
        tools.sendMessage(ch, "coordinator", "command", "Run fraud score", "corr-fraud", null, null, null);
        tools.sendMessage(ch, "fraud-detection", "event",
                "{\"tool_name\":\"ml\",\"duration_ms\":200,\"token_count\":100}", null, null, null, null);
        tools.sendMessage(ch, "fraud-detection", "done", "LOW 0.12", "corr-fraud", null, null, null);
        tools.sendMessage(ch, "coordinator", "command", "Unrelated work", "corr-other", null, null, null);

        final List<Map<String, Object>> entries = tools.listLedgerEntries(ch, null, null, null, null, "corr-fraud", null, 10);

        assertEquals(2, entries.size(), "Only COMMAND+DONE for corr-fraud (EVENT has no correlationId)");
        assertTrue(entries.stream().allMatch(e -> "corr-fraud".equals(e.get("correlation_id"))));
    }

    @Test
    void e2e_sortDesc_newestFirst() {
        final String ch = "e2e-sort-desc-1";
        setup(ch, "coordinator", "worker");
        tools.sendMessage(ch, "coordinator", "command", "A", "corr-sd1", null, null, null);
        tools.sendMessage(ch, "worker", "status", "B", "corr-sd1", null, null, null);
        tools.sendMessage(ch, "worker", "done", "C", "corr-sd1", null, null, null);

        final List<Map<String, Object>> entries = tools.listLedgerEntries(ch, null, null, null, null, null, "desc", 10);

        assertEquals(3, entries.size());
        assertEquals("DONE", entries.get(0).get("message_type"));
        assertEquals("STATUS", entries.get(1).get("message_type"));
        assertEquals("COMMAND", entries.get(2).get("message_type"));
    }

    // =========================================================================
    // get_obligation_chain — multi-step obligation scenarios
    // =========================================================================

    @Test
    void e2e_obligationChain_commandToDone_sanctionsScenario() {
        final String ch = "e2e-goc-sanctions-1";
        setup(ch, "coordinator", "sanctions-screener");
        tools.sendMessage(ch, "coordinator", "command",
                "Screen Acme Corp on OFAC/HMT", "corr-sanctions", null, null, null);
        tools.sendMessage(ch, "sanctions-screener", "status", "Screening...", "corr-sanctions", null, null, null);
        tools.sendMessage(ch, "sanctions-screener", "done", "Acme Corp clear", "corr-sanctions", null, null, null);

        final ObligationChainSummary chain = tools.getObligationChain(ch, "corr-sanctions");

        assertEquals("corr-sanctions", chain.correlationId());
        assertEquals("coordinator", chain.initiator());
        assertEquals("DONE", chain.resolution());
        assertEquals(0, chain.handoffCount());
        assertEquals(2, chain.participants().size());
        assertEquals("coordinator", chain.participants().get(0));
        assertEquals("sanctions-screener", chain.participants().get(1));
        assertNotNull(chain.elapsedSeconds());
        assertTrue(chain.elapsedSeconds() >= 0);
    }

    @Test
    void e2e_obligationChain_commandToHandoffToDone_escalationScenario() {
        final String ch = "e2e-goc-escalation-1";
        setup(ch, "coordinator", "compliance-officer", "senior-adjuster");
        tools.sendMessage(ch, "coordinator", "command",
                "Syndicate approval escalation", "corr-escalation", null, null, null);
        tools.sendMessage(ch, "compliance-officer", "handoff",
                "Escalating to senior", "corr-escalation", null, null, "instance:senior-adjuster");
        tools.sendMessage(ch, "senior-adjuster", "done",
                "LLY-2026-04-789 approved", "corr-escalation", null, null, null);

        final ObligationChainSummary chain = tools.getObligationChain(ch, "corr-escalation");

        assertEquals(1, chain.handoffCount());
        assertEquals("DONE", chain.resolution());
        assertEquals(3, chain.participants().size());
        assertEquals("coordinator", chain.participants().get(0));
        assertEquals("compliance-officer", chain.participants().get(1));
        assertEquals("senior-adjuster", chain.participants().get(2));
    }

    @Test
    void e2e_obligationChain_commandToDecline_fcaScenario() {
        final String ch = "e2e-goc-fca-1";
        setup(ch, "coordinator", "compliance-officer");
        tools.sendMessage(ch, "coordinator", "command",
                "FCA compliance check", "corr-fca-fail", null, null, null);
        tools.sendMessage(ch, "compliance-officer", "decline",
                "Missing Lloyd's syndicate approval", "corr-fca-fail", null, null, null);

        final ObligationChainSummary chain = tools.getObligationChain(ch, "corr-fca-fail");

        assertEquals("DECLINE", chain.resolution());
        assertNotNull(chain.resolvedAt());
        assertNotNull(chain.elapsedSeconds());
    }

    @Test
    void e2e_obligationChain_openObligation_nullResolution() {
        final String ch = "e2e-goc-open-1";
        setup(ch, "coordinator");
        tools.sendMessage(ch, "coordinator", "command", "Dispatch surveyor", "corr-stall", null, null, null);
        // No response sent — obligation remains open

        final ObligationChainSummary chain = tools.getObligationChain(ch, "corr-stall");

        assertNull(chain.resolution());
        assertNull(chain.resolvedAt());
        assertNull(chain.elapsedSeconds());
        assertEquals(1, chain.participants().size());
    }

    @Test
    void e2e_obligationChain_commandToFailure_bacsScenario() {
        final String ch = "e2e-goc-payments-1";
        setup(ch, "coordinator", "payment-processor");
        tools.sendMessage(ch, "coordinator", "command", "BACS payout £180k", "corr-bacs", null, null, null);
        tools.sendMessage(ch, "payment-processor", "failure",
                "Invalid sort code 20-14-09", "corr-bacs", null, null, null);

        final ObligationChainSummary chain = tools.getObligationChain(ch, "corr-bacs");

        assertEquals("FAILURE", chain.resolution());
    }

    // =========================================================================
    // get_causal_chain
    // =========================================================================

    @Test
    void e2e_causalChain_escalationDone_threeHops() {
        final String ch = "e2e-gcc-escalation-1";
        setup(ch, "coordinator", "compliance-officer", "senior-adjuster");
        tools.sendMessage(ch, "coordinator", "command", "Escalate", "corr-esc", null, null, null);
        tools.sendMessage(ch, "compliance-officer", "handoff", "Escalating", "corr-esc", null,
                null, "instance:senior-adjuster");
        tools.sendMessage(ch, "senior-adjuster", "done", "Approved", "corr-esc", null, null, null);

        final List<Map<String, Object>> entries = tools.listLedgerEntries(ch, null, null, null, null, "corr-esc", null, 10);
        assertEquals(3, entries.size());
        final String doneEntryId = (String) entries.get(2).get("entry_id");
        assertNotNull(doneEntryId);

        final List<CausalChainEntry> chain = tools.getCausalChain(ch, doneEntryId);

        assertEquals(3, chain.size());
        assertEquals("COMMAND", chain.get(0).messageType());
        assertEquals("HANDOFF", chain.get(1).messageType());
        assertEquals("DONE", chain.get(2).messageType());
        assertNull(chain.get(0).causedByEntryId());
        assertNotNull(chain.get(1).causedByEntryId());
        assertNotNull(chain.get(2).causedByEntryId());
    }

    @Test
    void e2e_causalChain_commandToDone_twoHops() {
        final String ch = "e2e-gcc-done-1";
        setup(ch, "coordinator", "worker");
        tools.sendMessage(ch, "coordinator", "command", "Work", "corr-w1", null, null, null);
        tools.sendMessage(ch, "worker", "done", "Done", "corr-w1", null, null, null);

        final List<Map<String, Object>> entries = tools.listLedgerEntries(ch, null, null, null, null, "corr-w1", null, 10);
        final String doneId = (String) entries.get(1).get("entry_id");

        final List<CausalChainEntry> chain = tools.getCausalChain(ch, doneId);

        assertEquals(2, chain.size());
        assertEquals("COMMAND", chain.get(0).messageType());
        assertEquals("DONE", chain.get(1).messageType());
    }

    // =========================================================================
    // list_stalled_obligations
    // =========================================================================

    @Test
    void e2e_stalledObligations_damageAssessor_detectedAtZeroThreshold() {
        final String ch = "e2e-lso-stall-1";
        setup(ch, "coordinator", "sanctions-screener");

        // One completed obligation
        tools.sendMessage(ch, "coordinator", "command", "Sanctions check", "corr-done", null, null, null);
        tools.sendMessage(ch, "sanctions-screener", "done", "Clear", "corr-done", null, null, null);

        // One stalled obligation
        tools.sendMessage(ch, "coordinator", "command", "Dispatch surveyor", "corr-stall", null, null, null);

        final List<StalledObligation> stalled = tools.listStalledObligations(ch, 0);

        assertEquals(1, stalled.size(), "Only the stalled obligation must appear");
        assertEquals("corr-stall", stalled.get(0).correlationId());
        assertEquals("coordinator", stalled.get(0).actorId());
        assertNotNull(stalled.get(0).occurredAt());
    }

    @Test
    void e2e_stalledObligations_allCompleted_emptyResult() {
        final String ch = "e2e-lso-clean-1";
        setup(ch, "coordinator", "worker");
        tools.sendMessage(ch, "coordinator", "command", "Task A", "corr-a", null, null, null);
        tools.sendMessage(ch, "worker", "done", "A done", "corr-a", null, null, null);
        tools.sendMessage(ch, "coordinator", "command", "Task B", "corr-b", null, null, null);
        tools.sendMessage(ch, "worker", "decline", "B refused", "corr-b", null, null, null);

        final List<StalledObligation> stalled = tools.listStalledObligations(ch, 0);

        assertTrue(stalled.isEmpty());
    }

    // =========================================================================
    // get_obligation_stats — cross-channel scenario
    // =========================================================================

    @Test
    void e2e_obligationStats_claimChannel_threeCommands() {
        final String ch = "e2e-gos-claim-1";
        setup(ch, "coordinator", "worker");

        // 2 DONE
        tools.sendMessage(ch, "coordinator", "command", "Sanctions", "corr-g1", null, null, null);
        tools.sendMessage(ch, "worker", "done", "Clear", "corr-g1", null, null, null);
        tools.sendMessage(ch, "coordinator", "command", "Fraud", "corr-g2", null, null, null);
        tools.sendMessage(ch, "worker", "done", "LOW", "corr-g2", null, null, null);
        // 1 open (stalled)
        tools.sendMessage(ch, "coordinator", "command", "Dispatch surveyor", "corr-g3", null, null, null);

        final ObligationStats stats = tools.getObligationStats(ch);

        assertEquals(3, stats.totalCommands());
        assertEquals(2, stats.fulfilled());
        assertEquals(0, stats.failed());
        assertEquals(0, stats.declined());
        assertEquals(0, stats.delegated());
        assertEquals(1, stats.stillOpen());
        assertEquals(2.0 / 3.0, stats.fulfillmentRate(), 0.001);
    }

    @Test
    void e2e_obligationStats_paymentsChannel_failureAndDone() {
        final String ch = "e2e-gos-payments-1";
        setup(ch, "coordinator", "payment-processor");

        tools.sendMessage(ch, "coordinator", "command", "BACS payout", "corr-bacs", null, null, null);
        tools.sendMessage(ch, "payment-processor", "failure", "Invalid sort code", "corr-bacs", null, null, null);
        tools.sendMessage(ch, "coordinator", "command", "CHAPS retry", "corr-chaps", null, null, null);
        tools.sendMessage(ch, "payment-processor", "done", "CHAPS confirmed", "corr-chaps", null, null, null);

        final ObligationStats stats = tools.getObligationStats(ch);

        assertEquals(2, stats.totalCommands());
        assertEquals(1, stats.fulfilled());
        assertEquals(1, stats.failed());
        assertEquals(0.5, stats.fulfillmentRate(), 0.001);
    }

    @Test
    void e2e_obligationStats_complianceChannel_declineAndDone() {
        final String ch = "e2e-gos-compliance-1";
        setup(ch, "coordinator", "compliance-officer", "regulatory-reporter");

        // FCA fails (decline)
        tools.sendMessage(ch, "coordinator", "command", "FCA check", "corr-fca1", null, null, null);
        tools.sendMessage(ch, "compliance-officer", "decline", "Missing approval", "corr-fca1", null, null, null);
        // FCA passes (done)
        tools.sendMessage(ch, "coordinator", "command", "FCA re-check", "corr-fca2", null, null, null);
        tools.sendMessage(ch, "compliance-officer", "done", "Verified", "corr-fca2", null, null, null);
        // Solvency II (done)
        tools.sendMessage(ch, "coordinator", "command", "Solvency II", "corr-solv", null, null, null);
        tools.sendMessage(ch, "regulatory-reporter", "done", "Filed", "corr-solv", null, null, null);

        final ObligationStats stats = tools.getObligationStats(ch);

        assertEquals(3, stats.totalCommands());
        assertEquals(2, stats.fulfilled());
        assertEquals(1, stats.declined());
        assertEquals(0, stats.failed());
    }

    @Test
    void e2e_obligationStats_highValueReview_handoffDelegation() {
        final String ch = "e2e-gos-review-1";
        setup(ch, "coordinator", "compliance-officer", "senior-adjuster");

        tools.sendMessage(ch, "coordinator", "command", "Syndicate approval", "corr-esc", null, null, null);
        tools.sendMessage(ch, "compliance-officer", "handoff",
                "Escalating", "corr-esc", null, null, "instance:senior-adjuster");
        tools.sendMessage(ch, "senior-adjuster", "done", "Approved", "corr-esc", null, null, null);

        final ObligationStats stats = tools.getObligationStats(ch);

        // 1 COMMAND + 1 HANDOFF + 1 DONE: delegated counts HANDOFF entries,
        // fulfilled counts DONE entries — both can be 1 for COMMAND→HANDOFF→DONE.
        assertEquals(1, stats.totalCommands());
        assertEquals(1, stats.fulfilled());
        assertEquals(1, stats.delegated());
        assertEquals(0, stats.stillOpen());
        assertEquals(1.0, stats.fulfillmentRate(), 0.001);
    }

    // =========================================================================
    // get_telemetry_summary — multi-tool aggregation
    // =========================================================================

    @Test
    void e2e_telemetrySummary_mlFraudAndSolvencyApi() {
        final String ch = "e2e-gts-multi-1";
        setup(ch, "agent-a");

        // ML fraud score: 2 events
        tools.sendMessage(ch, "agent-a", "event",
                "{\"tool_name\":\"ml-fraud-score\",\"duration_ms\":2341,\"token_count\":1200}",
                null, null, null, null);
        tools.sendMessage(ch, "agent-a", "event",
                "{\"tool_name\":\"ml-fraud-score\",\"duration_ms\":1859,\"token_count\":1100}",
                null, null, null, null);
        // Solvency API: 1 event
        tools.sendMessage(ch, "agent-a", "event",
                "{\"tool_name\":\"solvency-api\",\"duration_ms\":450,\"token_count\":0}",
                null, null, null, null);

        final TelemetrySummary summary = tools.getTelemetrySummary(ch, null);

        assertEquals(3, summary.totalEvents());
        assertEquals(2300, summary.totalTokens());
        assertEquals(4650, summary.totalDurationMs());

        final var mlScore = summary.byTool().get("ml-fraud-score");
        assertNotNull(mlScore);
        assertEquals(2, mlScore.count());
        assertEquals(2100, mlScore.avgDurationMs(), "avg of 2341 and 1859");
        assertEquals(2300, mlScore.totalTokens());

        final var solvency = summary.byTool().get("solvency-api");
        assertNotNull(solvency);
        assertEquals(1, solvency.count());
        assertEquals(450, solvency.avgDurationMs());
        assertEquals(0, solvency.totalTokens());
    }

    @Test
    void e2e_telemetrySummary_nonEventMessagesExcluded() {
        final String ch = "e2e-gts-exclude-1";
        setup(ch, "coordinator", "worker");
        tools.sendMessage(ch, "coordinator", "command", "Work", "corr-x", null, null, null);
        tools.sendMessage(ch, "worker", "done", "Done", "corr-x", null, null, null);
        tools.sendMessage(ch, "worker", "event",
                "{\"tool_name\":\"t\",\"duration_ms\":5,\"token_count\":10}", null, null, null, null);

        final TelemetrySummary summary = tools.getTelemetrySummary(ch, null);

        assertEquals(1, summary.totalEvents());
    }

    @Test
    void e2e_telemetrySummary_missingToolName_countedUnderNull() {
        final String ch = "e2e-gts-null-tool-1";
        setup(ch, "agent-a");
        tools.sendMessage(ch, "agent-a", "event", "{\"duration_ms\":10}", null, null, null, null);

        final TelemetrySummary summary = tools.getTelemetrySummary(ch, null);

        assertEquals(1, summary.totalEvents());
        assertTrue(summary.byTool().containsKey(null));
    }

    // =========================================================================
    // Cross-channel integration — all 4 channels together
    // =========================================================================

    @Test
    void e2e_crossChannel_obligationStatsSummarizedPerChannel() {
        final String chClaim = "e2e-cross-claim-1";
        final String chCompliance = "e2e-cross-compliance-1";
        final String chPayments = "e2e-cross-payments-1";

        setup(chClaim, "coordinator", "sanctions-screener", "fraud-detection");
        setup(chCompliance, "coordinator", "compliance-officer", "regulatory-reporter");
        setup(chPayments, "coordinator", "payment-processor");

        // CH_CLAIM: 2 DONE
        tools.sendMessage(chClaim, "coordinator", "command", "Sanctions", "corr-cc-s", null, null, null);
        tools.sendMessage(chClaim, "sanctions-screener", "done", "Clear", "corr-cc-s", null, null, null);
        tools.sendMessage(chClaim, "coordinator", "command", "Fraud", "corr-cc-f", null, null, null);
        tools.sendMessage(chClaim, "fraud-detection", "done", "LOW", "corr-cc-f", null, null, null);

        // CH_COMPLIANCE: 1 DECLINE + 2 DONE
        tools.sendMessage(chCompliance, "coordinator", "command", "FCA fail", "corr-cc-fc1", null, null, null);
        tools.sendMessage(chCompliance, "compliance-officer", "decline", "Missing", "corr-cc-fc1", null, null, null);
        tools.sendMessage(chCompliance, "coordinator", "command", "FCA pass", "corr-cc-fc2", null, null, null);
        tools.sendMessage(chCompliance, "compliance-officer", "done", "Verified", "corr-cc-fc2", null, null, null);
        tools.sendMessage(chCompliance, "coordinator", "command", "Solvency", "corr-cc-solv", null, null, null);
        tools.sendMessage(chCompliance, "regulatory-reporter", "done", "Filed", "corr-cc-solv", null, null, null);

        // CH_PAYMENTS: 1 FAILURE + 1 DONE
        tools.sendMessage(chPayments, "coordinator", "command", "BACS", "corr-cc-bacs", null, null, null);
        tools.sendMessage(chPayments, "payment-processor", "failure", "Bounced", "corr-cc-bacs", null, null, null);
        tools.sendMessage(chPayments, "coordinator", "command", "CHAPS", "corr-cc-chaps", null, null, null);
        tools.sendMessage(chPayments, "payment-processor", "done", "Paid", "corr-cc-chaps", null, null, null);

        final ObligationStats claimStats = tools.getObligationStats(chClaim);
        final ObligationStats complianceStats = tools.getObligationStats(chCompliance);
        final ObligationStats paymentStats = tools.getObligationStats(chPayments);

        // Claim: 2/2 fulfilled
        assertEquals(2, claimStats.totalCommands());
        assertEquals(2, claimStats.fulfilled());
        assertEquals(1.0, claimStats.fulfillmentRate(), 0.001);

        // Compliance: 2/3 fulfilled (1 declined)
        assertEquals(3, complianceStats.totalCommands());
        assertEquals(2, complianceStats.fulfilled());
        assertEquals(1, complianceStats.declined());
        assertEquals(2.0 / 3.0, complianceStats.fulfillmentRate(), 0.001);

        // Payments: 1/2 fulfilled (1 failed)
        assertEquals(2, paymentStats.totalCommands());
        assertEquals(1, paymentStats.fulfilled());
        assertEquals(1, paymentStats.failed());
        assertEquals(0.5, paymentStats.fulfillmentRate(), 0.001);
    }

    // =========================================================================
    // Fixture
    // =========================================================================

    private void setup(final String channel, final String... agents) {
        tools.createChannel(channel, "Test channel", "APPEND", null);
        for (final String agent : agents) {
            tools.registerInstance(channel, agent, null, null, null);
        }
    }
}
