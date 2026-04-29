package io.quarkiverse.qhorus.ledger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkiverse.ledger.api.model.ActorType;
import io.quarkiverse.ledger.api.model.AttestationVerdict;
import io.quarkiverse.ledger.api.model.LedgerEntryType;
import io.quarkiverse.ledger.runtime.config.LedgerConfig;
import io.quarkiverse.ledger.runtime.model.LedgerAttestation;
import io.quarkiverse.ledger.runtime.model.LedgerEntry;
import io.quarkiverse.qhorus.api.message.MessageType;
import io.quarkiverse.qhorus.api.spi.CommitmentAttestationPolicy;
import io.quarkiverse.qhorus.api.spi.CommitmentAttestationPolicy.AttestationOutcome;
import io.quarkiverse.qhorus.api.spi.InstanceActorIdProvider;
import io.quarkiverse.qhorus.runtime.channel.Channel;
import io.quarkiverse.qhorus.runtime.ledger.LedgerWriteService;
import io.quarkiverse.qhorus.runtime.ledger.MessageLedgerEntry;
import io.quarkiverse.qhorus.runtime.ledger.MessageLedgerEntryRepository;
import io.quarkiverse.qhorus.runtime.message.Message;

/**
 * Pure unit tests for {@link LedgerWriteService#record} — no Quarkus runtime.
 * Uses a capturing stub repository and Mockito for LedgerConfig.
 *
 * <p>
 * Refs #102, #123, #124 — Epic #99.
 */
class LedgerWriteServiceTest {

    // ── Stub repository ───────────────────────────────────────────────────────

    static class CapturingRepo extends MessageLedgerEntryRepository {
        final List<MessageLedgerEntry> saved = new ArrayList<>();
        final List<LedgerAttestation> savedAttestations = new ArrayList<>();

        @Override
        public LedgerEntry save(final LedgerEntry entry) {
            final MessageLedgerEntry mle = (MessageLedgerEntry) entry;
            if (mle.id == null) {
                mle.id = UUID.randomUUID();
            }
            saved.add(mle);
            return mle;
        }

        @Override
        public Optional<LedgerEntry> findLatestBySubjectId(final UUID subjectId) {
            return saved.stream()
                    .filter(e -> e.subjectId.equals(subjectId))
                    .reduce((a, b) -> b)
                    .map(e -> (LedgerEntry) e);
        }

        @Override
        public Optional<MessageLedgerEntry> findLatestByCorrelationId(final UUID channelId,
                final String correlationId) {
            return saved.stream()
                    .filter(e -> channelId.equals(e.subjectId))
                    .filter(e -> correlationId.equals(e.correlationId))
                    .filter(e -> "COMMAND".equals(e.messageType) || "HANDOFF".equals(e.messageType))
                    .reduce((a, b) -> b);
        }

        @Override
        public LedgerAttestation saveAttestation(final LedgerAttestation attestation) {
            savedAttestations.add(attestation);
            return attestation;
        }
    }

    private CapturingRepo repo;
    private CommitmentAttestationPolicy attestationPolicy;
    private InstanceActorIdProvider actorIdProvider;
    private LedgerWriteService service;
    private LedgerConfig enabledConfig;

    @BeforeEach
    void setup() {
        repo = new CapturingRepo();
        enabledConfig = mock(LedgerConfig.class);
        when(enabledConfig.enabled()).thenReturn(true);

        // Default policy matching StoredCommitmentAttestationPolicy behaviour
        attestationPolicy = (type, actorId) -> switch (type) {
            case DONE -> Optional.of(new AttestationOutcome(AttestationVerdict.SOUND, 0.7, actorId, ActorType.AGENT));
            case FAILURE -> Optional.of(new AttestationOutcome(AttestationVerdict.FLAGGED, 0.6, "system", ActorType.SYSTEM));
            case DECLINE -> Optional.of(new AttestationOutcome(AttestationVerdict.FLAGGED, 0.4, "system", ActorType.SYSTEM));
            default -> Optional.empty();
        };
        actorIdProvider = id -> id; // identity — default behaviour

        service = new LedgerWriteService();
        service.repository = repo;
        service.config = enabledConfig;
        service.actorIdProvider = actorIdProvider;
        service.attestationPolicy = attestationPolicy;
        service.objectMapper = new ObjectMapper();
        // Note: NO service.commitmentStore — it is removed from LedgerWriteService
    }

    // ── Happy path — one test per message type ───────────────────────────────

    @Test
    void record_query_createsEntryWithCorrectTypeAndContent() {
        service.record(channel(), message("QUERY", "How many orders?", "agent:agent-a", null, null));

        assertEquals(1, repo.saved.size());
        MessageLedgerEntry e = repo.saved.get(0);
        assertEquals("QUERY", e.messageType);
        assertEquals(LedgerEntryType.COMMAND, e.entryType);
        assertEquals("agent:agent-a", e.actorId);
        assertEquals("How many orders?", e.content);
        assertNull(e.toolName);
    }

    @Test
    void record_command_createsEntryWithCorrectTypeAndContent() {
        service.record(channel(), message("COMMAND", "Generate the report", "agent:agent-a", "corr-1", null));

        MessageLedgerEntry e = repo.saved.get(0);
        assertEquals("COMMAND", e.messageType);
        assertEquals(LedgerEntryType.COMMAND, e.entryType);
        assertEquals("corr-1", e.correlationId);
        assertEquals("Generate the report", e.content);
    }

    @Test
    void record_response_createsEntry() {
        service.record(channel(), message("RESPONSE", "42 orders", "agent-b", "corr-1", null));

        MessageLedgerEntry e = repo.saved.get(0);
        assertEquals("RESPONSE", e.messageType);
        assertEquals(LedgerEntryType.EVENT, e.entryType);
        assertEquals("42 orders", e.content);
    }

    @Test
    void record_status_createsEntry() {
        service.record(channel(), message("STATUS", "Working...", "agent-b", "corr-1", null));

        assertEquals(1, repo.saved.size());
        assertEquals("STATUS", repo.saved.get(0).messageType);
    }

    @Test
    void record_decline_createsEntry() {
        service.record(channel(), message("DECLINE", "Out of scope", "agent-b", "corr-1", null));

        MessageLedgerEntry e = repo.saved.get(0);
        assertEquals("DECLINE", e.messageType);
        assertEquals(LedgerEntryType.EVENT, e.entryType);
        assertEquals("Out of scope", e.content);
    }

    @Test
    void record_handoff_createsEntry() {
        Message msg = message("HANDOFF", null, "agent:agent-a", "corr-1", null);
        msg.target = "instance:agent-c";
        service.record(channel(), msg);

        MessageLedgerEntry e = repo.saved.get(0);
        assertEquals("HANDOFF", e.messageType);
        assertEquals(LedgerEntryType.COMMAND, e.entryType);
        assertEquals("instance:agent-c", e.target);
    }

    @Test
    void record_done_createsEntry() {
        service.record(channel(), message("DONE", "Report delivered", "agent-b", "corr-1", null));

        MessageLedgerEntry e = repo.saved.get(0);
        assertEquals("DONE", e.messageType);
        assertEquals(LedgerEntryType.EVENT, e.entryType);
        assertEquals("Report delivered", e.content);
    }

    @Test
    void record_failure_createsEntry() {
        service.record(channel(), message("FAILURE", "DB error", "agent-b", "corr-1", null));

        MessageLedgerEntry e = repo.saved.get(0);
        assertEquals("FAILURE", e.messageType);
        assertEquals(LedgerEntryType.EVENT, e.entryType);
        assertEquals("DB error", e.content);
    }

    // ── EVENT telemetry ───────────────────────────────────────────────────────

    @Test
    void record_event_withValidJson_populatesTelemetry() {
        service.record(channel(),
                message("EVENT", "{\"tool_name\":\"read_file\",\"duration_ms\":42,\"token_count\":1200}",
                        "agent:agent-a", null, null));

        MessageLedgerEntry e = repo.saved.get(0);
        assertEquals("EVENT", e.messageType);
        assertEquals("read_file", e.toolName);
        assertEquals(42L, e.durationMs);
        assertEquals(1200L, e.tokenCount);
        assertNull(e.content);
    }

    @Test
    void record_event_missingToolName_entryStillWritten_toolNameNull() {
        service.record(channel(), message("EVENT", "{\"duration_ms\":10}", "agent:agent-a", null, null));

        assertEquals(1, repo.saved.size());
        assertNull(repo.saved.get(0).toolName);
        assertEquals(10L, repo.saved.get(0).durationMs);
    }

    @Test
    void record_event_missingDurationMs_entryStillWritten_durationNull() {
        service.record(channel(), message("EVENT", "{\"tool_name\":\"write_file\"}", "agent:agent-a", null, null));

        assertEquals(1, repo.saved.size());
        assertEquals("write_file", repo.saved.get(0).toolName);
        assertNull(repo.saved.get(0).durationMs);
    }

    @Test
    void record_event_malformedJson_entryStillWritten_allTelemetryNull() {
        service.record(channel(), message("EVENT", "not-valid-json", "agent:agent-a", null, null));

        assertEquals(1, repo.saved.size());
        assertNull(repo.saved.get(0).toolName);
        assertNull(repo.saved.get(0).durationMs);
    }

    @Test
    void record_event_nullContent_entryStillWritten() {
        service.record(channel(), message("EVENT", null, "agent:agent-a", null, null));

        assertEquals(1, repo.saved.size());
        assertNull(repo.saved.get(0).toolName);
    }

    @Test
    void record_event_emptyContent_entryStillWritten() {
        service.record(channel(), message("EVENT", "", "agent:agent-a", null, null));

        assertEquals(1, repo.saved.size());
    }

    // ── Causal chain — causedByEntryId ───────────────────────────────────────

    @Test
    void record_done_withMatchingCommand_setsCausedByEntryId() {
        UUID channelId = UUID.randomUUID();
        Channel ch = channel(channelId);
        MessageLedgerEntry cmdEntry = new MessageLedgerEntry();
        cmdEntry.id = UUID.randomUUID();
        cmdEntry.subjectId = channelId;
        cmdEntry.channelId = channelId;
        cmdEntry.messageType = "COMMAND";
        cmdEntry.correlationId = "corr-done";
        cmdEntry.sequenceNumber = 1;
        repo.saved.add(cmdEntry);

        service.record(ch, message("DONE", "Done", "agent-b", "corr-done", null));

        MessageLedgerEntry doneEntry = repo.saved.get(repo.saved.size() - 1);
        assertEquals(cmdEntry.id, doneEntry.causedByEntryId);
    }

    @Test
    void record_failure_withMatchingCommand_setsCausedByEntryId() {
        UUID channelId = UUID.randomUUID();
        Channel ch = channel(channelId);
        MessageLedgerEntry cmdEntry = new MessageLedgerEntry();
        cmdEntry.id = UUID.randomUUID();
        cmdEntry.subjectId = channelId;
        cmdEntry.messageType = "COMMAND";
        cmdEntry.correlationId = "corr-fail";
        cmdEntry.sequenceNumber = 1;
        repo.saved.add(cmdEntry);

        service.record(ch, message("FAILURE", "Timeout", "agent-b", "corr-fail", null));
        assertEquals(cmdEntry.id, repo.saved.get(repo.saved.size() - 1).causedByEntryId);
    }

    @Test
    void record_decline_withMatchingCommand_setsCausedByEntryId() {
        UUID channelId = UUID.randomUUID();
        Channel ch = channel(channelId);
        MessageLedgerEntry cmdEntry = new MessageLedgerEntry();
        cmdEntry.id = UUID.randomUUID();
        cmdEntry.subjectId = channelId;
        cmdEntry.messageType = "COMMAND";
        cmdEntry.correlationId = "corr-dec";
        cmdEntry.sequenceNumber = 1;
        repo.saved.add(cmdEntry);

        service.record(ch, message("DECLINE", "Out of scope", "agent-b", "corr-dec", null));
        assertEquals(cmdEntry.id, repo.saved.get(repo.saved.size() - 1).causedByEntryId);
    }

    @Test
    void record_done_noCorrelationId_causedByEntryIdNull() {
        service.record(channel(), message("DONE", "Done", "agent-b", null, null));

        assertNull(repo.saved.get(0).causedByEntryId);
    }

    @Test
    void record_done_noMatchingCommand_causedByEntryIdNull() {
        service.record(channel(), message("DONE", "Done", "agent-b", "corr-no-match", null));

        assertNull(repo.saved.get(0).causedByEntryId);
    }

    // ── Sequence numbering ────────────────────────────────────────────────────

    @Test
    void record_firstEntry_sequenceNumberIsOne() {
        service.record(channel(), message("COMMAND", "Go", "agent:agent-a", null, null));

        assertEquals(1, repo.saved.get(0).sequenceNumber);
    }

    @Test
    void record_threeEntries_sequenceNumbersIncrement() {
        UUID channelId = UUID.randomUUID();
        Channel ch = channel(channelId);
        service.record(ch, message("COMMAND", "Go", "agent:agent-a", null, null));
        service.record(ch, message("STATUS", "Working", "agent-b", null, null));
        service.record(ch, message("DONE", "Done", "agent-b", null, null));

        assertEquals(1, repo.saved.get(0).sequenceNumber);
        assertEquals(2, repo.saved.get(1).sequenceNumber);
        assertEquals(3, repo.saved.get(2).sequenceNumber);
    }

    // ── Base fields ───────────────────────────────────────────────────────────

    @Test
    void record_populatesBaseFields() {
        UUID channelId = UUID.randomUUID();
        Channel ch = channel(channelId);
        UUID commitmentId = UUID.randomUUID();
        Message msg = message("COMMAND", "Run audit", "agent:agent-a", "corr-x", commitmentId);
        service.record(ch, msg);

        MessageLedgerEntry e = repo.saved.get(0);
        assertEquals(channelId, e.channelId);
        assertEquals(channelId, e.subjectId);
        assertEquals(msg.id, e.messageId);
        assertEquals("agent:agent-a", e.actorId);
        assertEquals(ActorType.AGENT, e.actorType);
        assertEquals("corr-x", e.correlationId);
        assertEquals(commitmentId, e.commitmentId);
        assertNotNull(e.occurredAt);
    }

    // ── Ledger disabled ───────────────────────────────────────────────────────

    @Test
    void record_ledgerDisabled_writesNothing() {
        when(enabledConfig.enabled()).thenReturn(false);
        service.record(channel(), message("COMMAND", "Do it", "agent:agent-a", null, null));

        assertTrue(repo.saved.isEmpty());
    }

    // ── LedgerAttestation on terminal outcomes — Closes #123 ─────────────────

    @Test
    void record_done_withMatchingCommandEntry_writesSoundAttestation() {
        UUID channelId = UUID.randomUUID();
        Channel ch = channel(channelId);

        MessageLedgerEntry cmdEntry = new MessageLedgerEntry();
        cmdEntry.id = UUID.randomUUID();
        cmdEntry.subjectId = channelId;
        cmdEntry.channelId = channelId;
        cmdEntry.messageType = "COMMAND";
        cmdEntry.correlationId = "corr-attest-done";
        cmdEntry.sequenceNumber = 1;
        repo.saved.add(cmdEntry);

        service.record(ch, message("DONE", "Done!", "agent-b", "corr-attest-done", null));

        assertEquals(1, repo.savedAttestations.size());
        LedgerAttestation a = repo.savedAttestations.get(0);
        assertEquals(cmdEntry.id, a.ledgerEntryId);
        assertEquals(channelId, a.subjectId);
        assertEquals(AttestationVerdict.SOUND, a.verdict);
        assertEquals(0.7, a.confidence, 1e-9);
        assertEquals("agent-b", a.attestorId); // DONE sender's resolved actorId
        assertEquals(ActorType.AGENT, a.attestorType);
    }

    @Test
    void record_failure_withMatchingCommandEntry_writesFlaggedAttestation() {
        UUID channelId = UUID.randomUUID();
        Channel ch = channel(channelId);

        MessageLedgerEntry cmdEntry = new MessageLedgerEntry();
        cmdEntry.id = UUID.randomUUID();
        cmdEntry.subjectId = channelId;
        cmdEntry.channelId = channelId;
        cmdEntry.messageType = "COMMAND";
        cmdEntry.correlationId = "corr-attest-fail";
        cmdEntry.sequenceNumber = 1;
        repo.saved.add(cmdEntry);

        service.record(ch, message("FAILURE", "Timed out", "agent-b", "corr-attest-fail", null));

        assertEquals(1, repo.savedAttestations.size());
        LedgerAttestation a = repo.savedAttestations.get(0);
        assertEquals(AttestationVerdict.FLAGGED, a.verdict);
        assertEquals(0.6, a.confidence, 1e-9);
        assertEquals("system", a.attestorId);
        assertEquals(ActorType.SYSTEM, a.attestorType);
    }

    @Test
    void record_done_noMatchingCommandEntry_noAttestation_noException() {
        service.record(channel(), message("DONE", "Done", "agent-b", "corr-no-cmd", null));

        assertEquals(1, repo.saved.size()); // ledger entry still written
        assertTrue(repo.savedAttestations.isEmpty()); // no attestation — no command entry found
    }

    @Test
    void record_decline_withMatchingCommandEntry_writesFlaggedAttestation() {
        UUID channelId = UUID.randomUUID();
        Channel ch = channel(channelId);

        MessageLedgerEntry cmdEntry = new MessageLedgerEntry();
        cmdEntry.id = UUID.randomUUID();
        cmdEntry.subjectId = channelId;
        cmdEntry.channelId = channelId;
        cmdEntry.messageType = "COMMAND";
        cmdEntry.correlationId = "corr-dec";
        cmdEntry.sequenceNumber = 1;
        repo.saved.add(cmdEntry);

        service.record(ch, message("DECLINE", "Out of scope", "agent-b", "corr-dec", null));

        assertEquals(1, repo.savedAttestations.size());
        LedgerAttestation a = repo.savedAttestations.get(0);
        assertEquals(AttestationVerdict.FLAGGED, a.verdict);
        assertEquals(0.4, a.confidence, 1e-9);
        assertEquals("system", a.attestorId);
        assertEquals(ActorType.SYSTEM, a.attestorType);
    }

    @Test
    void record_handoff_doesNotWriteAttestation() {
        UUID channelId = UUID.randomUUID();
        Channel ch = channel(channelId);

        MessageLedgerEntry cmdEntry = new MessageLedgerEntry();
        cmdEntry.id = UUID.randomUUID();
        cmdEntry.subjectId = channelId;
        cmdEntry.messageType = "COMMAND";
        cmdEntry.correlationId = "corr-handoff";
        cmdEntry.sequenceNumber = 1;
        repo.saved.add(cmdEntry);

        Message msg = message("HANDOFF", null, "agent:agent-a", "corr-handoff", null);
        msg.target = "instance:agent-c";
        service.record(ch, msg);

        assertTrue(repo.savedAttestations.isEmpty());
    }

    @Test
    void record_status_doesNotWriteAttestation() {
        service.record(channel(), message("STATUS", "Working", "agent-b", "corr-1", null));
        assertTrue(repo.savedAttestations.isEmpty());
    }

    @Test
    void record_event_doesNotWriteAttestation() {
        service.record(channel(),
                message("EVENT", "{\"tool_name\":\"read\"}", "agent:agent-a", null, null));
        assertTrue(repo.savedAttestations.isEmpty());
    }

    @Test
    void record_done_nullCorrelationId_noAttestation() {
        service.record(channel(), message("DONE", "Done", "agent-b", null, null));
        assertTrue(repo.savedAttestations.isEmpty());
    }

    @Test
    void record_actorId_resolvedViaProvider() {
        service.actorIdProvider = id -> "claudony-worker-abc".equals(id) ? "claude:analyst@v1" : id;

        service.record(channel(), message("COMMAND", "Do it", "claudony-worker-abc", "corr-x", null));

        assertEquals("claude:analyst@v1", repo.saved.get(0).actorId);
    }

    @Test
    void record_done_resolvedActorId_usedInAttestation() {
        UUID channelId = UUID.randomUUID();
        Channel ch = channel(channelId);

        service.actorIdProvider = id -> "claude:analyst@v1";

        MessageLedgerEntry cmdEntry = new MessageLedgerEntry();
        cmdEntry.id = UUID.randomUUID();
        cmdEntry.subjectId = channelId;
        cmdEntry.messageType = "COMMAND";
        cmdEntry.correlationId = "corr-persona";
        cmdEntry.sequenceNumber = 1;
        repo.saved.add(cmdEntry);

        service.record(ch, message("DONE", "Done", "claudony-worker-abc", "corr-persona", null));

        assertEquals("claude:analyst@v1", repo.savedAttestations.get(0).attestorId);
    }

    @Test
    void record_customAttestationPolicy_empty_noAttestation() {
        service.attestationPolicy = (type, actorId) -> Optional.empty();

        UUID channelId = UUID.randomUUID();
        Channel ch = channel(channelId);
        MessageLedgerEntry cmdEntry = new MessageLedgerEntry();
        cmdEntry.id = UUID.randomUUID();
        cmdEntry.subjectId = channelId;
        cmdEntry.messageType = "COMMAND";
        cmdEntry.correlationId = "corr-suppressed";
        cmdEntry.sequenceNumber = 1;
        repo.saved.add(cmdEntry);

        service.record(ch, message("DONE", "Done", "agent-b", "corr-suppressed", null));

        assertEquals(2, repo.saved.size()); // pre-seeded COMMAND + the new DONE entry
        assertTrue(repo.savedAttestations.isEmpty());
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private Channel channel() {
        return channel(UUID.randomUUID());
    }

    private Channel channel(final UUID channelId) {
        Channel ch = new Channel();
        ch.id = channelId;
        ch.name = "test-channel-" + channelId;
        return ch;
    }

    private Message message(final String type, final String content, final String sender,
            final String correlationId, final UUID commitmentId) {
        Message msg = new Message();
        msg.id = (long) (Math.random() * 100000);
        msg.messageType = MessageType.valueOf(type);
        msg.content = content;
        msg.sender = sender;
        msg.correlationId = correlationId;
        msg.commitmentId = commitmentId;
        msg.createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return msg;
    }
}
