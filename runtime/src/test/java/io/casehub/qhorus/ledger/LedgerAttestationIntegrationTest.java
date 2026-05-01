package io.casehub.qhorus.ledger;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.casehub.ledger.api.model.AttestationVerdict;
import io.casehub.ledger.api.model.CapabilityTag;
import io.casehub.ledger.runtime.model.LedgerAttestation;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.ledger.MessageLedgerEntry;
import io.casehub.qhorus.runtime.ledger.MessageLedgerEntryRepository;
import io.casehub.qhorus.runtime.mcp.QhorusMcpTools;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests proving that the tx-boundary fix in LedgerWriteService is real:
 * DONE/FAILURE/DECLINE messages correctly write attestations even though commitment state
 * is updated in the outer transaction (not yet committed when REQUIRES_NEW fires).
 * Fixed by deriving verdict from MessageType, not CommitmentStore.
 *
 * <p>
 * Also proves DefaultInstanceActorIdProvider is identity (message.sender == entry.actorId).
 *
 * <p>
 * Refs #123, #124.
 */
@QuarkusTest
@TestTransaction
class LedgerAttestationIntegrationTest {

    @Inject
    QhorusMcpTools tools;

    @Inject
    MessageLedgerEntryRepository ledgerRepo;

    @Test
    void done_message_writes_sound_attestation_on_command_entry() {
        String channelName = "attest-done-" + System.nanoTime();
        String corrId = UUID.randomUUID().toString();
        setup(channelName, "agent-a", "agent-b");

        // COMMAND — creates the ledger entry the attestation will be written on
        tools.sendMessage(channelName, "agent-a", "command", "Run audit", corrId, null, null, null, null);

        // DONE — triggers SOUND attestation on the COMMAND's ledger entry
        tools.sendMessage(channelName, "agent-b", "done", "Audit complete", corrId, null, null, null, null);

        UUID channelId = channelId(channelName);
        List<MessageLedgerEntry> entries = ledgerRepo.findAllByCorrelationId(channelId, corrId);
        MessageLedgerEntry commandEntry = entries.stream()
                .filter(e -> "COMMAND".equals(e.messageType))
                .findFirst()
                .orElseThrow(() -> new AssertionError("COMMAND entry not found"));

        List<LedgerAttestation> attestations = ledgerRepo.findAttestationsByEntryId(commandEntry.id);
        assertEquals(1, attestations.size(), "Expected exactly one attestation on the COMMAND entry");

        LedgerAttestation att = attestations.get(0);
        assertEquals(AttestationVerdict.SOUND, att.verdict);
        assertEquals(0.7, att.confidence, 0.001);
        assertEquals("agent-b", att.attestorId); // DONE sender (default provider = identity)
        assertNotNull(att.occurredAt);
    }

    @Test
    void failure_message_writes_flagged_attestation_on_command_entry() {
        String channelName = "attest-fail-" + System.nanoTime();
        String corrId = UUID.randomUUID().toString();
        setup(channelName, "agent-a", "agent-b");

        tools.sendMessage(channelName, "agent-a", "command", "Run analysis", corrId, null, null, null, null);
        tools.sendMessage(channelName, "agent-b", "failure", "Could not access data", corrId, null, null, null, null);

        UUID channelId = channelId(channelName);
        MessageLedgerEntry commandEntry = ledgerRepo.findAllByCorrelationId(channelId, corrId).stream()
                .filter(e -> "COMMAND".equals(e.messageType)).findFirst().orElseThrow();
        List<LedgerAttestation> attestations = ledgerRepo.findAttestationsByEntryId(commandEntry.id);
        assertEquals(1, attestations.size());
        assertEquals(AttestationVerdict.FLAGGED, attestations.get(0).verdict);
        assertEquals(0.6, attestations.get(0).confidence, 0.001);
        assertEquals("system", attestations.get(0).attestorId);
    }

    @Test
    void decline_message_writes_flagged_attestation_with_lower_confidence() {
        String channelName = "attest-dec-" + System.nanoTime();
        String corrId = UUID.randomUUID().toString();
        setup(channelName, "agent-a", "agent-b");

        tools.sendMessage(channelName, "agent-a", "command", "Do something", corrId, null, null, null, null);
        tools.sendMessage(channelName, "agent-b", "decline", "Outside my scope", corrId, null, null, null, null);

        UUID channelId = channelId(channelName);
        MessageLedgerEntry commandEntry = ledgerRepo.findAllByCorrelationId(channelId, corrId).stream()
                .filter(e -> "COMMAND".equals(e.messageType)).findFirst().orElseThrow();
        List<LedgerAttestation> attestations = ledgerRepo.findAttestationsByEntryId(commandEntry.id);
        assertEquals(1, attestations.size());
        assertEquals(AttestationVerdict.FLAGGED, attestations.get(0).verdict);
        assertEquals(0.4, attestations.get(0).confidence, 0.001);
    }

    @Test
    void status_message_writes_no_attestation() {
        String channelName = "attest-status-" + System.nanoTime();
        String corrId = UUID.randomUUID().toString();
        setup(channelName, "agent-a");

        tools.sendMessage(channelName, "agent-a", "command", "Long task", corrId, null, null, null, null);
        tools.sendMessage(channelName, "agent-a", "status", "Still working", corrId, null, null, null, null);

        UUID channelId = channelId(channelName);
        MessageLedgerEntry commandEntry = ledgerRepo.findAllByCorrelationId(channelId, corrId).stream()
                .filter(e -> "COMMAND".equals(e.messageType)).findFirst().orElseThrow();
        assertTrue(ledgerRepo.findAttestationsByEntryId(commandEntry.id).isEmpty(),
                "STATUS should not trigger attestation");
    }

    @Test
    void done_without_prior_command_writes_no_attestation_no_exception() {
        String channelName = "attest-orphan-" + System.nanoTime();
        String corrId = UUID.randomUUID().toString();
        setup(channelName, "agent-b");

        // DONE with no matching COMMAND — must not throw, no attestation
        assertDoesNotThrow(() -> tools.sendMessage(channelName, "agent-b", "done", "Orphan done", corrId, null, null, null, null));

        UUID channelId = channelId(channelName);
        MessageLedgerEntry doneEntry = ledgerRepo.findAllByCorrelationId(channelId, corrId).stream()
                .filter(e -> "DONE".equals(e.messageType)).findFirst().orElseThrow();
        assertTrue(ledgerRepo.findAttestationsByEntryId(doneEntry.id).isEmpty());
    }

    @Test
    void done_with_capability_in_command_sets_capabilityTag_on_attestation() {
        String channelName = "attest-cap-" + System.nanoTime();
        String corrId = UUID.randomUUID().toString();
        setup(channelName, "agent-a", "agent-b");

        tools.sendMessage(channelName, "agent-a", "command",
                "{\"capability\":\"code-review\",\"task\":\"Review PR\"}", corrId, null, null, null, null);
        tools.sendMessage(channelName, "agent-b", "done", "Review done", corrId, null, null, null, null);

        UUID channelId = channelId(channelName);
        MessageLedgerEntry commandEntry = ledgerRepo.findAllByCorrelationId(channelId, corrId).stream()
                .filter(e -> "COMMAND".equals(e.messageType)).findFirst().orElseThrow();
        List<LedgerAttestation> attestations = ledgerRepo.findAttestationsByEntryId(commandEntry.id);
        assertEquals(1, attestations.size());
        assertEquals("code-review", attestations.get(0).capabilityTag);
    }

    @Test
    void done_with_no_capability_in_command_defaults_to_global() {
        String channelName = "attest-nocap-" + System.nanoTime();
        String corrId = UUID.randomUUID().toString();
        setup(channelName, "agent-a", "agent-b");

        tools.sendMessage(channelName, "agent-a", "command", "Plain text command", corrId, null, null, null, null);
        tools.sendMessage(channelName, "agent-b", "done", "Done", corrId, null, null, null, null);

        UUID channelId = channelId(channelName);
        MessageLedgerEntry commandEntry = ledgerRepo.findAllByCorrelationId(channelId, corrId).stream()
                .filter(e -> "COMMAND".equals(e.messageType)).findFirst().orElseThrow();
        List<LedgerAttestation> attestations = ledgerRepo.findAttestationsByEntryId(commandEntry.id);
        assertEquals(1, attestations.size());
        assertEquals(CapabilityTag.GLOBAL, attestations.get(0).capabilityTag);
    }

    @Test
    void actorId_is_resolved_via_default_provider_identity() {
        String channelName = "attest-actorid-" + System.nanoTime();
        setup(channelName, "agent-xyz");
        tools.sendMessage(channelName, "agent-xyz", "status", "hello", null, null, null, null, null);

        UUID channelId = channelId(channelName);
        List<MessageLedgerEntry> entries = ledgerRepo.findByActorIdInChannel(channelId, "agent-xyz", 10);
        assertFalse(entries.isEmpty(), "Entry should be findable by actorId");
        assertEquals("agent-xyz", entries.get(0).actorId);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void setup(final String channel, final String... agents) {
        tools.createChannel(channel, "Attestation test channel", "APPEND", null, null, null, null, null, null);
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
}
