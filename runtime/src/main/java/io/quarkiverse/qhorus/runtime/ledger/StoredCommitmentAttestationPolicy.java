package io.quarkiverse.qhorus.runtime.ledger;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkiverse.ledger.api.model.ActorType;
import io.quarkiverse.ledger.api.model.AttestationVerdict;
import io.quarkiverse.qhorus.api.message.MessageType;
import io.quarkiverse.qhorus.api.spi.CommitmentAttestationPolicy;
import io.quarkiverse.qhorus.runtime.config.QhorusConfig;
import io.quarkus.arc.DefaultBean;

/**
 * Default {@link CommitmentAttestationPolicy} that reads confidence values from
 * {@link QhorusConfig.Attestation}.
 *
 * <p>
 * Verdict and attestorId mappings:
 * <ul>
 * <li>DONE → SOUND, confidence from {@code quarkus.qhorus.attestation.done-confidence} (default 0.7),
 * attestorId = the resolved actorId (COMMAND sender)</li>
 * <li>FAILURE → FLAGGED, confidence from {@code quarkus.qhorus.attestation.failure-confidence} (default 0.6),
 * attestorId = "system"</li>
 * <li>DECLINE → FLAGGED, confidence from {@code quarkus.qhorus.attestation.decline-confidence} (default 0.4),
 * attestorId = "system"</li>
 * <li>All other types → empty (no attestation)</li>
 * </ul>
 *
 * <p>
 * Confidence semantics: the Beta trust score update is weighted by
 * {@code recencyWeight × confidence}. Values below 1.0 reflect epistemic caution —
 * a single message outcome is not fully diagnostic of trustworthiness. DECLINE
 * receives the lowest confidence because refusing may be appropriate professional judgment.
 *
 * <p>
 * Refs #123.
 */
@DefaultBean
@ApplicationScoped
public class StoredCommitmentAttestationPolicy implements CommitmentAttestationPolicy {

    @Inject
    public QhorusConfig config;

    @Override
    public Optional<AttestationOutcome> attestationFor(final MessageType terminalType,
            final String resolvedActorId) {
        return switch (terminalType) {
            case DONE -> Optional.of(new AttestationOutcome(
                    AttestationVerdict.SOUND,
                    config.attestation().doneConfidence(),
                    resolvedActorId,
                    ActorType.AGENT));
            case FAILURE -> Optional.of(new AttestationOutcome(
                    AttestationVerdict.FLAGGED,
                    config.attestation().failureConfidence(),
                    "system",
                    ActorType.SYSTEM));
            case DECLINE -> Optional.of(new AttestationOutcome(
                    AttestationVerdict.FLAGGED,
                    config.attestation().declineConfidence(),
                    "system",
                    ActorType.SYSTEM));
            default -> Optional.empty();
        };
    }
}
