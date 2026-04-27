# MessageType Redesign — Design Specification
**Date:** 2026-04-23  
**Status:** Draft — awaiting user approval  
**Approach:** A (full v1 — taxonomy + envelope + two-part structure)  
**Supersedes:** Current 6-value `MessageType` enum  
**Theoretical foundation:** ADR-0005 (to be written — see Section 9)

---

## 1. Problem Statement

The current `MessageType` enum has three concrete failures:

**1. REQUEST conflates two categorically distinct acts.**
"Give me information" and "do something for me" have different reply semantics, different obligation lifecycles, and different routing implications. Agents must parse content to distinguish them — which is unreliable and pushes normative reasoning into the LLM.

**2. STATUS conflates progress with refusal.**
"I am working on it" (a commissive — ongoing commitment) and "I cannot do this" (a refusal — obligation cancelled) are both currently expressed as STATUS. Receivers must parse content to tell them apart.

**3. DONE has no typed failure counterpart.**
Terminal outcomes are either success or failure. Distinguishing them by content forces content parsing in routing logic and audit queries.

The consequence: 36.9% of multi-agent failures are inter-agent communication misalignment (MAST research). A typed taxonomy makes ambiguity structurally impossible for the cases that matter.

---

## 2. Design Principles

**P1: Example-first.** Every type must be demonstrable against a real enterprise failure mode — not a toy scenario. If a type cannot be grounded in a concrete problem a developer or compliance officer has already encountered, it does not belong in v1.

**P2: Theory-grounded.** The taxonomy is derived from a four-layer normative framework (speech act theory, deontic logic, defeasible reasoning, social commitment semantics). Each type has a defined position in all four layers. This is documented in ADR-0005. The Javadoc does not require developers to read ADR-0005.

**P3: LLM-friendly.** The types must be classifiable by an LLM from context, without requiring developers to understand the theoretical framework. Classification accuracy is a measurable property of the taxonomy — if LLMs consistently confuse two types, they should be merged; if they confuse one type with multiple things, it should be split.

**P4: Infrastructure-enforced.** Normative semantics are enforced by the mesh, not by LLM reasoning. LLMs populate the message envelope; infrastructure validates and enforces. LLMs are not trusted to reason correctly about obligations.

**P5: Nothing closes off v2/v3.** The v1 design must not prevent the CommitmentStore generalisation (v2) or the Drools enforcement layer (v3).

---

## 2a. Ecosystem Scope — Beyond Inter-Agent Communication

The four-layer normative framework defined here (speech act theory, deontic logic, defeasible
reasoning, social commitment semantics) is intentionally designed as ecosystem-wide
infrastructure, not as a Qhorus-specific mechanism for inter-agent messages.

CaseHub has adopted this framework for worker registration and discovery (casehub-engine
ADR-0006). The reasoning: worker registration is itself a normative act — a declarative speech
act that constitutively creates a new participant with deontic consequences. The engine incurs
an obligation to consider the worker for capable work; the worker incurs an obligation to accept
work within declared capabilities or decline with reason.

The discovery lineage of a worker (how it came to be known — statically declared, provisioned,
self-announced, or introduced by another participant) is recorded in the normative ledger using
the same `causedByEntryId` causal chain mechanism as obligation lineage in Qhorus. Trust derives
from this chain: a worker introduced by a trusted provisioner inherits a higher initial deontic
standing than one that self-announced without a voucher.

**Trust is derived from the same ledger.** The discovery lineage chain does not merely record
provenance — it feeds the trust models in quarkus-ledger. When peers attest to an agent's
decisions (`LedgerAttestation`: `SOUND`, `ENDORSED`, `FLAGGED`, `CHALLENGED`), two trust
scores are computed from those records:

- **Bayesian Beta** (`ActorTrustScore`): per-actor trust from direct attestation history.
  Alpha accumulates positive evidence; beta accumulates negative. The score narrows as more
  peers attest and does not require any configuration.
- **EigenTrust** (`EigenTrustComputer`): global trust propagated transitively via power
  iteration (Kamvar et al., 2003). Trust flows through the peer review network: if agent A
  attests positively to B's decisions, and B attests positively to C's, A has a derived
  signal about C even without direct interaction.

A worker introduced by a provisioner whose EigenTrust global share is high inherits a
stronger initial deontic standing than one that self-announced. Trust and provenance are
unified in the same causal chain.

**Methodology, not middleware.** The framework defined in this spec — and implemented by
Qhorus, quarkus-ledger, and extended by CaseHub — is a governance methodology grounded in
thirty years of formal methods research. The significance for business owners and compliance
officers is not the technology; it is that every agent interaction carries the formal status
of an accountable act: recorded, tracked, causally linked, tamper-evidently proven, and
queryable. For the full methodology framing, see `docs/normative-layer.md`.

The implication for this spec: when extending the framework to new domains, the four layers apply
without modification. The framework gains value by being applied consistently — each additional
domain it governs strengthens the case for it as the shared normative substrate of the ecosystem.
New message types or new participation events should be mapped to the four-layer table in
Section 4.2, not modelled separately.

**Cross-references:**
- `casehub-engine adr/0006-worker-registration-as-normative-act.md` — the decision to apply
  this framework to worker registration
- `casehub-engine adr/0005-worker-provisioner-spi-placement.md` — the SPI contracts governed
  normatively by ADR-0006
- `docs/superpowers/specs/2026-04-26-normative-ledger-design.md` — the ledger infrastructure
  that records both inter-agent and worker-registration normative events
- `docs/normative-layer.md` — full methodology framing, trust models, and grounded scenario

---

## 3. The Two-Part Message Structure

Every message has two explicitly designed parts:

```
┌──────────────────────────────────────────────────────────┐
│  COMMITMENT ENVELOPE  (structured, infrastructure-reads) │
│                                                          │
│  messageType    : COMMAND         ← illocutionary type   │
│  sender         : agent-A                                │
│  target         : agent-B         ← optional, directed   │
│  correlationId  : uuid            ← auto-generated       │
│  commitmentId   : uuid            ← NEW: CommitmentStore │
│  channelId      : uuid                                   │
│  deadline       : Instant         ← NEW: temporal layer  │
│  acknowledgedAt : Instant         ← NEW: ACK tracking    │
│  inReplyTo      : Long                                   │
│  artefactRefs   : String                                 │
│  createdAt      : Instant                                │
│                                                          │
├──────────────────────────────────────────────────────────┤
│  LLM PAYLOAD  (free text, opaque to infrastructure)      │
│                                                          │
│  content : natural language message from the LLM agent   │
└──────────────────────────────────────────────────────────┘
```

**Who sets what:**

| Field | Set by | How |
|---|---|---|
| `messageType` | LLM | Via MCP tool parameter |
| `content` | LLM | Natural language, free form |
| `target` | LLM (optional) | Via MCP tool parameter when directed |
| `deadline` | Channel config default; LLM override optional | Via MCP tool parameter |
| `artefactRefs` | LLM (optional) | Via MCP tool parameter |
| `commitmentId` | Infrastructure | Auto-generated on QUERY/COMMAND |
| `correlationId` | Infrastructure | Auto-generated on QUERY/COMMAND |
| `acknowledgedAt` | Infrastructure | Set when ACK received (v2) |
| `createdAt` | Infrastructure | `@PrePersist` |

**The infrastructure operates exclusively on the envelope. It never reads `content`.**  
**The LLM operates on `content`. It only needs to choose `messageType` and optionally `target`, `deadline`, `artefactRefs`.**

---

## 4. The 9-Type Taxonomy

### 4.1 Overview

| Type | Replaces | Searle category | Terminal | Corr. ID | Agent visible |
|---|---|---|---|---|---|
| `QUERY` | REQUEST (partial) | Directive (epistemic) | No | Yes | Yes |
| `COMMAND` | REQUEST (partial) | Directive (action) | No | Yes | Yes |
| `RESPONSE` | RESPONSE | Assertive | No | Yes | Yes |
| `STATUS` | STATUS | Commissive | No | No | Yes |
| `DECLINE` | *(new)* | Assertive (negative) | No | Optional | Yes |
| `HANDOFF` | HANDOFF | Declarative (constitutive) | Yes | No | Yes |
| `DONE` | DONE | Declarative (success) | Yes | No | Yes |
| `FAILURE` | *(new)* | Declarative (terminal) | Yes | No | Yes |
| `EVENT` | EVENT | Perlocutionary record | No | No | No |

### 4.2 Four-Layer Semantics Per Type

The master mapping across all four normative layers:

| Type | **L1: Deontic effect** | **L1: Defeasible** | **L2: Commitment operation** | **L3: Temporal** | **L4: Enforcement (v3)** |
|---|---|---|---|---|---|
| `QUERY` | Creates: receiver obligated to inform or DECLINE | Defeated by: HANDOFF, VETO | Creates `C(receiver→sender, inform_result)` | Deadline from channel; no RESPONSE in T → auto-DECLINE | Block RESPONSE without prior QUERY on correlationId |
| `COMMAND` | Creates: receiver obligated to execute or DECLINE | Defeated by: HANDOFF, VETO, superseding COMMAND | Creates `C(receiver→sender, execute_and_report)` | Deadline from channel; no STATUS/DONE in T → generate FAILURE | Block DONE without open COMMAND; cascade VETO |
| `RESPONSE` | Discharges: QUERY obligation | Defeated by: correction (v3+) | Discharges `C` matching `correlationId` | Valid only after matching QUERY | Reject RESPONSE with no matching open QUERY |
| `STATUS` | Extends: COMMAND obligation window; asserts continued intent | Not defeasible — informational | No new commitment; extends deadline | Valid only while COMMAND obligation open | Reject STATUS with no open COMMAND obligation |
| `DECLINE` | Discharges: obligation by refusal; creates secondary: explain_reason | Not further defeasible | Cancels `C`; creates `C(receiver→sender, explain_reason)` | Secondary obligation has its own deadline | Verify reason field populated; fire reminder if secondary unfulfilled |
| `HANDOFF` | Transfers: obligation to named target; original discharged | Defeated by: VETO from superior authority | Delegates `C`; new `C(target→creditor)` created | Target inherits original deadline unless overridden | Verify target is registered capable agent; block if not |
| `DONE` | Discharges: COMMAND obligation — successful | Not defeasible once committed | Fulfils `C` — terminal | Recorded against deadline: was it within window? | Verify open COMMAND obligation exists; block premature DONE |
| `FAILURE` | Discharges: COMMAND obligation — unsuccessful; creates secondary: explain_failure | Not defeasible once committed | Cancels `C` by inability — terminal | Recorded against deadline; may be deadline-triggered | Fire secondary obligation (notify/escalate); auto-generate if deadline exceeded |
| `EVENT` | None — no deontic footprint | N/A | No commitment created or discharged | No temporal constraints | Not processed by Drools (observer channel only) |

### 4.3 Per-Type Developer Definitions

---

#### QUERY
**One-liner:** Ask another agent for information. Expect a RESPONSE back.

**When to use:** You need a fact, a value, or a decision before you can proceed. You are not asking the receiver to do anything — you are asking them to tell you something.

**When NOT to use:** When you need the receiver to take an action. Use COMMAND instead.

**Receiver must:** Send RESPONSE with the requested information, or DECLINE with a reason. Do not execute anything — a QUERY has no side effects.

**Enterprise example — preventing the ambiguous delegation:**
Without QUERY, Agent B receives "handle the refund for order #4521" and must guess whether to ask or act. With QUERY: Agent A sends QUERY "Is the refund amount pre-approved for order #4521?" Agent B sends RESPONSE "Yes, standard 10% goodwill, max £50." Agent A then sends COMMAND "Process the refund at 10% goodwill." The distinction prevents a £50,000 refund on a £500 order.

---

#### COMMAND
**One-liner:** Tell another agent to do something. Expect STATUS updates and eventually DONE or FAILURE.

**When to use:** You need the receiver to execute something — run a report, make a change, process a request. There will be side effects.

**When NOT to use:** When you just need information. Use QUERY instead.

**Receiver must:** Execute the task and send DONE or FAILURE when complete. Send STATUS to extend the deadline window while working. DECLINE if unable to accept the obligation.

**Enterprise example — the silent failure:**
Without typed COMMANDs, Agent B times out silently and Agent A waits indefinitely. With COMMAND: the obligation is tracked in CommitmentStore with a 30-minute deadline. At T+30 with no STATUS or DONE: infrastructure generates FAILURE "deadline exceeded." Agent A receives FAILURE, escalates to fallback. Full audit trail exists.

---

#### RESPONSE
**One-liner:** Answer a QUERY. Carries the requested information.

**When to use:** You received a QUERY and have the answer. Always carries a `correlationId` matching the originating QUERY.

**When NOT to use:** As a substitute for DONE. RESPONSE answers a QUERY; DONE completes a COMMAND.

**Receiver must:** Nothing further — RESPONSE discharges the QUERY obligation completely.

---

#### STATUS
**One-liner:** Report progress on an open COMMAND. Signals you are still working.

**When to use:** While executing a COMMAND, to extend the deadline window and signal continued intent. Especially important for long-running tasks where the deadline might otherwise expire.

**When NOT to use:** As a soft refusal or to communicate problems. Use DECLINE or FAILURE for those. STATUS means "I am still working and will complete this."

**Receiver must:** Nothing — STATUS is informational. The deadline window is extended.

---

#### DECLINE
**One-liner:** Refuse an obligation. You will not fulfil the QUERY or COMMAND.

**When to use:** You received a QUERY or COMMAND you cannot or should not fulfil. Requires a reason in `content` — `content` must be non-empty on a DECLINE. The reason is part of the obligation: a DECLINE without explanation triggers a secondary obligation to explain (enforced in v3; validated as non-empty content in v1).

**When NOT to use:** As a soft "I'll get to it later." DECLINE terminates the obligation. If you intend to complete the task later, send STATUS instead.

**Receiver must:** Process the refusal and handle accordingly — find another agent, escalate, or accept the constraint. The burden returns to the original sender.

**Enterprise example — the human approval ambiguity:**
Currently a human writes "the indemnity clause needs work" and the agent interprets it as approval with minor notes. With typed messages: the human sends DECLINE (not proceeding) or RESPONSE (here's my feedback, proceed with it). The agent cannot misinterpret a DECLINE as a soft suggestion. The BARRIER on "send contract" only releases on DONE from an authorised reviewer.

---

#### HANDOFF
**One-liner:** Transfer your obligation to another agent. You step back; they take over.

**When to use:** You have accepted a COMMAND but the work properly belongs to another agent, or you need to delegate a subtask with full responsibility transfer. Always specifies `target` and typically includes `artefactRefs` for the context being transferred.

**When NOT to use:** As a way to copy-send a message. HANDOFF transfers obligation — the original receiver is discharged when the target accepts.

**Receiver (target) must:** Accept the obligation and proceed as if they received the original COMMAND. The commitment is now theirs.

**Note:** HANDOFF is a constitutive/declarative act — it changes the institutional reality by saying it. It is not just a message; it is a commitment transfer with normative force. `target` is validated non-null at the MCP tool level — a HANDOFF without a target is rejected before it reaches the store.

---

#### DONE
**One-liner:** Signal successful completion of a COMMAND.

**When to use:** You have completed the COMMAND obligation successfully. Always corresponds to an open COMMAND via `correlationId` or `inReplyTo`. May include `artefactRefs` pointing to the output.

**When NOT to use:** When the task failed. Use FAILURE instead. The distinction matters for audit — DONE means the obligation was fulfilled; FAILURE means it was not.

**Receiver must:** Nothing further — DONE discharges the obligation completely.

---

#### FAILURE
**One-liner:** Signal that you cannot complete a COMMAND. The obligation is terminated unsuccessfully.

**When to use:** The COMMAND could not be completed — error, timeout, blocked by policy, resource unavailable. Always requires a reason. Creates a secondary obligation: the failure must be explained and may trigger escalation.

**When NOT to use:** As a soft status. FAILURE is terminal — the obligation ends here. If you are still working, send STATUS.

**Receiver must:** Handle the failure — escalate, retry with a different agent, or accept the constraint. The infrastructure may auto-generate a secondary COMMAND to notify a supervisor agent.

**Enterprise example — the cascading override:**
Human operator sends VETO. Infrastructure fires defeasibility rule: all open COMMAND obligations in scope are defeated. Each agent receives FAILURE on their open commitment. No agent continues processing. The cascade happens in the infrastructure — no agent needs to understand it.

---

#### EVENT
**One-liner:** Structured telemetry. Not a communication act — a perlocutionary record.

**When to use:** Recording the observable effects of agent communication — tool calls, timing, downstream state changes. Observer-only: EVENT messages are excluded from agent context and never delivered to agents.

**When NOT to use:** To communicate between agents. EVENT is not a message — it is a record. Use QUERY, COMMAND, STATUS, or RESPONSE for inter-agent communication.

**Note:** EVENT is the perlocutionary layer of the framework — it records what happened as a result of other acts. The ledger preserves EVENTs permanently with SHA-256 tamper evidence. EVENT is explicitly excluded from the enforcement layer (Drools does not process EVENTs).

---

### 4.4 The isAgentVisible() Rule

```java
public boolean isAgentVisible() {
    return this != EVENT;
}
```

All types except EVENT are delivered to agent context. EVENT is observer-only. This rule is unchanged from the current design.

---

## 5. New Message Entity Fields

Three new fields added to `Message`:

```java
@Column(name = "commitment_id")
public UUID commitmentId;          // Links to CommitmentStore entry (auto-set on QUERY/COMMAND)

@Column(name = "deadline")
public Instant deadline;           // When obligation must be discharged (null = channel default)

@Column(name = "acknowledged_at")
public Instant acknowledgedAt;     // When obligation was explicitly accepted (null until ACK — v2)
```

**Schema change:** Three nullable columns added to the `message` table. No existing rows require migration — all three default to null.

**Auto-population rules (infrastructure):**
- `commitmentId`: auto-generated UUID on QUERY and COMMAND; for RESPONSE, STATUS, DECLINE, DONE, FAILURE — propagated from the originating QUERY/COMMAND via `correlationId` lookup (infrastructure sets this, sender does not); null for HANDOFF and EVENT
- `deadline`: set from channel configuration default when not provided by sender; null for types with no temporal obligation (STATUS, RESPONSE, EVENT)
- `acknowledgedAt`: null in v1; populated in v2 when ACK mechanism is implemented

---

## 6. MCP Tool Changes

### 6.1 `send_message` — new optional parameters

```
deadline    : ISO-8601 duration or Instant (optional; defaults to channel config)
```

`target` is already a parameter. `artefactRefs` is already a parameter. No other changes to the tool signature.

### 6.2 Type string parsing

Current: `MessageType.valueOf(type.toUpperCase())`

Unchanged — the new types (`QUERY`, `COMMAND`, `DECLINE`, `FAILURE`) parse naturally. `REQUEST` is removed — senders passing `"request"` will receive a validation error with a helpful message directing them to use `QUERY` or `COMMAND`.

### 6.3 LLM-assisted classification

The MCP tool description for `messageType` must be written to enable accurate LLM classification without developer knowledge of the framework:

```
messageType: The type of message. Use:
  QUERY — when asking for information (no action expected)
  COMMAND — when asking for action to be taken (side effects expected)
  RESPONSE — when answering a QUERY
  STATUS — to report progress on a COMMAND while still working
  DECLINE — to refuse a QUERY or COMMAND you cannot fulfil (reason required)
  HANDOFF — to transfer your obligation to another agent (target required)
  DONE — to signal successful completion of a COMMAND
  FAILURE — to signal unsuccessful termination of a COMMAND (reason required)
  EVENT — for telemetry only (not delivered to agents)
```

This description should be validated against LLM classification accuracy before v1 ships.

---

## 7. Breaking Changes and Migration

### 7.1 REQUEST is removed

`MessageType.REQUEST` is deleted. There is no deprecation period — this is a pre-Quarkiverse extension with no external consumers.

**Migration for each REQUEST usage:**
- "Give me information" → `QUERY`
- "Do something for me" → `COMMAND`
- Ambiguous (both possible) → resolve by intent; default to `COMMAND` if action is expected

### 7.2 Consumer impact

**QhorusMcpTools.java:**
- Line 453: correlation_id auto-generation rule changes from `if (msgType == REQUEST)` to `if (msgType == QUERY || msgType == COMMAND)`
- Lines 437, 445: ACL/rate-limit bypass for EVENT unchanged
- Line 533: ledger recording for EVENT unchanged
- A2A `deriveState()`: RESPONSE/DONE → "completed"; STATUS → "working"; FAILURE → "failed"; else → "submitted"

**Tests:**
- 40 REQUEST usages → classify to QUERY or COMMAND by intent
- No mechanical find-replace — each must be assessed

**Claudony:**
- Named datasource already updated. MessageType references need the same REQUEST → QUERY/COMMAND classification.

### 7.3 What is NOT breaking

- `RESPONSE`, `STATUS`, `HANDOFF`, `DONE`, `EVENT` — unchanged semantically
- `isAgentVisible()` — unchanged behaviour
- `MessageQuery.excludeTypes` — unchanged interface
- `distinctSendersByChannel(UUID, MessageType)` — unchanged interface
- Channel semantics (BARRIER, COLLECT, LAST_WRITE, APPEND, EPHEMERAL) — unchanged

---

## 8. What Is Explicitly Deferred

### v2: CommitmentStore

Generalise `PendingReply` into a full `CommitmentStore` tracking all QUERY and COMMAND obligations, not just REQUEST→RESPONSE pairs.

New tracked fields: `createdAt` (exists), `deadline` (v1 adds to Message), `acknowledgedAt` (v1 adds to Message), `fulfilledAt`, `cancelledAt`, `delegatedTo`.

Nothing in the v1 design prevents this. `commitmentId` in the Message entity is the bridge — it links each message to its eventual CommitmentStore entry.

### v2: Normative ledger entries

Expand `LedgerWriteService` to record all normatively significant types (COMMAND, DECLINE, FAILURE, HANDOFF, DONE) not just EVENT. The `commitmentId` field links ledger entries to CommitmentStore for the full obligation lifecycle record.

### v3: `quarkus-qhorus-rules` module

Optional Drools enforcement extension. Implements the Layer 4 enforcement rules from the master table in Section 4.2. Works without LLM cooperation — purely infrastructure. Secondary obligation generation, temporal CEP, defeasible norm enforcement.

### Deferred types: APPROVAL, VETO, REDIRECT

Human-in-the-loop specific types. Deferred until the HITL design is settled. Nothing in the v1 type system prevents adding them — the `isAgentVisible()` method and the deontic framework accommodate them cleanly.

### Deferred: ACK as first-class type

Currently STATUS absorbs both "obligation accepted" and "working on it." An explicit ACK type would separate commitment acceptance from commitment progress. Deferred to v2 — the `acknowledgedAt` field in v1 is the bridge.

---

## 9. Theoretical Foundation

The taxonomy is formally grounded in a four-layer normative framework. The full theoretical derivation, formal definitions, and completeness argument are documented in **ADR-0005** (to be written).

ADR-0005 covers:
1. Illocutionary act taxonomy — Searle category for each type and why
2. Deontic effects — what obligation each type creates, transfers, or discharges
3. Defeasibility rules — which obligations can be defeated, by what, under what conditions
4. Commitment operations — formal `C(debtor, creditor, antecedent, consequent)` for each type
5. Temporal semantics — deadline, ordering, duration, expiry rules
6. Completeness argument — proof that the 9-type taxonomy covers the full obligation lifecycle state space with no gaps or overlaps

The Javadoc and MCP tool descriptions do not reference the ADR. Developers interact with the developer-facing definitions in Section 4.3. The ADR is for architects, auditors, and academic collaborators.

---

## 10. LLM-Assisted Envelope Population

LLMs are better than humans at populating formal logical structures consistently at scale. The framework relies on this:

**At message send time:** An LLM agent calling `send_message` chooses `messageType` from the MCP tool description. With a well-designed description (Section 6.3), LLM classification accuracy should be high. This should be measured before v1 ships — run 50 realistic agent scenarios through an LLM, check classification against expected types.

**At policy authoring time:** An LLM assistant can generate Drools enforcement rules from natural language workflow descriptions. This is v3 territory but the taxonomy must support it — each type must be expressible as a rule condition (`on COMMAND` / `on DECLINE` etc.).

**The classification accuracy test** is also the empirical validation that the taxonomy is correctly granular: if two types are consistently confused by LLMs, consider merging; if one type is consistently split by LLMs into multiple behaviours, consider splitting.

---

## 11. Policy and Compliance Architecture

The typed taxonomy enables a semi-autonomous, accountable governance architecture:

**Semi-autonomous:** LLMs operate without human intervention at runtime. Infrastructure enforces policy rules. Violations trigger automatic responses. Humans set policies and can override via VETO/HANDOFF.

**Accountable:** Every normatively significant act is attributed, typed, timestamped, and ledgered. "Who authorised this?" is a database query.

This architecture is applicable to EU AI Act compliance for high-risk AI systems:

| EU AI Act requirement | Framework provision |
|---|---|
| Article 9: Risk management | Policy rules (v3 Drools) approved by humans |
| Article 13: Transparency | All acts typed, attributed, ledgered |
| Article 14: Human oversight | VETO + HANDOFF-to-human built into the type system |
| Article 17: Quality management | CommitmentStore (v2) tracks obligation fulfilment rates |

---

## 12. Open Questions

1. **`deadline` units:** ISO-8601 duration relative to `createdAt`, or absolute `Instant`? Recommendation: store as `Instant` (computed at send time from duration + `createdAt`), expose as duration in MCP tools for developer convenience.

2. **FAILURE auto-generation:** When infrastructure generates a FAILURE on deadline expiry (v3), what is the `sender` field? Recommendation: a reserved `system` sender identifier, distinct from agent senders.

3. **HANDOFF acceptance:** Does a HANDOFF require explicit ACK from the target, or is institutional authority assumed? Recommendation for v1: institutional authority assumed (no ACK required). Revisit in v2 when ACK type is considered.

4. **`commitmentId` for RESPONSE/DONE/FAILURE:** Resolved — infrastructure propagates `commitmentId` from the originating QUERY/COMMAND via `correlationId` lookup (see Section 5). Senders do not need to set this.

---

*Spec ready for review. Once approved, proceed to ADR-0005 and implementation plan.*
