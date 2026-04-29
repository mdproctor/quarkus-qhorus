package io.quarkiverse.qhorus.ledger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.quarkiverse.ledger.api.model.ActorType;
import io.quarkiverse.ledger.api.model.AttestationVerdict;
import io.quarkiverse.qhorus.api.message.MessageType;
import io.quarkiverse.qhorus.api.spi.CommitmentAttestationPolicy;
import io.quarkiverse.qhorus.api.spi.CommitmentAttestationPolicy.AttestationOutcome;
import io.quarkiverse.qhorus.runtime.config.QhorusConfig;
import io.quarkiverse.qhorus.runtime.ledger.StoredCommitmentAttestationPolicy;

class CommitmentAttestationPolicyTest {

    @Test
    void attestationOutcome_fields_accessible() {
        AttestationOutcome o = new AttestationOutcome(
                AttestationVerdict.SOUND, 0.7, "agent-a", ActorType.AGENT);
        assertEquals(AttestationVerdict.SOUND, o.verdict());
        assertEquals(0.7, o.confidence(), 1e-9);
        assertEquals("agent-a", o.attestorId());
        assertEquals(ActorType.AGENT, o.attestorType());
    }

    @Test
    void functionalInterface_lambdaCompiles() {
        CommitmentAttestationPolicy p = (type, actorId) -> Optional
                .of(new AttestationOutcome(AttestationVerdict.SOUND, 1.0, actorId, ActorType.AGENT));
        Optional<AttestationOutcome> result = p.attestationFor(MessageType.DONE, "agent-a");
        assertTrue(result.isPresent());
    }

    @Test
    void policy_canReturnEmpty_forUnwantedTypes() {
        CommitmentAttestationPolicy p = (type, actorId) -> Optional.empty();
        assertTrue(p.attestationFor(MessageType.DONE, "agent-a").isEmpty());
    }

    // ── StoredCommitmentAttestationPolicy tests ──

    static StoredCommitmentAttestationPolicy policyWithDefaults() {
        QhorusConfig.Attestation att = mock(QhorusConfig.Attestation.class);
        when(att.doneConfidence()).thenReturn(0.7);
        when(att.failureConfidence()).thenReturn(0.6);
        when(att.declineConfidence()).thenReturn(0.4);
        QhorusConfig cfg = mock(QhorusConfig.class);
        when(cfg.attestation()).thenReturn(att);
        StoredCommitmentAttestationPolicy p = new StoredCommitmentAttestationPolicy();
        p.config = cfg;
        return p;
    }

    @Test
    void stored_done_returnsSound_withDoneConfidence_fromSender() {
        var result = policyWithDefaults().attestationFor(MessageType.DONE, "agent-a");
        assertTrue(result.isPresent());
        assertEquals(AttestationVerdict.SOUND, result.get().verdict());
        assertEquals(0.7, result.get().confidence(), 1e-9);
        assertEquals("agent-a", result.get().attestorId());
        assertEquals(ActorType.AGENT, result.get().attestorType());
    }

    @Test
    void stored_failure_returnsFlagged_withFailureConfidence_fromSystem() {
        var result = policyWithDefaults().attestationFor(MessageType.FAILURE, "agent-b");
        assertTrue(result.isPresent());
        assertEquals(AttestationVerdict.FLAGGED, result.get().verdict());
        assertEquals(0.6, result.get().confidence(), 1e-9);
        assertEquals("system", result.get().attestorId());
        assertEquals(ActorType.SYSTEM, result.get().attestorType());
    }

    @Test
    void stored_decline_returnsFlagged_withDeclineConfidence_fromSystem() {
        var result = policyWithDefaults().attestationFor(MessageType.DECLINE, "agent-b");
        assertTrue(result.isPresent());
        assertEquals(AttestationVerdict.FLAGGED, result.get().verdict());
        assertEquals(0.4, result.get().confidence(), 1e-9);
        assertEquals("system", result.get().attestorId());
        assertEquals(ActorType.SYSTEM, result.get().attestorType());
    }

    @Test
    void stored_event_returnsEmpty() {
        assertTrue(policyWithDefaults().attestationFor(MessageType.EVENT, "agent-a").isEmpty());
    }

    @Test
    void stored_status_returnsEmpty() {
        assertTrue(policyWithDefaults().attestationFor(MessageType.STATUS, "agent-a").isEmpty());
    }

    @Test
    void stored_handoff_returnsEmpty() {
        assertTrue(policyWithDefaults().attestationFor(MessageType.HANDOFF, "agent-a").isEmpty());
    }

    @Test
    void stored_query_returnsEmpty() {
        assertTrue(policyWithDefaults().attestationFor(MessageType.QUERY, "agent-a").isEmpty());
    }

    @Test
    void stored_command_returnsEmpty() {
        assertTrue(policyWithDefaults().attestationFor(MessageType.COMMAND, "agent-a").isEmpty());
    }

    @Test
    void stored_response_returnsEmpty() {
        assertTrue(policyWithDefaults().attestationFor(MessageType.RESPONSE, "agent-a").isEmpty());
    }

    @Test
    void stored_customConfidence_usedFromConfig() {
        QhorusConfig.Attestation att = mock(QhorusConfig.Attestation.class);
        when(att.doneConfidence()).thenReturn(0.9);
        when(att.failureConfidence()).thenReturn(0.6);
        when(att.declineConfidence()).thenReturn(0.4);
        QhorusConfig cfg = mock(QhorusConfig.class);
        when(cfg.attestation()).thenReturn(att);
        StoredCommitmentAttestationPolicy p = new StoredCommitmentAttestationPolicy();
        p.config = cfg;
        var result = p.attestationFor(MessageType.DONE, "agent-x");
        assertEquals(0.9, result.get().confidence(), 1e-9);
    }
}
