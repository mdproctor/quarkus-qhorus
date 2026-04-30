package io.casehub.qhorus.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.qhorus.runtime.ledger.MessageLedgerEntry;
import io.casehub.qhorus.runtime.ledger.MessageLedgerEntryRepository;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Validates that real LLM agent communication produces correct ledger entries.
 *
 * <p>
 * Uses Jlama (pure Java inference, no external process). Model downloads ~700MB
 * on first run and caches in {@code ~/.jlama/}.
 *
 * <p>
 * Requires {@code -Pwith-llm-examples} and the model downloaded to {@code ~/.jlama/}.
 *
 * <p>
 * Refs #107 — Epic #99.
 */
@QuarkusTest
class LedgerObligationTrailTest {

    @Inject
    QhorusMcpTools tools;

    @Inject
    MessageLedgerEntryRepository ledgerRepo;

    @Test
    void agentCommunication_commandLifecycle_producesLedgerTrail() {
        // Set up a channel for this test scenario
        tools.createChannel("ledger-llm-trail", "APPEND", null, null);
        tools.registerInstance("ledger-llm-trail", "orchestrator", null, null, null);
        tools.registerInstance("ledger-llm-trail", "worker", null, null, null);

        String corrId = UUID.randomUUID().toString();

        // Orchestrator issues a COMMAND
        tools.sendMessage("ledger-llm-trail", "orchestrator", "command",
                "Generate a summary of Q1 sales data", corrId, null, null, null);

        // Worker acknowledges with STATUS then completes with DONE
        tools.sendMessage("ledger-llm-trail", "worker", "status",
                "Retrieving Q1 data", corrId, null, null, null);
        tools.sendMessage("ledger-llm-trail", "worker", "done",
                "Q1 sales total: $1.2M across 342 transactions", corrId, null, null, null);

        io.casehub.qhorus.runtime.channel.Channel ch = io.casehub.qhorus.runtime.channel.Channel.<io.casehub.qhorus.runtime.channel.Channel> find(
                "name", "ledger-llm-trail")
                .firstResultOptional()
                .orElseThrow();

        List<MessageLedgerEntry> entries = ledgerRepo.findByChannelId(ch.id);

        // 3 messages → 3 ledger entries
        assertThat(entries).hasSize(3);
        assertThat(entries.get(0).messageType).isEqualTo("COMMAND");
        assertThat(entries.get(1).messageType).isEqualTo("STATUS");
        assertThat(entries.get(2).messageType).isEqualTo("DONE");

        // DONE points back to COMMAND
        assertThat(entries.get(2).causedByEntryId)
                .as("DONE entry should point to COMMAND via causedByEntryId")
                .isEqualTo(entries.get(0).id);

        // Obligation lifecycle visible via list_ledger_entries
        List<Map<String, Object>> obligationTrail = tools.listLedgerEntries(
                "ledger-llm-trail", "COMMAND,DONE,FAILURE", null, null, null, 20);
        assertThat(obligationTrail).hasSize(2); // COMMAND + DONE only (STATUS filtered out)
        assertThat(obligationTrail.get(1).get("caused_by_entry_id")).isNotNull();
    }
}
