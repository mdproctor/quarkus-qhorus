package io.quarkiverse.qhorus.ledger;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.ledger.runtime.service.LedgerHashChain;
import io.quarkiverse.qhorus.runtime.ledger.AgentMessageLedgerEntry;
import io.quarkiverse.qhorus.runtime.ledger.AgentMessageLedgerEntryRepository;
import io.quarkiverse.qhorus.runtime.mcp.QhorusMcpTools;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for the ledger capture triggered by EVENT messages.
 *
 * <p>
 * Verifies that posting an EVENT via {@code send_message} with a structured
 * JSON payload creates a corresponding {@link AgentMessageLedgerEntry} with
 * correct field values, per-channel sequence numbering, and hash chain integrity.
 *
 * <p>
 * RED-phase: will not compile until {@link AgentMessageLedgerEntry},
 * {@link AgentMessageLedgerEntryRepository}, and the capture wiring are created.
 *
 * <p>
 * Refs #52, Epic #50.
 */
@QuarkusTest
@TestTransaction
class AgentLedgerCaptureTest {

    @Inject
    QhorusMcpTools tools;

    @Inject
    AgentMessageLedgerEntryRepository ledgerRepo;

    // =========================================================================
    // Happy path — structured event payload creates ledger entry
    // =========================================================================

    @Test
    void sendEvent_withStructuredPayload_createsLedgerEntry() {
        tools.createChannel("alc-capture-1", "LAST_WRITE", null, null, null, null);
        tools.registerInstance("alc-capture-1", "agent-1", null, null, null, null, null);

        final String payload = """
                {"tool_name":"read_file","duration_ms":42,"token_count":1200}
                """;
        tools.sendMessage("alc-capture-1", "agent-1", "event", payload, null, null, null, null);

        final List<AgentMessageLedgerEntry> entries = ledgerRepo.findByChannelId(
                channelIdFor("alc-capture-1"));

        assertEquals(1, entries.size());
        final AgentMessageLedgerEntry e = entries.get(0);
        assertEquals("read_file", e.toolName);
        assertEquals(42L, e.durationMs);
        assertEquals(1200L, e.tokenCount);
        assertEquals("agent-1", e.actorId);
    }

    @Test
    void sendEvent_setsSubjectIdToChannelId() {
        tools.createChannel("alc-capture-2", "LAST_WRITE", null, null, null, null);
        tools.registerInstance("alc-capture-2", "agent-1", null, null, null, null, null);

        tools.sendMessage("alc-capture-2", "agent-1", "event",
                "{\"tool_name\":\"write_file\",\"duration_ms\":10}", null, null, null, null);

        final List<AgentMessageLedgerEntry> entries = ledgerRepo.findByChannelId(
                channelIdFor("alc-capture-2"));

        assertEquals(1, entries.size());
        assertEquals(channelIdFor("alc-capture-2"), entries.get(0).subjectId);
        assertEquals(channelIdFor("alc-capture-2"), entries.get(0).channelId);
    }

    @Test
    void sendEvent_withOptionalContextRefs_storesThem() {
        tools.createChannel("alc-capture-3", "LAST_WRITE", null, null, null, null);
        tools.registerInstance("alc-capture-3", "agent-1", null, null, null, null, null);

        final String payload = """
                {
                  "tool_name":"analyze",
                  "duration_ms":300,
                  "context_refs":["msg-17","artefact-abc"],
                  "source_entity":{"id":"case-1","type":"CaseHub:Case","system":"casehub"}
                }
                """;
        tools.sendMessage("alc-capture-3", "agent-1", "event", payload, null, null, null, null);

        final List<AgentMessageLedgerEntry> entries = ledgerRepo.findByChannelId(
                channelIdFor("alc-capture-3"));

        assertEquals(1, entries.size());
        assertNotNull(entries.get(0).contextRefs);
        assertNotNull(entries.get(0).sourceEntity);
    }

    @Test
    void sendEvent_correlationIdPropagatedToLedger() {
        tools.createChannel("alc-capture-4", "LAST_WRITE", null, null, null, null);
        tools.registerInstance("alc-capture-4", "agent-1", null, null, null, null, null);

        tools.sendMessage("alc-capture-4", "agent-1", "event",
                "{\"tool_name\":\"plan\",\"duration_ms\":50}",
                "trace-abc-123", null, null, null);

        final List<AgentMessageLedgerEntry> entries = ledgerRepo.findByChannelId(
                channelIdFor("alc-capture-4"));

        // correlationId is now in ObservabilitySupplement; verify via supplementJson or lazy accessor
        final var obs = entries.get(0).observability();
        assertTrue(obs.isPresent(), "ObservabilitySupplement should be present");
        assertEquals("trace-abc-123", obs.get().correlationId);
    }

    // =========================================================================
    // Sequence numbering — per channel
    // =========================================================================

    @Test
    void multipleEvents_sequenceNumbersIncrement() {
        tools.createChannel("alc-seq-1", "LAST_WRITE", null, null, null, null);
        tools.registerInstance("alc-seq-1", "agent-1", null, null, null, null, null);

        final String payload = "{\"tool_name\":\"step\",\"duration_ms\":10}";
        tools.sendMessage("alc-seq-1", "agent-1", "event", payload, null, null, null, null);
        tools.sendMessage("alc-seq-1", "agent-1", "event", payload, null, null, null, null);
        tools.sendMessage("alc-seq-1", "agent-1", "event", payload, null, null, null, null);

        final List<AgentMessageLedgerEntry> entries = ledgerRepo.findByChannelId(
                channelIdFor("alc-seq-1"));

        assertEquals(3, entries.size());
        assertEquals(1, entries.get(0).sequenceNumber);
        assertEquals(2, entries.get(1).sequenceNumber);
        assertEquals(3, entries.get(2).sequenceNumber);
    }

    @Test
    void sequenceNumbers_independentAcrossChannels() {
        tools.createChannel("alc-seq-2a", "LAST_WRITE", null, null, null, null);
        tools.createChannel("alc-seq-2b", "LAST_WRITE", null, null, null, null);
        tools.registerInstance("alc-seq-2a", "agent-1", null, null, null, null, null);
        tools.registerInstance("alc-seq-2b", "agent-2", null, null, null, null, null);

        final String payload = "{\"tool_name\":\"step\",\"duration_ms\":5}";
        tools.sendMessage("alc-seq-2a", "agent-1", "event", payload, null, null, null, null);
        tools.sendMessage("alc-seq-2b", "agent-2", "event", payload, null, null, null, null);

        final List<AgentMessageLedgerEntry> a = ledgerRepo.findByChannelId(channelIdFor("alc-seq-2a"));
        final List<AgentMessageLedgerEntry> b = ledgerRepo.findByChannelId(channelIdFor("alc-seq-2b"));

        assertEquals(1, a.get(0).sequenceNumber);
        assertEquals(1, b.get(0).sequenceNumber);
    }

    // =========================================================================
    // Hash chain
    // =========================================================================

    @Test
    void hashChain_firstEntryHasNullPreviousHash() {
        tools.createChannel("alc-hash-1", "LAST_WRITE", null, null, null, null);
        tools.registerInstance("alc-hash-1", "agent-1", null, null, null, null, null);

        tools.sendMessage("alc-hash-1", "agent-1", "event",
                "{\"tool_name\":\"init\",\"duration_ms\":1}", null, null, null, null);

        final List<AgentMessageLedgerEntry> entries = ledgerRepo.findByChannelId(
                channelIdFor("alc-hash-1"));

        assertNull(entries.get(0).previousHash);
        assertNotNull(entries.get(0).digest);
    }

    @Test
    void hashChain_secondEntryLinkedToFirst() {
        tools.createChannel("alc-hash-2", "LAST_WRITE", null, null, null, null);
        tools.registerInstance("alc-hash-2", "agent-1", null, null, null, null, null);

        final String payload = "{\"tool_name\":\"step\",\"duration_ms\":5}";
        tools.sendMessage("alc-hash-2", "agent-1", "event", payload, null, null, null, null);
        tools.sendMessage("alc-hash-2", "agent-1", "event", payload, null, null, null, null);

        final List<AgentMessageLedgerEntry> entries = ledgerRepo.findByChannelId(
                channelIdFor("alc-hash-2"));

        assertEquals(entries.get(0).digest, entries.get(1).previousHash);
    }

    @Test
    void hashChain_threeEntries_chainIsValid() {
        tools.createChannel("alc-hash-3", "LAST_WRITE", null, null, null, null);
        tools.registerInstance("alc-hash-3", "agent-1", null, null, null, null, null);

        final String payload = "{\"tool_name\":\"step\",\"duration_ms\":5}";
        tools.sendMessage("alc-hash-3", "agent-1", "event", payload, null, null, null, null);
        tools.sendMessage("alc-hash-3", "agent-1", "event", payload, null, null, null, null);
        tools.sendMessage("alc-hash-3", "agent-1", "event", payload, null, null, null, null);

        final List<AgentMessageLedgerEntry> entries = ledgerRepo.findByChannelId(
                channelIdFor("alc-hash-3"));

        assertTrue(LedgerHashChain.verify(entries));
    }

    // =========================================================================
    // Graceful skip — missing mandatory fields
    // =========================================================================

    @Test
    void sendEvent_missingToolName_noLedgerEntry() {
        tools.createChannel("alc-skip-1", "LAST_WRITE", null, null, null, null);
        tools.registerInstance("alc-skip-1", "agent-1", null, null, null, null, null);

        // payload missing tool_name
        tools.sendMessage("alc-skip-1", "agent-1", "event",
                "{\"duration_ms\":10}", null, null, null, null);

        final List<AgentMessageLedgerEntry> entries = ledgerRepo.findByChannelId(
                channelIdFor("alc-skip-1"));

        assertTrue(entries.isEmpty(), "Expected no ledger entry for invalid EVENT payload");
    }

    @Test
    void sendEvent_missingDurationMs_noLedgerEntry() {
        tools.createChannel("alc-skip-2", "LAST_WRITE", null, null, null, null);
        tools.registerInstance("alc-skip-2", "agent-1", null, null, null, null, null);

        // payload missing duration_ms
        tools.sendMessage("alc-skip-2", "agent-1", "event",
                "{\"tool_name\":\"write_file\"}", null, null, null, null);

        final List<AgentMessageLedgerEntry> entries = ledgerRepo.findByChannelId(
                channelIdFor("alc-skip-2"));

        assertTrue(entries.isEmpty(), "Expected no ledger entry for invalid EVENT payload");
    }

    // =========================================================================
    // Non-EVENT messages do NOT produce ledger entries
    // =========================================================================

    @Test
    void nonEventMessage_doesNotCreateLedgerEntry() {
        tools.createChannel("alc-nonevent-1", "LAST_WRITE", null, null, null, null);
        tools.registerInstance("alc-nonevent-1", "agent-1", null, null, null, null, null);

        tools.sendMessage("alc-nonevent-1", "agent-1", "request",
                "Do something", null, null, null, null);
        tools.sendMessage("alc-nonevent-1", "agent-1", "status",
                "Working on it", null, null, null, null);

        final List<AgentMessageLedgerEntry> entries = ledgerRepo.findByChannelId(
                channelIdFor("alc-nonevent-1"));

        assertTrue(entries.isEmpty(), "Non-EVENT messages must not create ledger entries");
    }

    // =========================================================================
    // Fixture helper
    // =========================================================================

    private java.util.UUID channelIdFor(final String channelName) {
        return io.quarkiverse.qhorus.runtime.channel.Channel.<io.quarkiverse.qhorus.runtime.channel.Channel> find("name",
                channelName)
                .firstResultOptional()
                .map(ch -> ch.id)
                .orElseThrow(() -> new IllegalStateException("Channel not found: " + channelName));
    }
}
