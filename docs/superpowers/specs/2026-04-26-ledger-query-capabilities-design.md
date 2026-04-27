# Ledger Query Capabilities + Agent Mesh Dashboard — Design Specification

**Date:** 2026-04-26
**Status:** Approved — revised with trust models, CaseHub extension specifics, and incremental delivery

---

## 1. Vision

The normative layer is not middleware. It is a governance methodology — a formally-grounded
framework that gives AI agents the formal status of accountable participants: capable of making
commitments, tracked when they do, and proven when they succeed or fail. This feature makes that
methodology explorable and demonstrable: seven ledger query capabilities that serve agents at
runtime, and a reference example that shows the full stack working together.

The example is the centrepiece. A single insurance claim scenario, `claim-456`, runs at every
level of the ecosystem. Each sub-project adds genuine capability to the same scenario — not as a
feature increment, but as a new layer of the governance methodology made visible. A business
owner watching the scenario advance step by step sees obligation tracking, regulatory compliance,
fraud detection, trust derivation, and payment failure recovery — all in one coherent picture.
A developer sees exactly what each library contributes and how to extend it.

This specification covers the full trajectory: seven tools today, attestation and trust in the
next sub-project, quarkus-work and then Claudony and CaseHub beyond. Each sub-project is
independently valuable and demonstrable. None requires the next to make sense.

---

## 2. Design Principles

**P1 — Queries serve agents at runtime, not just developers post-hoc.** Every tool must answer
a question an agent would genuinely ask during a live interaction, not just a developer debugging
after the fact.

**P2 — Extension over duplication.** The example module is a published Maven artifact.
Claudony and CaseHub depend on it and override extension points — they do not copy it.

**P3 — Each sub-project is independently deployable.** The dashboard works with only
quarkus-qhorus + quarkus-ledger. quarkus-work, Claudony, and CaseHub each add a complete,
demonstrable layer — never a half-built feature waiting for the next phase.

**P4 — The scenario is realistic and compliance-grounded.** Insurance claim processing is
universally understood, involves sanctions screening, fraud detection, regulatory reporting,
large-payout escalation, and payment failure recovery — exactly the obligation-heavy,
compliance-sensitive workflow where the normative layer earns its keep.

**P5 — Trust is derived, not configured.** Agent trust scores emerge from the ledger record of
peer attestations, not from static configuration. The EigenTrust and Bayesian Beta models in
quarkus-ledger compute trust from observable behaviour.

**P6 — Follow established patterns.** Dashboard structure follows quarkus-work-queues-dashboard
exactly: pure static builders, CDI beans, PicoCLI entry point, Tamboui TUI rendering.

---

## 3. MCP Tool Design — Rationale

Before the tool definitions, three design decisions that emerged from a systematic review
against the existing 41-tool surface:

**`get_obligation_chain` returns summary + commitment only — not raw entries.** Raw entries
for a correlation_id are already available via `list_ledger_entries(correlation_id=X)`.
Returning them again in `get_obligation_chain` would be redundant. The value of
`get_obligation_chain` is the *computed* enrichment: participants, elapsed time, handoff
count, resolution type, and live CommitmentStore state — none of which can be derived from
a single existing tool call.

**`get_agent_history` does not exist.** `list_ledger_entries` already filters by `agent_id`.
Rather than a separate tool, `list_ledger_entries` gains a `sort=asc|desc` parameter (default
`asc` for audit trail; `desc` for "most recent first" agent inspection). One parameter, not
one new tool.

**`get_telemetry_summary` not `summarise_telemetry`.** All 41 existing tools use American
English and `get_*` for queries returning a computed single result. `summarise` (British) +
verb-first breaks both conventions.

**`get_causal_chain` is a compliance/audit tool.** It takes a `ledger_entry_id` (UUID) that
agents only have after calling `list_ledger_entries`. Its description makes this explicit —
it is not an agent-runtime query, it is an audit and debugging tool for developers and
compliance officers tracing obligation lineage.

---

## 4. MCP Tool Enhancements

### 4.1 Enhance `list_ledger_entries` — add `correlation_id` and `sort` parameters

Two new optional parameters:
- `correlation_id` — when provided, only entries with matching `correlationId` are returned
- `sort` — `asc` (default, chronological audit trail) or `desc` (most recent first)

All other filters still apply. This replaces the need for a separate `get_agent_history` tool.

### 4.2 `get_obligation_chain`

```
get_obligation_chain(channel_name, correlation_id)
```

Returns computed enrichment for the obligation — **not raw entries** (use
`list_ledger_entries(correlation_id=X)` for those):

```json
{
  "correlation_id": "corr-abc",
  "initiator": "claims-coordinator",
  "created_at": "2026-04-26T10:00:00Z",
  "resolved_at": "2026-04-26T10:04:23Z",
  "elapsed_seconds": 263,
  "resolution": "DONE",
  "participants": ["claims-coordinator", "damage-assessor"],
  "handoff_count": 0,
  "commitment": { "state": "FULFILLED", "obligor": "damage-assessor", "requester": "claims-coordinator" }
}
```

`commitment` is null if no matching Commitment exists (not an error).
`resolution` is null if the obligation is still open.
Unknown `correlation_id` returns null fields, no throw.

### 4.3 `get_causal_chain`

```
get_causal_chain(channel_name, ledger_entry_id)
```

*Compliance and audit tool.* Takes a ledger entry UUID (obtained from `list_ledger_entries`)
and walks `causedByEntryId` links upward to the root. Returns the chain ordered oldest first.
Never throws on a missing chain — returns the single entry with an empty `ancestors` list.

### 4.4 `list_stalled_obligations`

```
list_stalled_obligations(channel_name, older_than_seconds? = 30)
```

Returns all COMMAND entries with no DONE/FAILURE/DECLINE/HANDOFF sibling sharing the same
`correlationId`, whose `occurredAt` is older than the threshold. Each result includes
`correlation_id`, `actor_id`, `content`, `occurred_at`, `stalled_for_seconds`.

### 4.5 `get_obligation_stats`

```
get_obligation_stats(channel_name)
```

```json
{
  "total_commands": 8, "fulfilled": 5, "failed": 1, "declined": 1,
  "delegated": 1, "still_open": 0, "stalled": 0, "fulfillment_rate": 0.625
}
```

### 4.6 `get_telemetry_summary`

```
get_telemetry_summary(channel_name, since?)
```

```json
{
  "total_events": 12,
  "by_tool": {
    "ml-fraud-score": { "count": 3, "avg_duration_ms": 2341, "total_tokens": 3600 },
    "sanctions-api":  { "count": 2, "avg_duration_ms": 187,  "total_tokens": 0   }
  },
  "total_tokens": 3600,
  "total_duration_ms": 7609
}
```

---

## 5. Repository Additions

Six new query methods on `MessageLedgerEntryRepository`:

| Method | Purpose |
|---|---|
| `findAllByCorrelationId(channelId, correlationId)` | All entries for a correlationId, ASC |
| `findAncestorChain(channelId, entryId)` | Walk `causedByEntryId` links upward to root |
| `findStalledCommands(channelId, olderThan)` | COMMANDs with no terminal sibling after timestamp |
| `countByOutcome(channelId)` | `Map<String, Long>` of messageType → count |
| `findByActorId(channelId, actorId, limit)` | All entries for an agent, sequence DESC |
| `aggregateTelemetry(channelId, since)` | Per-tool count, avg duration, total tokens from EVENTs |

---

## 6. Layered Example Architecture

### 5.1 Module

`examples/agent-mesh-dashboard/` — published to GitHub Packages as
`io.quarkiverse.qhorus:quarkus-qhorus-example-agent-mesh-dashboard:0.2-SNAPSHOT`.

**Sub-project 1 dependencies** (this spec):
- `quarkus-qhorus`, `quarkus-ledger`, `tamboui-tui`, `tamboui-toolkit`, `quarkus-picocli`

**Sub-project 2** adds `quarkus-work` (work items, inboxes, worker queues).

### 5.2 Class structure

| Class | Type | Extension point |
|---|---|---|
| `ObligationBoardBuilder` | Pure static — no CDI | Reuse unchanged at every layer |
| `TrustBoardBuilder` | Pure static — no CDI | Added in Sub-project 2; reuse unchanged |
| `WorkQueueBoardBuilder` | Pure static — no CDI | Added in Sub-project 3 (+quarkus-work) |
| `ClaimScenarioDriver` (interface) | CDI bean | Override with `@Alternative` at each layer |
| `DefaultClaimScenarioDriver` | CDI `@ApplicationScoped` | 15-step synthetic scenario |
| `AgentMeshDashboard` | CDI `@ApplicationScoped` | Panels are protected methods — add panels |
| `DashboardMain` | PicoCLI `@QuarkusMain` | Replace at upper layers |

### 5.3 Extension contract

```java
public interface ClaimScenarioDriver {
    StepResult advance();   // advance one step; returns action label + hints
    StepResult reset();     // reset to initial state
    String nextAction();    // human-readable description of next step
    String scenarioTitle(); // shown in dashboard header
}
```

### 5.4 Incremental delivery — sub-projects

Each sub-project is a complete, demonstrable increment. Later sub-projects live in the
repositories of the libraries they integrate — not in this repo.

```
Sub-project 1 — quarkus-qhorus + quarkus-ledger  (THIS SPEC)
   7 MCP tools. Dashboard: 3 panels (obligation health, recent obligations, console).
   15-step insurance claim with 2 attestation events.
   Standalone value: complete obligation governance visible in terminal.

Sub-project 2 — + trust layer  (THIS SPEC — Trust Panel)
   Dashboard: adds 4th panel (agent trust scores: EigenTrust + Bayesian Beta).
   Attestation events in scenario feed the trust pipeline.
   Standalone value: trust is derived from the ledger record, not from configuration.

Sub-project 3 — + quarkus-work  (quarkus-work repo)
   Claims become real work items with inboxes. Workers pick up tasks from queues.
   Dashboard: adds work queue panel (which worker has which claim, in which state).
   ClaimScenarioDriver → WorkItemScenarioDriver backed by real work queues.
   Standalone value: task management and normative communication work together.

Sub-project 4 — + claudony  (claudony repo)
   ClaimScenarioDriver → LiveAgentScenarioDriver (real LLM reasoning via MCP calls).
   Agents reason about obligations; infrastructure tracks and enforces them.
   Standalone value: live AI agents operating under formal accountability governance.

Sub-project 5 — + casehub  (casehub-engine repo)
   ClaimScenarioDriver → RealClaimScenarioDriver (live CaseHub cases).
   Worker registration as normative act (casehub-engine ADR-0006): workers announce
   capabilities, engine incurs obligation to route capable work, workers incur obligation
   to accept or decline with reason. Discovery lineage recorded via causedByEntryId.
   Trust scores flow from provisioner chains: a worker introduced by a pre-trusted
   provisioner inherits higher initial deontic standing via EigenTrust propagation.
   Standalone value: full production stack — real cases, real agents, formal governance.
```

---

## 7. The Insurance Claim Scenario (`claim-456`)

### 6.1 Agents and channels

**Agents:** `claims-coordinator`, `policy-validator`, `sanctions-screener`, `fraud-detection`,
`damage-assessor`, `compliance-officer`, `senior-adjuster`, `payment-processor`,
`regulatory-reporter`

**Channels:** `claim-456` (main), `high-value-review` (escalation), `compliance-checks`
(regulatory), `payments`

### 6.2 The 15-step flow

Steps 3a and 7a are attestation events — peer review verdicts stamped onto ledger entries.
These feed the Bayesian Beta and EigenTrust trust models in quarkus-ledger.

| Step | Type | Channel | From → To | What happens |
|---|---|---|---|---|
| 1 | QUERY → RESPONSE | claim-456 | coordinator → policy-validator | "Policy UK-2024-789 active? Fire covered?" → "Yes, max £500k" |
| 2 | COMMAND → STATUS → DONE | claim-456 | coordinator → sanctions-screener | OFAC/HMT screen on Acme Corp — clear |
| 3 | COMMAND + EVENT + DONE | claim-456 | coordinator → fraud-detection | ML fraud score (EVENT: 2.3s, 1200 tokens) — LOW 0.12 |
| 3a | ATTESTATION | claim-456 | compliance-officer attests fraud-detection | ENDORSED, confidence 0.95: "ML score below 0.2 threshold; consistent with historical fraud rates" |
| 4 | COMMAND → STATUS → (STALLED) | claim-456 | coordinator → damage-assessor | External surveyor dispatched — never responds |
| 5 | QUERY → RESPONSE | claim-456 | coordinator → damage-assessor | "Estimate without site visit?" → "£180k from photos" |
| 6 | COMMAND → DECLINE | compliance-checks | coordinator → compliance-officer | FCA check fails — missing Lloyd's syndicate approval |
| 7 | COMMAND → HANDOFF → STATUS → DONE | high-value-review | coordinator → compliance-officer → senior-adjuster | Syndicate approval escalation |
| 7a | ATTESTATION | high-value-review | claims-coordinator attests senior-adjuster | SOUND, confidence 0.90: "Syndicate process correctly followed; LLY-2026-04-789 on file" |
| 8 | COMMAND → DONE | compliance-checks | coordinator → compliance-officer | Re-verify FCA compliance — passes |
| 9 | COMMAND + EVENT + DONE | compliance-checks | coordinator → regulatory-reporter | Solvency II pre-notification (EVENT: 450ms API call, ref FCA-2026-04-001) |
| 10 | COMMAND → FAILURE | payments | coordinator → payment-processor | BACS payout £180k — invalid sort code |
| 11 | COMMAND → STATUS → DONE | payments | coordinator → payment-processor | CHAPS retry — payment confirmed |
| 12 | COMMAND → DONE | compliance-checks | coordinator → regulatory-reporter | Post-settlement Solvency II report |
| 13 | COMMAND → DONE | claim-456 | coordinator → audit-trail-keeper | Audit record sealed |

### 6.3 What the scenario demonstrates

- **All 9 message types** and all 7 CommitmentStore states ✓
- **Causal chain**: step 7: COMMAND → HANDOFF → DONE across two agents and two channels ✓
- **Attestations**: steps 3a and 7a feed EigenTrust and Bayesian Beta trust scoring ✓
- **Stalled detection**: step 4 surveyor never responds ✓
- **Regulatory subsystem**: Solvency II, FCA, Lloyd's — audit ledger has legal weight ✓
- **Telemetry**: ML model (step 3) and external API (step 9) — `get_telemetry_summary` ✓
- **Trust derivation**: peer attestations visible in trust panel; EigenTrust propagates ✓

---

## 8. Tamboui Dashboard Design

### 7.1 Four-panel layout (Sub-project 1 has three; trust panel added in Sub-project 2)

```
+==============================================================================+
|  Acme Corp -- Fire Damage Claim #456  [s: next  r: reset  a: attest  q: quit]|
+==============================================================================+
|  CHANNEL OBLIGATION HEALTH                                                   |
|  Channel            | Commands | Done | Failed | Declined | Stalled          |
|  -------------------+----------+------+--------+----------+--------          |
|  claim-456          |    7     |  4   |   0    |    0     |  1 [!]           |
|  compliance-checks  |    3     |  3   |   0    |    1     |  0 [ok]          |
|  high-value-review  |    1     |  1   |   0    |    0     |  0 [ok]          |
|  payments           |    2     |  1   |   1    |    0     |  0 [!]           |
+==============================================================================+
|  RECENT OBLIGATIONS                                                          |
|  corr-pay-2   COMMAND -> DONE     payment-processor   CHAPS confirmed  [ok]  |
|  corr-pay-1   COMMAND -> FAILURE  payment-processor   Invalid sort code [x]  |
|  corr-solvii  COMMAND -> DONE     regulatory-reporter Pre-notification  [ok] |
|  corr-comp-2  COMMAND -> DONE     compliance-officer  FCA re-verified   [ok] |
|  corr-comp-1  COMMAND -> DECLINE  compliance-officer  Missing syndicate  [o] |
|  corr-surv    COMMAND -> STALLED  damage-assessor     Awaiting surveyor [~~] |
+==============================================================================+
|  AGENT TRUST  (Sub-project 2)                                                |
|  Agent                | EigenTrust | Bayesian | Decisions | Attested         |
|  ---------------------+------------+----------+-----------+---------         |
|  fraud-detection      |    0.82    |   0.91   |    147    |   12 ENDORSED    |
|  compliance-officer   |    0.76    |   0.88   |     89    |    9 SOUND       |
|  senior-adjuster      |    0.71    |   0.85   |     34    |    4 SOUND       |
|  sanctions-screener   |    0.65    |   0.79   |     62    |    6 ENDORSED    |
+==============================================================================+
|  CONSOLE                                                                     |
|  [10:04:23] ATTESTATION -- compliance-officer ENDORSED fraud-detection [0.95]|
|  [10:04:01] DONE -- CHAPS payment confirmed: CHAPS-2026-04-001               |
|  [10:03:47] FAILURE -- BACS rejected: invalid sort code 20-14-09             |
+==============================================================================+
```

Trust panel (Sub-project 2) shows per-agent EigenTrust global share and Bayesian Beta score,
derived from the quarkus-ledger `ActorTrustScore` entity. Agents with no attestation history
show a uniform prior. Scores shift as peers attest to their decisions during the scenario.

### 7.2 Colour scheme

| Element | Colour | Meaning |
|---|---|---|
| DONE / [ok] | Green | Obligation fulfilled |
| FAILURE / [x] | Red | Obligation failed |
| DECLINE / [o] | Orange | Obligation refused |
| HANDOFF | Magenta | Obligation delegated |
| STALLED / [~~] | Yellow | No resolution yet |
| COMMAND (active) | Cyan | In-flight obligation |
| EVENT | Dark grey | Telemetry, no obligation |
| ATTESTATION | Blue | Peer review event |
| Trust score > 0.8 | Green | High confidence |
| Trust score 0.5–0.8 | Yellow | Moderate confidence |
| Trust score < 0.5 | Red | Low confidence |
| Channel: all resolved | Green header | Healthy |
| Channel: has stalled | Yellow header | Needs attention |
| Channel: has failures | Red header | Unhealthy |

### 7.3 Keyboard bindings

- `s` — advance scenario one step (including attestation events)
- `r` — reset scenario to initial state
- `q` — quit

### 7.4 Key classes

**`ObligationBoardBuilder`** (pure static): input from `get_obligation_stats`, output Tamboui rows.

**`ObligationListBuilder`** (pure static): input from `list_ledger_entries`, output coloured rows.

**`TrustBoardBuilder`** (pure static, Sub-project 2): input from quarkus-ledger
`ActorTrustScore` list, output trust panel rows with EigenTrust and Bayesian Beta scores.

**`AgentMeshDashboard`** (CDI): injects `QhorusMcpTools` and `ClaimScenarioDriver`. Protected
`renderObligationPanel()`, `renderObligationsListPanel()`, `renderTrustPanel()`,
`renderConsolePanel()` — override individual panels at upper layers.

**`DefaultClaimScenarioDriver`** (CDI): 15-step sequence including attestation events at steps
3a and 7a. `advance()` dispatches either a `sendMessage` call or a ledger attestation write.

---

## 9. Testing Strategy

### 8.1 Unit tests — pure builders

| Test | Assertion |
|---|---|
| `ObligationBoardBuilder` — stalled count > 0 | Row yellow-flagged |
| `ObligationBoardBuilder` — failed count > 0 | Row red-flagged |
| `ObligationListBuilder` — DONE entry | Row green |
| `ObligationListBuilder` — ATTESTATION entry | Row blue |
| `TrustBoardBuilder` — score > 0.8 | Green cell |
| `TrustBoardBuilder` — score < 0.5 | Red cell |
| `TrustBoardBuilder` — no attestations | Uniform prior shown |

### 8.2 Unit tests — repository methods

`CapturingRepo` stub pattern (same as `LedgerWriteServiceTest`).

| Test | Assertion |
|---|---|
| `findStalledCommands` — COMMAND with DONE sibling | Not returned |
| `findStalledCommands` — COMMAND alone past threshold | Returned |
| `aggregateTelemetry` — two events same tool | Count=2, avg correct |
| `findAncestorChain` — COMMAND → HANDOFF → DONE | Returns chain in order |
| `countByOutcome` — mixed types | Correct count per type |

### 8.3 Integration tests (`@QuarkusTest`)

**Happy path — one test per new tool:** each sends messages → calls tool → asserts response shape.

**`get_obligation_chain` correctness:**
- COMMAND → DONE: summary resolution=DONE, elapsed > 0, participants correct
- COMMAND → HANDOFF → DONE: handoff_count=1, three participants
- COMMAND → DECLINE: summary resolution=DECLINED

**`get_causal_chain` correctness:**
- COMMAND → DONE: chain length 2, COMMAND is root
- COMMAND → HANDOFF → DONE: chain length 3 in order

**`list_stalled_obligations` correctness:**
- COMMAND + DONE same correlationId → not stalled
- COMMAND alone past threshold → stalled
- `stalled_for_seconds` computed correctly

**`get_obligation_stats` correctness:**
- 2 DONE, 1 FAILURE, 1 DECLINE → rates correct
- `fulfillment_rate` = fulfilled / total_commands

**`get_telemetry_summary` correctness:**
- Three EVENTs same tool → count=3, avg correct
- Missing `tool_name` → counted under null, no throw
- Non-EVENT entries excluded

**Attestation flow (Sub-project 2):**
- Post attestation → `ActorTrustScore` updated for attestor and decision-maker
- EigenTrust recomputed → global trust share shifts
- `TrustBoardBuilder` reflects updated scores

**Robustness:**
- All tools on empty channel → empty/zero results, no exception
- Unknown channel → `ToolCallException`
- Ledger disabled → empty results, pipeline unaffected

### 8.4 End-to-end — full 15-step scenario

Drive all 15 steps via `DefaultClaimScenarioDriver.advance()` × 15. Then assert:
- `get_obligation_stats` per channel matches expected counts
- `list_stalled_obligations` returns exactly step 4 (surveyor)
- `get_causal_chain` on step 7 DONE returns the 3-entry chain
- `get_telemetry_summary` shows two tools (ML fraud scorer, Solvency II API)
- After steps 3a and 7a: `ActorTrustScore` updated for attested agents
- Dashboard renders without exception (TuiTestRunner smoke test)

---

## 10. Documentation Updates

- `docs/specs/2026-04-13-qhorus-design.md`: add all 7 tools to MCP surface; update Normative
  Audit Ledger section with new tool descriptions and attestation/trust context
- `docs/normative-layer.md`: already updated with trust models and grounded scenario
- Javadoc on all new classes: class-level explanation, `Refs #NNN, Epic #NNN` closing line

---

## 11. Extension Points — Claudony and CaseHub

### 10.1 What to depend on

```xml
<dependency>
  <groupId>io.quarkiverse.qhorus</groupId>
  <artifactId>quarkus-qhorus-example-agent-mesh-dashboard</artifactId>
  <version>0.2-SNAPSHOT</version>
</dependency>
```

Resolves from GitHub Packages at `casehubio/*`.

### 10.2 Claudony (Sub-project 4)

Provide `@Alternative @Priority(10) LiveAgentScenarioDriver implements ClaimScenarioDriver`.
Each `advance()` call invokes real LLM agent reasoning via Claudony's MCP orchestration. The
obligation board, trust panel, and ledger query tools require no changes — they observe what the
live agents actually did, not what a synthetic driver simulated.

### 10.3 CaseHub (Sub-project 5)

Provide `@Alternative @Priority(20) RealClaimScenarioDriver implements ClaimScenarioDriver`
backed by live CaseHub cases.

**Worker registration as normative act (casehub-engine ADR-0006):** When CaseHub workers
register with the engine, their registration is recorded in the normative ledger using the same
`causedByEntryId` causal chain as obligation lineage in Qhorus. A worker introduced by a trusted
provisioner has a `causedByEntryId` pointing to the provisioner's own registration entry. The
EigenTrust model propagates trust through this chain: workers introduced by high-trust
provisioners inherit a higher initial deontic standing than workers that self-announced.

The trust panel in the dashboard will show this: CaseHub workers with provisioner lineage will
have non-uniform priors even before their first attested decision. The causal chain from
`get_causal_chain` will show the provenance of how each worker came to exist in the mesh.

**What never changes across layers:**
- `ObligationBoardBuilder`, `TrustBoardBuilder`, `WorkQueueBoardBuilder` — pure static, no CDI
- The 7 MCP tools — identical at every layer
- The normative ledger — same immutable record at every layer

---

## 12. Affected Files

### Sub-project 1: Ledger Query Tools

| File | Change |
|---|---|
| `runtime/.../ledger/MessageLedgerEntryRepository.java` | 6 new query methods |
| `runtime/.../mcp/QhorusMcpTools.java` | Add 6 new tools + enhance `list_ledger_entries` |
| `runtime/.../mcp/ReactiveQhorusMcpTools.java` | Mirror |
| `runtime/.../mcp/QhorusMcpToolsBase.java` | Add mappers for new response shapes |
| `runtime/src/test/.../ledger/LedgerQueryToolsTest.java` | New — unit + integration tests |
| `docs/specs/2026-04-13-qhorus-design.md` | MCP tool surface + Normative Ledger section |

### Sub-project 2: Agent Mesh Dashboard (3 panels + scenario)

| File | Change |
|---|---|
| `examples/agent-mesh-dashboard/pom.xml` | New module |
| `examples/agent-mesh-dashboard/.../ClaimScenarioDriver.java` | Extension interface |
| `examples/agent-mesh-dashboard/.../DefaultClaimScenarioDriver.java` | 15-step scenario |
| `examples/agent-mesh-dashboard/.../ObligationBoardBuilder.java` | Pure static builder |
| `examples/agent-mesh-dashboard/.../ObligationListBuilder.java` | Pure static builder |
| `examples/agent-mesh-dashboard/.../AgentMeshDashboard.java` | CDI Tamboui TUI |
| `examples/agent-mesh-dashboard/.../DashboardMain.java` | PicoCLI entry point |
| `examples/pom.xml` | Add agent-mesh-dashboard module |

### Sub-project 2 (continued): Trust Panel

| File | Change |
|---|---|
| `examples/agent-mesh-dashboard/.../TrustBoardBuilder.java` | Pure static trust panel builder |
| `examples/agent-mesh-dashboard/.../AgentMeshDashboard.java` | Add 4th panel method |

---

## 13. Issue and Epic Structure

One epic per sub-project. Each is independently closeable.

**Sub-project 1 epic — "Ledger Query Capabilities":** child issues for each of the 7 tools,
repository additions, integration tests, robustness tests, documentation.

**Sub-project 2 epic — "Agent Mesh Dashboard":** child issues for scenario driver, builders,
TUI rendering, attestation events in scenario, trust panel, dashboard tests, publishing to
GitHub Packages.

**Sub-projects 3–5** are tracked in their respective repositories (quarkus-work, claudony,
casehub-engine) with dependency declarations pointing to the artifact from Sub-project 2.
