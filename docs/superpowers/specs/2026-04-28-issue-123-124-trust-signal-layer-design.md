# Issues #123 + #124 — Trust Signal Layer: Design Spec

**Date:** 2026-04-28
**Issues:** casehubio/quarkus-qhorus#123, casehubio/quarkus-qhorus#124
**Prerequisite:** `quarkus-ledger` 0.2-SNAPSHOT — `TrustScoreComputer` now weights each
attestation by `recencyWeight × clamp(confidence, 0, 1)` (fix committed in ledger repo,
SNAPSHOT installed). This makes the `confidence` field on `LedgerAttestation` meaningful
in the Beta computation.

---

## Overview

Two SPIs added to `quarkus-qhorus` that together close the trust signal loop:

1. **`InstanceActorIdProvider`** (#124) — maps a Qhorus `instanceId` (session-scoped) to a
   ledger `actorId` (persona-scoped). Default is identity. Claudony provides the real
   implementation that maps `claudony-worker-{uuid}` → `claude:analyst@v1`.

2. **`CommitmentAttestationPolicy`** (#123) — decides what `LedgerAttestation` to write when a
   terminal message (DONE/FAILURE/DECLINE) discharges a commitment. Default writes SOUND on
   DONE, FLAGGED on FAILURE/DECLINE, nothing on EXPIRED (EXPIRED requires scheduler wiring —
   deferred). HANDOFF is not terminal in the attestation sense (obligation transferred, not
   resolved) — no attestation written.

Both SPIs are wired into `LedgerWriteService.record()` and its reactive mirror. The attestation
is written on the **originating COMMAND's** `MessageLedgerEntry`, not on the terminal message's
entry — this correctly attributes the trust signal to the act being evaluated.

---

## 1. `InstanceActorIdProvider` SPI (#124)

**File:** `runtime/src/main/java/io/quarkiverse/qhorus/runtime/ledger/InstanceActorIdProvider.java`

```java
@FunctionalInterface
public interface InstanceActorIdProvider {
    /**
     * Map a Qhorus instanceId to a ledger actorId (persona format).
     * Return the instanceId unchanged if no mapping is known.
     * Never return null.
     */
    String resolve(String instanceId);
}
```

**Default implementation** — `DefaultInstanceActorIdProvider`:

```java
@DefaultBean
@ApplicationScoped
public class DefaultInstanceActorIdProvider implements InstanceActorIdProvider {
    @Override
    public String resolve(String instanceId) {
        return instanceId;
    }
}
```

Replaceable via `@Alternative @Priority`. Claudony will provide:
```java
// in claudony-casehub:
String resolve(String instanceId) {
    return sessionRegistry.findPersona(instanceId)
        .orElse(instanceId);  // graceful fallback
}
```

**Where called:** `LedgerWriteService.record()` and `ReactiveLedgerWriteService.record()`,
before `entry.actorId = message.sender`. The resolved actorId is also passed to
`CommitmentAttestationPolicy` for the attestation's `attestorId` on DONE.

---

## 2. `CommitmentAttestationPolicy` SPI (#123)

**Files:**
- `runtime/src/main/java/io/quarkiverse/qhorus/runtime/ledger/CommitmentAttestationPolicy.java`
- `runtime/src/main/java/io/quarkiverse/qhorus/runtime/ledger/StoredCommitmentAttestationPolicy.java`

```java
@FunctionalInterface
public interface CommitmentAttestationPolicy {

    /**
     * Determine what attestation to write when a terminal message discharges a commitment.
     *
     * @param terminalType the message type that discharged the commitment
     *                     (DONE, FAILURE, or DECLINE — callers only invoke for these three)
     * @param resolvedActorId the resolved ledger actorId of the message sender
     *                        (already mapped through InstanceActorIdProvider)
     * @return the attestation to write, or empty to write no attestation
     */
    Optional<AttestationOutcome> attestationFor(MessageType terminalType, String resolvedActorId);

    record AttestationOutcome(
            AttestationVerdict verdict,
            double confidence,
            String attestorId,
            ActorType attestorType) {}
}
```

**Default implementation** — `StoredCommitmentAttestationPolicy`:

| MessageType | Verdict | Confidence | AttestorId | AttestorType | Rationale |
|---|---|---|---|---|---|
| `DONE` | `SOUND` | `config.doneConfidence()` (default 0.7) | `resolvedActorId` | `AGENT` | Strong positive — obligation fully discharged |
| `FAILURE` | `FLAGGED` | `config.failureConfidence()` (default 0.6) | `"system"` | `SYSTEM` | Moderate negative — agent tried but could not complete |
| `DECLINE` | `FLAGGED` | `config.declineConfidence()` (default 0.4) | `"system"` | `SYSTEM` | Weaker negative — declining may be appropriate professional judgment |
| anything else | empty | — | — | — | No attestation written |

**Confidence semantics:** Because `TrustScoreComputer` now computes
`α += recencyWeight × confidence`, these values directly control how strongly each outcome
moves the Beta distribution:
- 0.7 means DONE contributes 70% of a full recency-weighted positive update
- 0.6/0.4 mean FAILURE/DECLINE contribute 60%/40% of a full negative update
- Values deliberately below 1.0: a single message outcome is not fully diagnostic of
  trustworthiness — task difficulty and context matter

EXPIRED is not handled here — it requires scheduler integration (follow-on issue).

---

## 3. Config

**`QhorusConfig`** gains a new nested group:

```java
@WithName("attestation")
AttestationConfig attestation();

interface AttestationConfig {
    @WithDefault("0.7")
    double doneConfidence();       // → quarkus.qhorus.attestation.done-confidence

    @WithDefault("0.6")
    double failureConfidence();    // → quarkus.qhorus.attestation.failure-confidence

    @WithDefault("0.4")
    double declineConfidence();    // → quarkus.qhorus.attestation.decline-confidence
}
```

Application properties:
```properties
quarkus.qhorus.attestation.done-confidence=0.7
quarkus.qhorus.attestation.failure-confidence=0.6
quarkus.qhorus.attestation.decline-confidence=0.4
```

---

## 4. LedgerWriteService Changes

Both `LedgerWriteService` and `ReactiveLedgerWriteService` gain two injections:

```java
@Inject InstanceActorIdProvider actorIdProvider;
@Inject CommitmentAttestationPolicy attestationPolicy;
```

**Change 1 — actorId resolution** (replace `entry.actorId = message.sender`):

```java
final String resolvedActorId = actorIdProvider.resolve(message.sender);
entry.actorId = resolvedActorId;
```

**Change 2 — attestation** (extend existing `causedByEntryId` block):

```java
if (CAUSAL_TYPES.contains(message.messageType.name()) && message.correlationId != null) {
    repository.findLatestByCorrelationId(ch.id, message.correlationId)
        .ifPresent(prior -> {
            entry.causedByEntryId = prior.id;
            // Write attestation for terminal types only
            if (isTerminalAttestation(message.messageType)) {
                attestationPolicy.attestationFor(message.messageType, resolvedActorId)
                    .ifPresent(outcome -> writeAttestation(prior, outcome));
            }
        });
}
```

`isTerminalAttestation(MessageType)` returns true for DONE, FAILURE, DECLINE.

`writeAttestation(prior, outcome)` is a private helper:

```java
private void writeAttestation(MessageLedgerEntry commandEntry, AttestationOutcome outcome) {
    try {
        LedgerAttestation att = new LedgerAttestation();
        att.ledgerEntryId = commandEntry.id;
        att.subjectId = commandEntry.subjectId;
        att.attestorId = outcome.attestorId();
        att.attestorType = outcome.attestorType();
        att.verdict = outcome.verdict();
        att.confidence = outcome.confidence();
        repository.saveAttestation(att);
    } catch (Exception e) {
        LOG.warnf("Could not write attestation for entry %s — trust signal lost but pipeline unaffected",
                commandEntry.id);
    }
}
```

Failures are caught and logged — same pattern as the ledger write itself. The message
pipeline must never be affected by attestation issues.

---

## 5. Scope Boundaries

**In scope:**
- `InstanceActorIdProvider` interface + `DefaultInstanceActorIdProvider`
- `CommitmentAttestationPolicy` interface + `AttestationOutcome` record + `StoredCommitmentAttestationPolicy`
- `QhorusConfig.AttestationConfig` with three confidence properties
- `LedgerWriteService` — actorId resolution + attestation writing
- `ReactiveLedgerWriteService` — same two changes
- Tests as described in Section 4 (unit + integration + E2E + robustness)
- `docs/superpowers/specs/` design doc (this file)
- CLAUDE.md and design doc updates

**Out of scope:**
- EXPIRED commitment attestation (scheduler-driven — separate follow-on issue)
- Claudony's `InstanceActorIdProvider` implementation (belongs in `claudony-casehub`)
- Changes to `quarkus-ledger` (already done — SNAPSHOT installed)

---

## 6. Testing Strategy

### Unit tests (no Quarkus, no DB)

| Class | Coverage |
|---|---|
| `DefaultInstanceActorIdProviderTest` | Returns input unchanged; handles null gracefully |
| `StoredCommitmentAttestationPolicyTest` | DONE→SOUND/0.7, FAILURE→FLAGGED/0.6, DECLINE→FLAGGED/0.4, EVENT→empty, STATUS→empty, all 9 types covered |
| `AttestationOutcomeTest` | Record fields, equality |

### Integration tests (`@QuarkusTest`, H2)

| Class | Coverage |
|---|---|
| `LedgerAttestationWriteTest` | DONE writes SOUND on COMMAND entry; FAILURE/DECLINE write FLAGGED; HANDOFF, STATUS, EVENT write nothing; null correlationId → no attestation; attestation fields (verdict, confidence, attestorId, attestorType) correct |
| `ActorIdResolutionTest` | Custom `@Alternative` provider injected; resolved actorId appears in written entry |
| `AttestationConfigTest` | Custom confidence values via test properties flow through to attestation |

### End-to-end

| Class | Coverage |
|---|---|
| `LedgerQueryE2ETest` (extend) | Full COMMAND→DONE cycle: SOUND attestation readable via `findAttestationsByEntryId`; trust signal attribution correct |

### Robustness

- No prior ledger entry found for correlationId → no attestation, no error
- `attestationPolicy.attestationFor()` throws → caught, logged, pipeline unaffected
- `writeAttestation()` fails (DB error) → caught, logged, pipeline unaffected
- Custom policy returning `null` from `attestationFor()` → NPE guard (wrap in Optional)
