# Ledger Query Capabilities + Agent Mesh Dashboard — Design Specification

**Date:** 2026-04-26
**Status:** Approved

---

## 1. Vision

This feature completes the normative ledger as a queryable, explorable system — not just an
immutable write-ahead log. It adds seven ledger query capabilities (six new MCP tools plus an
enhancement to an existing one), a Tamboui TUI dashboard example, and establishes the layered
example architecture that Claudony and CaseHub will extend.

The example is the centrepiece. A single insurance claim scenario, `claim-456`, is used at every
level of the ecosystem. Each layer adds genuine capability to the same scenario — so a developer
can see exactly what each library contributes, and a colleague can understand the full stack by
watching one scenario evolve.

---

## 2. Design Principles

**P1 — Queries serve agents at runtime, not just developers post-hoc.** Every tool must answer
a question an agent would genuinely ask during a live interaction, not just a developer debugging
after the fact.

**P2 — Extension over duplication.** The example module is a published Maven artifact
(`io.quarkiverse.qhorus:quarkus-qhorus-example-agent-mesh-dashboard`). Claudony and CaseHub
depend on it and override extension points — they do not copy it.

**P3 — Each layer is independently useful.** The dashboard works with only quarkus-qhorus +
quarkus-ledger. quarkus-work adds task management. Claudony adds live LLM agents. CaseHub adds
real case data. Each level is demonstrable on its own.

**P4 — The scenario is realistic, not synthetic.** Insurance claim processing involves sanctions
screening, fraud detection, regulatory reporting, large-payout escalation, and payment failure
recovery — exactly the kind of obligation-heavy, compliance-sensitive workflow where the normative
layer earns its keep.

**P5 — Follow established patterns.** The dashboard module follows the quarkus-work-queues-dashboard
structure exactly: pure static builders, CDI beans, a PicoCLI entry point, and Tamboui TUI rendering.

---

## 3. MCP Tool Enhancements

### 3.1 Enhance `list_ledger_entries` — add `correlation_id` filter

Add an optional `correlation_id` parameter to the existing `list_ledger_entries` tool. When
provided, only entries with a matching `correlationId` are returned. All other filters still apply.

**Rationale:** Agents querying "everything in obligation X" should not need to know entry IDs
upfront. `correlation_id` is the natural key for an obligation.

### 3.2 `get_obligation_chain`

```
get_obligation_chain(
    channel_name,          required
    correlation_id,        required
)
```

Returns: all ledger entries for the obligation in sequence order, plus the live `Commitment`
state from CommitmentStore, plus a computed summary:

```json
{
  "summary": {
    "correlation_id": "corr-abc",
    "initiator": "claims-coordinator",
    "created_at": "2026-04-26T10:00:00Z",
    "resolved_at": "2026-04-26T10:04:23Z",
    "elapsed_seconds": 263,
    "resolution": "DONE",
    "participants": ["claims-coordinator", "damage-assessor"],
    "handoff_count": 0
  },
  "commitment": {
    "state": "FULFILLED",
    "obligor": "damage-assessor",
    "requester": "claims-coordinator"
  },
  "entries": [ ... ]
}
```

### 3.3 `get_causal_chain`

```
get_causal_chain(
    channel_name,          required
    ledger_entry_id,       required  — UUID of any entry in the chain
)
```

Walks `causedByEntryId` links upward from the given entry until reaching the root (an entry with
`causedByEntryId = null`). Returns the chain from root to the given entry, oldest first.

Handles delegation: if a HANDOFF is in the chain, it is included and identified. The chain shows
the full causal ancestry of any resolution.

**Error handling:** If the entry ID does not exist or has no causal ancestors, returns the single
entry with an empty `ancestors` list. Never throws on a missing chain.

### 3.4 `list_stalled_obligations`

```
list_stalled_obligations(
    channel_name,          required
    older_than_seconds?,   optional — default 30
)
```

Returns all COMMAND entries that have no DONE, FAILURE, DECLINE, or HANDOFF sibling with the
same `correlationId`, and whose `occurredAt` is older than `older_than_seconds` ago.

Each result includes: `correlation_id`, `actor_id` (who issued the COMMAND), `content`,
`occurred_at`, `stalled_for_seconds`.

**Rationale:** The single most operationally useful health check for a running agent mesh. Pairs
with the Watchdog module for automated alerting.

### 3.5 `get_obligation_stats`

```
get_obligation_stats(
    channel_name,          required
)
```

Returns aggregate obligation outcome counts for the channel:

```json
{
  "total_commands": 8,
  "fulfilled": 5,
  "failed": 1,
  "declined": 1,
  "delegated": 1,
  "still_open": 0,
  "stalled": 0,
  "fulfillment_rate": 0.625
}
```

### 3.6 `get_agent_history`

```
get_agent_history(
    channel_name,          required
    instance_id,           required
    limit?,                optional — default 20, max 100
)
```

Returns all ledger entries where `actor_id = instance_id`, ordered by sequence descending (most
recent first). Includes all message types — queries issued, commands issued, responses given,
declines, etc.

### 3.7 `summarise_telemetry`

```
summarise_telemetry(
    channel_name,          required
    since?,                optional — ISO-8601 timestamp
)
```

Aggregates EVENT-type ledger entries:

```json
{
  "total_events": 12,
  "by_tool": {
    "ml-fraud-score": { "count": 3, "avg_duration_ms": 2341, "total_tokens": 3600 },
    "sanctions-api":  { "count": 2, "avg_duration_ms": 187,  "total_tokens": 0   },
    "bacs-validate":  { "count": 1, "avg_duration_ms": 45,   "total_tokens": 0   }
  },
  "total_tokens": 3600,
  "total_duration_ms": 7609
}
```

---

## 4. Repository Additions

Six new query methods on `MessageLedgerEntryRepository`:

| Method | Purpose |
|---|---|
| `findAllByCorrelationId(channelId, correlationId)` | All entries for a correlationId, sequence ASC |
| `findAncestorChain(channelId, entryId)` | Walk `causedByEntryId` links upward to root |
| `findStalledCommands(channelId, olderThan)` | COMMANDs with no terminal sibling after timestamp |
| `countByOutcome(channelId)` | `Map<String, Long>` of messageType → count |
| `findByActorId(channelId, actorId, limit)` | All entries for an agent, sequence DESC |
| `aggregateTelemetry(channelId, since)` | Per-tool count, avg duration, total tokens from EVENTs |

All methods follow the established pattern: direct JPQL via `@PersistenceUnit("qhorus")` EntityManager,
with defensive null handling and ordered results.

---

## 5. Layered Example Architecture

### 5.1 Module

`examples/agent-mesh-dashboard/` — a standalone Quarkus app published to GitHub Packages as
`io.quarkiverse.qhorus:quarkus-qhorus-example-agent-mesh-dashboard:0.2-SNAPSHOT`.

Dependencies:
- `quarkus-qhorus` (communication mesh)
- `quarkus-ledger` (audit ledger)
- `quarkus-work` (task/work item management) — `0.2-SNAPSHOT` from GitHub Packages
- `tamboui-tui` + `tamboui-toolkit` `0.2.0-SNAPSHOT` — TUI rendering
- `quarkus-picocli` — entry point

### 5.2 Class structure

| Class | Type | Extension point |
|---|---|---|
| `ObligationBoardBuilder` | Pure static — no CDI | Reuse unchanged at every layer |
| `WorkQueueBoardBuilder` | Pure static — no CDI | Reuse unchanged at every layer |
| `ClaimScenarioDriver` (interface) | CDI bean | **Override with `@Alternative`** — Claudony uses live agents; CaseHub uses real claims |
| `DefaultClaimScenarioDriver` | CDI `@ApplicationScoped` | Drives the synthetic 13-step insurance scenario |
| `AgentMeshDashboard` | CDI `@ApplicationScoped` | Three-panel TUI; panels are protected methods — override to add panels |
| `DashboardMain` | PicoCLI `@QuarkusMain` | Replace at upper layers |

### 5.3 Extension contract

```java
public interface ClaimScenarioDriver {
    StepResult advance();   // advance one step; returns action label + hints
    StepResult reset();     // reset to initial state
    String nextAction();    // human-readable description of the next step
    String scenarioTitle(); // shown in dashboard header
}
```

Claudony overrides this with a `LiveAgentScenarioDriver` that sends real MCP tool calls via
its own agent orchestration. CaseHub overrides it with `RealClaimScenarioDriver` backed by live
case data from the CaseHub engine. Both compile against the `quarkus-qhorus-example-agent-mesh-dashboard`
artifact — no code duplication.

### 5.4 Layering trajectory

```
Layer 1 — quarkus-qhorus + quarkus-ledger (this feature)
          AgentMeshDashboard: obligation health + stalled + telemetry
          Scenario: synthetic 13-step insurance claim

Layer 2 — + quarkus-work (future)
          Adds WorkQueueBoardBuilder panel: which workers have which claims
          ClaimScenarioDriver → WorkItemScenarioDriver backed by real work queues

Layer 3 — + claudony (future)
          ClaimScenarioDriver → LiveAgentScenarioDriver (real LLM calls)
          Agents reason; infrastructure tracks obligations

Layer 4 — + casehub (future)
          ClaimScenarioDriver → RealClaimScenarioDriver (live CaseHub cases)
          Full production stack, same dashboard structure
```

Each layer is a separate Maven module that depends on the layer below. The dashboard panels
accumulate; the scenario driver is replaced. The board builders are never duplicated.

---

## 6. The Insurance Claim Scenario (`claim-456`)

### 6.1 Agents and channels

**Agents:** `claims-coordinator`, `policy-validator`, `sanctions-screener`, `fraud-detection`,
`damage-assessor`, `compliance-officer`, `senior-adjuster`, `payment-processor`,
`regulatory-reporter`

**Channels:** `claim-456` (main), `high-value-review` (escalation), `compliance-checks`
(regulatory), `payments`

### 6.2 The 13-step flow

| Step | Type | Channel | From → To | What happens |
|---|---|---|---|---|
+==============================================================================+
|  Acme Corp -- Fire Damage Claim #456  [s: next step  r: reset  q: quit]      |
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
|  corr-sii-2   COMMAND -> DONE     regulatory-reporter Post-settlement   [ok] |
|  corr-sii-1   COMMAND -> DONE     regulatory-reporter Pre-notification  [ok] |
|  corr-comp-2  COMMAND -> DONE     compliance-officer  FCA re-verified   [ok] |
|  corr-surv    COMMAND -> STALLED  damage-assessor     Awaiting surveyor[~~]  |
+==============================================================================+
|  CONSOLE                                                                     |
|  [10:04:23] DONE -- CHAPS payment confirmed: ref CHAPS-2026-04-001           |
|  [10:03:47] FAILURE -- BACS rejected: invalid sort code 20-14-09             |
|  [10:03:12] DONE -- Solvency II pre-notification filed: FCA-2026-04-001      |
+==============================================================================+
```
+==============================================================================+
|  Acme Corp -- Fire Damage Claim #456  [s: next step  r: reset  q: quit]      |
+==============================================================================+
|  CHANNEL OBLIGATION HEALTH                                                   |
|  Channel            | Commands | Done | Failed | Declined | Stalled          |
|  -------------------+----------+------+--------+----------+--------          |
|  claim-456          |    7     |  4   |   0    |    0     |   1    [!]       |
|  compliance-checks  |    3     |  3   |   0    |    1     |   0    [ok]      |
|  high-value-review  |    1     |  1   |   0    |    0     |   0    [ok]      |
|  payments           |    2     |  1   |   1    |    0     |   0    [!]       |
+==============================================================================+
|  RECENT OBLIGATIONS                                                          |
|  corr-pay-2   COMMAND -> DONE     payment-processor   CHAPS confirmed  [ok]  |
|  corr-pay-1   COMMAND -> FAILURE  payment-processor   Invalid sort code [x]  |
|  corr-sii-2   COMMAND -> DONE     regulatory-reporter Post-settlement   [ok] |
|  corr-sii-1   COMMAND -> DONE     regulatory-reporter Pre-notification  [ok] |
|  corr-comp-2  COMMAND -> DONE     compliance-officer  FCA re-verified   [ok] |
|  corr-surv    COMMAND -> STALLED  damage-assessor     Awaiting surveyor [~~] |
+==============================================================================+
|  CONSOLE                                                                     |
|  [10:04:23] DONE -- CHAPS payment confirmed: ref CHAPS-2026-04-001           |
|  [10:03:47] FAILURE -- BACS rejected: invalid sort code 20-14-09             |
|  [10:03:12] DONE -- Solvency II pre-notification filed: FCA-2026-04-001      |
+==============================================================================+
```

### 7.2 Colour scheme

| Element | Colour | Meaning |
|---|---|---|
| DONE row / ✓ | Green | Obligation fulfilled |
| FAILURE row / ✗ | Red | Obligation failed |
| DECLINE row | Orange | Obligation refused |
| HANDOFF row | Magenta | Obligation delegated |
| STALLED row / ⏳ | Yellow | No resolution yet |
| COMMAND (active) | Cyan | In-flight obligation |
| EVENT row | Dark grey | Telemetry, no obligation |
| Channel row: all resolved | Green header | Healthy channel |
| Channel row: has stalled | Yellow header | Needs attention |
| Channel row: has failures | Red header | Unhealthy channel |
| Regulatory entries | Blue | Compliance/regulatory context |

### 7.3 Keyboard bindings

- `s` — advance scenario one step; board repaints immediately
- `r` — reset scenario to initial state
- `q` — quit

### 7.4 Key classes

**`ObligationBoardBuilder`** (pure static, no CDI):
- Input: `List<Map<String, Object>>` from `get_obligation_stats` (one per channel)
- Output: `List<Row>` for Tamboui `Table` widget
- Stateless, fully unit-testable without Quarkus

**`ObligationListBuilder`** (pure static, no CDI):
- Input: `List<Map<String, Object>>` from `list_ledger_entries` (recent entries across channels)
- Output: `List<Row>` for Tamboui `Table` widget with per-row colour based on `message_type`

**`AgentMeshDashboard`** (CDI `@ApplicationScoped`):
- Injects: `QhorusMcpTools`, `ClaimScenarioDriver`
- Subscribes to CDI events for live repaint (or polls on tick)
- `renderFrame()` calls both builders and lays out three panels

**`DefaultClaimScenarioDriver`** (CDI `@ApplicationScoped`):
- Implements `ClaimScenarioDriver`
- Maintains step index; each `advance()` call sends the next `sendMessage` call
- Fully replaceable via `@Alternative` at upper layers

---

## 8. Testing Strategy

### 8.1 Unit tests — pure builders

No Quarkus, no CDI. Each builder takes plain Java collections as input.

| Test | Assertion |
|---|---|
| `ObligationBoardBuilder` — all zero counts | "—" in each cell |
| `ObligationBoardBuilder` — stalled count > 0 | Row is yellow-flagged |
| `ObligationBoardBuilder` — failed count > 0 | Row is red-flagged |
| `ObligationListBuilder` — DONE entry | Row colour is green |
| `ObligationListBuilder` — FAILURE entry | Row colour is red |
| `ObligationListBuilder` — STALLED entry | Row colour is yellow |

### 8.2 Unit tests — repository methods

Pure unit, `CapturingRepo` stub (same pattern as `LedgerWriteServiceTest`).

| Test | Assertion |
|---|---|
| `findStalledCommands` — COMMAND with DONE sibling | Not returned |
| `findStalledCommands` — COMMAND with no sibling, past threshold | Returned |
| `findStalledCommands` — COMMAND with no sibling, under threshold | Not returned |
| `aggregateTelemetry` — two events same tool | Count=2, avg correct |
| `aggregateTelemetry` — non-EVENT entries | Excluded |
| `findAncestorChain` — three-step COMMAND→HANDOFF→DONE | Returns chain in order |
| `countByOutcome` — mixed types | Correct count per type |

### 8.3 Unit tests — MCP tool response shape (LedgerQueryToolsTest)

No Quarkus, pure logic.

| Test | Assertion |
|---|---|
| `get_obligation_chain` — summary elapsed_seconds | Computed correctly from ledger timestamps |
| `get_obligation_chain` — no commitment found | Returns entries with commitment: null, no throw |
| `get_causal_chain` — root entry | ancestors: [] |
| `list_stalled_obligations` — empty channel | Returns [] |
| `get_obligation_stats` — fulfillment_rate | Computed as fulfilled / total_commands |

### 8.4 Integration tests (`@QuarkusTest`)

One test per tool, full stack via `sendMessage` → tool query → assert shape.

**Happy path — one test per new tool:**
Each test: setup channel + agents → send messages → call tool → assert result structure.

**`get_obligation_chain` correctness:**
- COMMAND → DONE: summary shows resolution=DONE, elapsed > 0, participants includes both agents
- COMMAND → HANDOFF → DONE: handoff_count=1, participants includes all three agents
- Unknown correlationId: returns empty entries, null commitment, no throw

**`get_causal_chain` correctness:**
- COMMAND → DONE: chain has 2 entries, COMMAND is root (causedByEntryId null)
- COMMAND → HANDOFF → DONE: chain has 3 entries in order
- Single entry with no ancestors: returns that entry, ancestors empty

**`list_stalled_obligations` correctness:**
- COMMAND + DONE same correlationId → COMMAND not stalled
- COMMAND + no response after threshold → appears in stalled list
- COMMAND + HANDOFF (which is a terminal for the original COMMAND) → not stalled

**`get_obligation_stats` correctness:**
- Channel with 4 COMMANDs: 2 DONE, 1 FAILURE, 1 DECLINE → rates correct
- Empty channel → all zeros, no throw

**`summarise_telemetry` correctness:**
- Three EVENT entries with tool_name + duration_ms → correct per-tool aggregation
- EVENT with missing tool_name → counted under tool_name=null, no throw
- Non-EVENT entries → excluded from aggregation

**Robustness:**
- All tools called on empty channel → empty/zero results, no exception
- All tools called on unknown channel → `ToolCallException`, correct message
- `list_stalled_obligations` — ledger disabled → empty list, pipeline unaffected
- `get_obligation_chain` — correlationId with STATUS but no terminal → commitment state OPEN, entries include STATUS

### 8.5 End-to-end via scenario driver

Drive the full 13-step insurance scenario through `DefaultClaimScenarioDriver.advance()` × 13,
then assert:
- `get_obligation_stats` on each channel matches expected counts
- `list_stalled_obligations` returns exactly step 4 (surveyor stalled)
- `get_causal_chain` on the DONE entry in step 7 returns the 3-entry COMMAND→HANDOFF→DONE chain
- `summarise_telemetry` shows two tools (ML fraud scorer, Solvency II API)
- Dashboard renders without exception (TuiTestRunner smoke test)

### 8.6 Dashboard tests (TuiTestRunner, no terminal)

Mirror the quarkus-work-queues-dashboard pattern:

```java
// Pure event-handler test
@Test
void handleEvent_s_advancesScenario() {
    dashboard.handleEvent(KeyEvent.of('s'), mockRunner);
    verify(driver).advance();
}

// Pure render test
@Test
void renderFrame_afterStep3_showsFraudScoringEntry() {
    dashboard.renderBoard(testFrame);
    assertThat(testFrame.renderedContent()).contains("fraud-detection");
}
```

---

## 9. Documentation Updates

### 9.1 `docs/specs/2026-04-13-qhorus-design.md`

Systematic pass (same discipline as the previous review):

- Add all 7 tools to MCP Tool Surface table
- Add `correlation_id` to `list_ledger_entries` parameter list
- Update Normative Audit Ledger section with the new tool descriptions and query patterns
- Add "Agent Mesh Dashboard example" subsection pointing to the example module

### 9.2 `docs/normative-layer.md`

Add "Grounded in a Real Scenario" section (written separately, see revision task) — grounds the
four-layer theory in the insurance claim walkthrough.

### 9.3 Javadoc

All new tool methods, repository methods, and builder classes follow the established pattern:
- Class-level Javadoc explaining purpose and cross-references
- `Refs #NNN, Epic #NNN` closing line
- No redundant parameter re-description if the name is self-explanatory

---

## 10. Extension Points for Claudony and CaseHub

This section exists so Claudony and CaseHub engineers can pick up this work without reading the
full implementation.

### 10.1 What to depend on

```xml
<dependency>
  <groupId>io.quarkiverse.qhorus</groupId>
  <artifactId>quarkus-qhorus-example-agent-mesh-dashboard</artifactId>
  <version>0.2-SNAPSHOT</version>
</dependency>
```

Resolves from GitHub Packages at `casehubio/*`.

### 10.2 What to override (Claudony — Layer 3)

1. Provide a `@Alternative @Priority(10) @ApplicationScoped LiveAgentScenarioDriver implements ClaimScenarioDriver`
   that drives the scenario via real LLM agent calls through Claudony's orchestration.
2. Optionally extend `AgentMeshDashboard` to add a fourth panel showing Claudony's session state.
3. Replace `DashboardMain` with Claudony's own entry point.

### 10.3 What to override (CaseHub — Layer 4)

1. Provide a `@Alternative @Priority(20) @ApplicationScoped RealClaimScenarioDriver implements ClaimScenarioDriver`
   backed by live CaseHub case data.
2. Replace `claim-456` references with dynamic case selection from CaseHub's case store.
3. The obligation board, ledger query tools, and dashboard structure require no changes.

### 10.4 What never changes across layers

- `ObligationBoardBuilder` — pure static, no CDI, no state
- `ObligationListBuilder` — pure static, no CDI, no state
- The 7 MCP tools — same tools work at every layer
- The normative ledger — same immutable record at every layer

---

## 11. Affected Files

### New files

| File | Purpose |
|---|---|
| `runtime/.../ledger/LedgerQueryService.java` | Encapsulates the 6 new query computations (summary, stats, stalled detection) |
| `runtime/.../mcp/QhorusMcpTools.java` | Add 6 new tools + enhance `list_ledger_entries` |
| `runtime/.../mcp/ReactiveQhorusMcpTools.java` | Mirror |
| `runtime/.../mcp/QhorusMcpToolsBase.java` | Add mappers for new response shapes |
| `examples/agent-mesh-dashboard/pom.xml` | New module |
| `examples/agent-mesh-dashboard/.../ClaimScenarioDriver.java` | Extension interface |
| `examples/agent-mesh-dashboard/.../DefaultClaimScenarioDriver.java` | 13-step synthetic scenario |
| `examples/agent-mesh-dashboard/.../ObligationBoardBuilder.java` | Pure static builder |
| `examples/agent-mesh-dashboard/.../ObligationListBuilder.java` | Pure static builder |
| `examples/agent-mesh-dashboard/.../AgentMeshDashboard.java` | CDI Tamboui TUI |
| `examples/agent-mesh-dashboard/.../DashboardMain.java` | PicoCLI entry point |

### Modified files

| File | Change |
|---|---|
| `runtime/.../ledger/MessageLedgerEntryRepository.java` | 6 new query methods |
| `examples/pom.xml` | Add agent-mesh-dashboard module |
| `docs/specs/2026-04-13-qhorus-design.md` | MCP tool surface + Normative Ledger section |
| `docs/normative-layer.md` | Add grounded scenario section |

---

## 12. Issue and Epic Structure

Create one epic: "Ledger Query Capabilities + Agent Mesh Dashboard". Child issues:

1. Repository — 6 new query methods + unit tests
2. MCP tools — enhance `list_ledger_entries` + 6 new tools + unit tests
3. Integration tests — all 7 tools, happy path + correctness + robustness
4. `ClaimScenarioDriver` interface + `DefaultClaimScenarioDriver` (13 steps)
5. `ObligationBoardBuilder` + `ObligationListBuilder` (pure static + unit tests)
6. `AgentMeshDashboard` + `DashboardMain` + dashboard tests
7. End-to-end scenario test (full 13 steps → assert all query tools)
8. Documentation — design doc systematic pass + new tool descriptions
9. `docs/normative-layer.md` — grounded scenario section
