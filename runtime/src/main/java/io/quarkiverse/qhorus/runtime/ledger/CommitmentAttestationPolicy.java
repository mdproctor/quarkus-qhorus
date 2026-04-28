package io.quarkiverse.qhorus.runtime.ledger;

import java.util.Optional;

import io.quarkiverse.ledger.runtime.model.ActorType;
import io.quarkiverse.ledger.runtime.model.AttestationVerdict;
import io.quarkiverse.qhorus.runtime.message.MessageType;

/**
 * Determines what {@link io.quarkiverse.ledger.runtime.model.LedgerAttestation} to write
 * when a terminal message (DONE, FAILURE, DECLINE) discharges a commitment.
 *
 * <p>
 * Called in {@link LedgerWriteService#record} for DONE, FAILURE, and DECLINE message types
 * when a prior COMMAND ledger entry exists for the same correlationId. The returned
 * {@link AttestationOutcome} is written against the COMMAND's {@code MessageLedgerEntry}.
 *
 * <p>
 * Returning {@link Optional#empty()} suppresses attestation writing for that message type.
 *
 * <p>
 * Default implementation: {@link StoredCommitmentAttestationPolicy}.
 * Replace with {@code @Alternative @Priority} for custom verdict mappings.
 *
 * <p>
 * Refs #123.
 */
@FunctionalInterface
public interface CommitmentAttestationPolicy {

    /**
     * Determine what attestation to write for a terminal commitment outcome.
     *
     * @param terminalType the message type that discharged the commitment
     *        (callers only invoke for DONE, FAILURE, DECLINE)
     * @param resolvedActorId the ledger actorId of the message sender, already resolved
     *        through {@link InstanceActorIdProvider}
     * @return the attestation to write, or empty to write no attestation
     */
    Optional<AttestationOutcome> attestationFor(MessageType terminalType, String resolvedActorId);

    /**
     * Attestation fields to write on the originating COMMAND's {@code MessageLedgerEntry}.
     *
     * @param verdict SOUND for positive outcomes, FLAGGED for negative; feeds the
     *        Bayesian Beta trust score in quarkus-ledger
     * @param confidence strength of evidence in [0.0, 1.0]; the Beta update is weighted by
     *        {@code recencyWeight × confidence} — higher values move the score more
     * @param attestorId the actor making the attestation (sender for DONE, "system" for others)
     * @param attestorType AGENT or SYSTEM
     */
    record AttestationOutcome(
            AttestationVerdict verdict,
            double confidence,
            String attestorId,
            ActorType attestorType) {
    }
}
