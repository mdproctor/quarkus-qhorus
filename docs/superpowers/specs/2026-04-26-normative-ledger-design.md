# Normative Ledger — Design Specification
**Date:** 2026-04-26
**Status:** Approved
**Replaces:** Partial ledger coverage (EVENT-only) introduced in Epic #50

---

## 1. Problem Statement

The ledger currently records only EVENT messages — tool invocation telemetry. This was an MVP shortcut. The CommitmentStore tracks live obligation state; there is no immutable, tamper-evident record of the obligation lifecycle. The consequence: audit queries, compliance reports, and the paper's empirical evaluation all lack a persistent, queryable record of what agents actually committed to, refused, completed, or failed.

The fix is not additive. The existing `AgentMessageLedgerEntry` entity has mandatory `toolName` and `durationMs` fields that are structurally wrong for normative messages. The correct approach is a clean redesign.

---

## 2. Design Principles

**P1 — Complete channel audit trail.** Every message sent on a channel is a speech act. Every speech act is recorded. No conditional logic in the caller, no silent skips by type.

**P2 — Two-layer model.** CommitmentStore is the live obligation state (current view). The ledger is the immutable historical record (permanent view). They are complementary, not redundant.

**P3 — Single entity.** One `MessageLedgerEntry` table covers all 9 message types. `messageType` is the discriminator for field interpretation. Telemetry fields (EVENT-only) are nullable and clearly labelled. No UNION queries, no dual-table complexity.

**P4 — Causal chains in the ledger.** DONE, FAILURE, DECLINE, and HANDOFF entries carry `causedByEntryId` pointing to the COMMAND (or prior HANDOFF) that created the obligation. The obligation lifecycle is fully traceable inside the ledger itself.

**P5 — Graceful telemetry.** EVENT entries with malformed or missing JSON payload still produce a ledger entry — the speech act happened regardless of whether the telemetry payload was well-formed. Telemetry fields are left null. No silent skips.

**P6 — Documentation is a first-class deliverable.** The design doc (`docs/specs/2026-04-13-qhorus-design.md`) receives a systematic review pass — staleness, cross-references, duplication, redundancy, correctness — before targeted normative ledger additions are written.

---

## 3. Entity — `MessageLedgerEntry`

Replaces `AgentMessageLedgerEntry` entirely.

```
Table: message_ledger_entry
Discriminator: "QHORUS_MESSAGE"
Inherits: LedgerEntry (ledger_entry table via JOINED inheritance)
```

### 3.1 Fields

| Field | Type | Nullable | Notes |
|---|---|---|---|
| `channelId` | UUID | NOT NULL | mirrors `subjectId` from base class |
| `messageId` | Long | NOT NULL | FK to the `message` row that triggered this entry |
| `messageType` | String | NOT NULL | Qhorus `MessageType` enum name (e.g. `"COMMAND"`) |
| `target` | String | nullable | populated for COMMAND, HANDOFF; the intended recipient |
| `content` | TEXT | nullable | COMMAND description; DECLINE/FAILURE reason; DONE summary |
| `correlationId` | String | nullable | propagated from the message; used for causal chain resolution |
| `commitmentId` | UUID | nullable | links to `Commitment` row for obligation-bearing types |
| `toolName` | String | nullable | EVENT only — agent tool that was invoked |
| `durationMs` | Long | nullable | EVENT only — wall-clock duration in milliseconds |
| `tokenCount` | Long | nullable | EVENT only — LLM token count |
| `contextRefs` | TEXT | nullable | EVENT only — JSON array of context references |
| `sourceEntity` | TEXT | nullable | EVENT only — JSON object describing the source domain entity |

Inherited from `LedgerEntry` base (not redeclared):

| Field | Notes |
|---|---|
| `id` | UUID PK, assigned on persist |
| `subjectId` | = `channelId` — scopes the sequence and hash chain |
| `sequenceNumber` | per-channel, 1-based |
| `entryType` | `LedgerEntryType`: COMMAND/QUERY/HANDOFF → `COMMAND`; all others → `EVENT` |
| `actorId` | = message sender |
| `actorType` | always `ActorType.AGENT` |
| `occurredAt` | = `message.createdAt`, truncated to millis |
| `digest` | SHA-256 hash chain leaf (quarkus-ledger managed) |
| `causedByEntryId` | UUID FK to the ledger entry that causally produced this one |
| `traceId` | OpenTelemetry W3C trace context (populated if available) |

### 3.2 `causedByEntryId` resolution

At write time, for DONE, FAILURE, DECLINE, and HANDOFF entries:

1. Look up the most recent `MessageLedgerEntry` on the same channel with the same `correlationId` whose `messageType` is COMMAND or HANDOFF.
2. Set `causedByEntryId` to its `id`.
3. If no match is found (e.g. correlationId is null, or the COMMAND predates the ledger), leave `causedByEntryId` null — no error.

This creates a traversable obligation chain directly in the ledger.

### 3.3 `LedgerEntryType` mapping

| Qhorus MessageType | LedgerEntryType |
|---|---|
| QUERY, COMMAND, HANDOFF | COMMAND (declared intent) |
| RESPONSE, STATUS, DECLINE, DONE, FAILURE, EVENT | EVENT (observable fact) |

`LedgerEntryType` is a coarse base-class field. All Qhorus-level filtering uses `messageType`.

---

## 4. Write Path

### 4.1 `LedgerWriteService`

Single method replaces the old `recordEvent`:

```java
@Transactional(REQUIRES_NEW)
public void record(Channel ch, Message message)
```

Called from `QhorusMcpTools.sendMessage` for **every** message type. No conditional branching in the caller.

Internal logic:

1. Check `config.enabled()` — return immediately if false.
2. Build base fields (common to all types): `channelId`, `messageId`, `messageType`, `actorId`, `target`, `correlationId`, `commitmentId`, `subjectId`, `actorType`, `entryType`, `occurredAt`, `sequenceNumber`.
3. **If EVENT**: attempt JSON parse of `message.content`. On success, extract `toolName`, `durationMs` (no longer mandatory — missing fields leave them null). Extract optional `tokenCount`, `contextRefs`, `sourceEntity`. On JSON parse failure, log a warning and continue — telemetry fields remain null.
4. **If DONE, FAILURE, DECLINE, or HANDOFF**: resolve `causedByEntryId` via `findLatestByCorrelationId`.
5. Persist via `MessageLedgerEntryRepository.save`.
6. On any exception: log warning, do not rethrow — the message pipeline must not be affected by ledger write failures.

### 4.2 `ReactiveLedgerWriteService`

`Uni<Void> record(Channel ch, Message message)` — mirrors blocking semantics using `Panache.withTransaction`. Failures caught and swallowed at call site.

### 4.3 Caller change in `QhorusMcpTools`

```java
// Before (EVENT-only):
if (msg.messageType == MessageType.EVENT) {
    ledgerWriteService.recordEvent(ch, msg);
}

// After (all types):
ledgerWriteService.record(ch, msg);
```

Same change in `ReactiveQhorusMcpTools`.

---

## 5. Repository — `MessageLedgerEntryRepository`

Replaces `AgentMessageLedgerEntryRepository`.

### 5.1 Core query

```java
List<MessageLedgerEntry> listEntries(
    UUID channelId,
    Set<String> messageTypes,   // null = all types
    Long afterSequence,          // cursor pagination
    String agentId,              // filter by actorId
    Instant since,               // filter by occurredAt
    int limit
)
```

Builds a dynamic JPQL query. All filters optional. Results ordered by `sequenceNumber ASC`.

### 5.2 Causal chain lookup

```java
Optional<MessageLedgerEntry> findLatestByCorrelationId(UUID channelId, String correlationId)
```

Returns the most recent entry on the channel with the given `correlationId` where `messageType IN ('COMMAND', 'HANDOFF')`. Used by the write path to resolve `causedByEntryId`.

### 5.3 Retained methods

- `findByChannelId(UUID channelId)` — all entries for a channel, ordered
- `findLatestBySubjectId(UUID subjectId)` — for sequence number calculation
- `findEntryById(UUID id)` — by PK
- `findCausedBy(UUID entryId)` — all entries caused by a given entry (from base interface)

---

## 6. MCP Tools

### 6.1 Removed

`list_events` — replaced entirely by `list_ledger_entries`.

### 6.2 Added

```
list_ledger_entries(
    channel_name,           required
    type_filter?,           optional — comma-separated MessageType names
                            e.g. "COMMAND,DONE,FAILURE" for obligation lifecycle
                            omit for full channel history
    agent_id?,              optional — filter by sender
    since?,                 optional — ISO-8601 timestamp
    after_id?,              optional — sequence_number cursor for pagination
    limit?                  optional — default 20, max 100
)
```

Returns entries in chronological order. Each entry includes: `sequence_number`, `message_type`, `entry_type`, `actor_id`, `target`, `content`, `correlation_id`, `commitment_id`, `caused_by_entry_id`, `occurred_at`, plus telemetry fields when present.

### 6.3 Unchanged

`get_channel_timeline` — queries the Message table, not the ledger. No change.

---

## 7. Testing Strategy

### 7.1 Unit tests — `LedgerWriteServiceTest`

No CDI. `MessageLedgerEntryRepository` mocked or replaced with a capture list.

| Test | Assertion |
|---|---|
| Each of the 9 message types | correct `messageType`, `entryType`, base fields populated |
| EVENT with valid JSON | telemetry fields populated |
| EVENT with missing `toolName` | entry written, `toolName` null, no exception |
| EVENT with malformed JSON | entry written, all telemetry fields null, warning logged |
| DONE with matching COMMAND correlationId | `causedByEntryId` set to COMMAND entry id |
| DONE with no matching correlationId | `causedByEntryId` null, no exception |
| DECLINE with matching COMMAND | `causedByEntryId` set |
| HANDOFF with matching COMMAND | `causedByEntryId` set |
| Ledger disabled | no repository call, no exception |
| Repository throws | exception swallowed, warning logged |

### 7.2 Integration tests — `@QuarkusTest`

Real H2, real JPA. Unique channel names per test.

**Happy path — all 9 types:**
- Each type: `sendMessage` → `findByChannelId` → assert entry exists with correct fields

**Sequence numbering:**
- Multiple messages on one channel → sequence numbers increment 1, 2, 3, …
- Two channels in parallel → sequence numbers independent per channel

**Causal chain integrity:**
- COMMAND + DONE on same correlationId → `causedByEntryId` on DONE points to COMMAND entry `id`
- COMMAND + FAILURE → same
- COMMAND + DECLINE → same
- COMMAND + HANDOFF + DONE → HANDOFF points to COMMAND; DONE points to HANDOFF
- COMMAND + DONE, no correlationId → `causedByEntryId` null

**`listEntries` filter correctness:**
- `type_filter=COMMAND,DONE` → returns only those types
- `agent_id` filter → returns only entries from that sender
- `since` filter → excludes entries before timestamp
- `after_id` cursor → returns only entries with sequenceNumber > cursor
- All filters combined

**EVENT telemetry:**
- Valid JSON payload → telemetry fields populated
- Malformed JSON → entry present, telemetry fields null
- Missing `toolName` only → entry present, `toolName` null

### 7.3 End-to-end via MCP tool

Full obligation lifecycle through `sendMessage`, queried via `list_ledger_entries`:
- `COMMAND` → `STATUS` → `DONE` sequence: 3 entries, correct causal links
- `COMMAND` → `DECLINE`: 2 entries, DECLINE points to COMMAND
- `COMMAND` → `HANDOFF` → `DONE`: 3 entries, full chain

### 7.4 Robustness

- Ledger disabled (`quarkus.ledger.enabled=false`) → zero entries, message pipeline unaffected
- EVENT with completely empty content → entry written, all telemetry null
- Concurrent `sendMessage` calls on same channel → no sequence number collision (REQUIRES_NEW serialises writes)

### 7.5 Example updates

- `examples/type-system/` — add `@Test` demonstrating ledger entries for each of the 9 types; assert entries are created and correctly typed
- `examples/agent-communication/` — add assertion that the full obligation lifecycle (COMMAND → DONE via agent negotiation) is reflected in `list_ledger_entries`

---

## 8. Documentation Workstream

### 8.1 Systematic review of `docs/specs/2026-04-13-qhorus-design.md`

Before any additive changes, perform a full audit pass:

**Staleness:**
- Remove or update all references to `PendingReply` → replaced by `Commitment`
- Remove `list_events` → replaced by `list_ledger_entries`
- Update `AgentMessageLedgerEntry` references → `MessageLedgerEntry`
- Check Build Roadmap item #12 (structured observability) — mark as implemented and updated
- Verify all MCP tool names match the current `QhorusMcpTools` surface

**Cross-references:**
- Verify all section cross-references resolve (e.g. "see Section X" actually points to something that still exists)
- Verify all `Refs #N` issue numbers in the doc are still valid open/closed issues

**Duplication and redundancy:**
- Identify content that appears in both the Design Decisions section and the main body — keep the authoritative version, remove the echo
- Check whether the "Differences From cross-claude-mcp" table still adds value or is now just noise

**Correctness:**
- Data Model ERD: replace `PENDING_REPLY` with `COMMITMENT` (full schema); add `MESSAGE_LEDGER_ENTRY`
- Message Type Taxonomy table: verify all 9 types, obligation column, terminal column match the current `MessageType` enum and `CommitmentService` state machine
- MCP Tool Surface: verify every tool listed exists in `QhorusMcpTools` with the documented signature

### 8.2 Additive changes

After the review pass, add:

1. **New section "Normative Audit Ledger"** — between Message Type Taxonomy and MCP Tool Surface:
   - Two-layer model: CommitmentStore (live state) vs Ledger (immutable record)
   - What each message type writes and why
   - Causal chain (`causedByEntryId`) — how to traverse an obligation lifecycle
   - EVENT telemetry subset — which fields, graceful handling of malformed payloads
   - `list_ledger_entries` query patterns with examples

2. **Data Model ERD** — add `MESSAGE_LEDGER_ENTRY` entity with all fields and relationships to `CHANNEL` and `MESSAGE`

3. **Design Decisions** — add:
   - "Complete channel audit trail": why every message type is recorded, not just EVENT
   - "Single ledger entity": why one table over two, and what the nullable telemetry fields mean

---

## 9. Affected Files

| File | Change |
|---|---|
| `runtime/.../ledger/AgentMessageLedgerEntry.java` | Delete |
| `runtime/.../ledger/AgentMessageLedgerEntryRepository.java` | Delete |
| `runtime/.../ledger/ReactiveAgentMessageLedgerEntryRepository.java` | Delete |
| `runtime/.../ledger/ReactiveLedgerWriteService.java` | Rewrite — `record()` replaces `recordEvent()` |
| `runtime/.../ledger/LedgerWriteService.java` | Rewrite — unified `record()` |
| `runtime/.../ledger/MessageLedgerEntry.java` | New |
| `runtime/.../ledger/MessageLedgerEntryRepository.java` | New — replaces old repo |
| `runtime/.../ledger/ReactiveMessageLedgerEntryRepository.java` | New — replaces reactive repo |
| `runtime/.../mcp/QhorusMcpTools.java` | Update write call; replace `list_events` with `list_ledger_entries`; remove `toEventMap` |
| `runtime/.../mcp/ReactiveQhorusMcpTools.java` | Mirror |
| `runtime/.../mcp/QhorusMcpToolsBase.java` | Update mapper — `toLedgerEntryMap` replaces `toEventMap` |
| `runtime/src/test/.../ledger/AgentLedgerCaptureTest.java` | Delete — replaced by `MessageLedgerCaptureTest` |
| `runtime/src/test/.../ledger/AgentMessageLedgerEntryTest.java` | Delete — entity gone |
| `runtime/src/test/.../ledger/MessageLedgerCaptureTest.java` | New — integration tests per spec §7.2 |
| `runtime/src/test/.../ledger/LedgerWriteServiceTest.java` | New — unit tests per spec §7.1 |
| `examples/type-system/src/test/.../` | Add ledger assertion tests |
| `examples/agent-communication/src/test/.../` | Add ledger assertions to obligation lifecycle tests |
| `docs/specs/2026-04-13-qhorus-design.md` | Systematic review + normative ledger additions |

---

## 10. Subsequent Layer — Trust and Participant Provenance

This spec covers the normative ledger as implemented: all 9 message types recorded,
`causedByEntryId` causal chains, `list_ledger_entries` MCP tool. The next layer — specced
in `docs/superpowers/specs/2026-04-26-ledger-query-capabilities-design.md` — adds:

**Trust derived from the ledger record.** quarkus-ledger provides two complementary trust
models, both computed from `LedgerAttestation` records stamped onto ledger entries by peer
agents:

- **Bayesian Beta** (`ActorTrustScore`): per-actor trust score from direct attestation
  history. Alpha/beta parameters narrow as peers endorse or challenge decisions.
- **EigenTrust** (`EigenTrustComputer`): global trust propagated transitively via power
  iteration (Kamvar et al., 2003). Trust flows through the peer review network: if A
  attests positively to B's decisions, and B to C's, A has a derived signal about C.

**Discovery provenance.** CaseHub applies this framework to worker registration
(casehub-engine ADR-0006): a worker's registration is a normative act recorded in the
ledger with the same `causedByEntryId` causal chain as obligation lineage here. Trust
derives from provenance — a worker introduced by a high-trust provisioner inherits stronger
initial deontic standing.

**Methodology.** The normative ledger is not middleware — it is the persistence layer of a
governance methodology grounded in thirty years of formal methods research. For the full
framing, see `docs/normative-layer.md`.

---

## 11. Issue and Epic Structure

All implementation commits reference issues linked to a parent epic. The epic covers the full normative ledger feature. Child issues cover:

1. `MessageLedgerEntry` entity + `MessageLedgerEntryRepository`
2. `LedgerWriteService` rewrite (unified `record()`)
3. Reactive write service mirror
4. MCP tool: replace `list_events` with `list_ledger_entries`
5. Unit tests (`LedgerWriteServiceTest`)
6. Integration tests (`MessageLedgerCaptureTest`, filter tests, causal chain tests)
7. End-to-end and example tests
8. Documentation: systematic review pass
9. Documentation: normative ledger additions
