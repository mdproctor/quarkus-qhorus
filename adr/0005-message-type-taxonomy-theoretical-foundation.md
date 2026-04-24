# 0005 — MessageType Taxonomy: Theoretical Foundation

Date: 2026-04-23
Status: Accepted

## Context and Problem Statement

The original `MessageType` enum had six values: REQUEST, RESPONSE, STATUS,
HANDOFF, DONE, EVENT. This taxonomy conflated communication function with
workflow role. REQUEST in particular collapsed two categorically distinct acts:
asking for information (no side effects) and asking for action (side effects
expected). Receivers had to parse message content to determine how to handle an
incoming message — which is unreliable and pushes normative reasoning into the
LLM.

The empirical cost is documented: the MAST failure taxonomy (1,600 annotated
traces across AutoGen, CrewAI, and LangGraph) found that 36.9% of all
multi-agent failures were inter-agent communication misalignment — breakdowns
from absent semantic enforcement.

The question: is there a principled theoretical basis for a richer taxonomy that
(a) is formally complete, (b) maps unambiguously to implementation behaviour, and
(c) is classifiable by an LLM from natural language context without requiring
developers to understand the theory?

---

## Decision

Replace the 6-type enum with a 9-type taxonomy derived from a four-layer
normative framework. The types are:

**QUERY, COMMAND, RESPONSE, STATUS, DECLINE, HANDOFF, DONE, FAILURE, EVENT**

The taxonomy is grounded in four complementary theoretical frameworks — not
competing alternatives, but orthogonal perspectives on the same underlying
phenomenon of agent communication:

| Layer | Framework | Question answered |
|---|---|---|
| 1 — Normative | Speech act theory + deontic logic + defeasible reasoning | What does this message *mean*? What obligation does it create or discharge? |
| 2 — Social commitment | Singh's commitment semantics | Who owes *what* to *whom*? |
| 3 — Temporal | Deadline, ordering, duration, expiry | *When* must the obligation be fulfilled? |
| 4 — Enforcement | Defeasible rules + rule engine (v3) | What happens when obligations are not met? |

---

## Theoretical Foundation

### Layer 1: Normative (Speech Acts + Deontic Logic + Defeasible Reasoning)

Speech act theory (Austin, Searle) identifies three levels of every
communicative act:

- **Locutionary**: the act of saying something with a literal meaning
- **Illocutionary**: the intended communicative function — what you are *doing*
  by saying it
- **Perlocutionary**: the effect on the listener

Agent communication is almost exclusively concerned with illocutionary acts.
Searle's five illocutionary categories provide the classification grid:

- **Directives**: getting someone to act or inform (QUERY, COMMAND)
- **Assertives**: committing to truth of something (RESPONSE, DECLINE, STATUS)
- **Commissives**: committing to a future action (STATUS)
- **Declarations**: changing state by saying it (HANDOFF, DONE, FAILURE)
- **Expressives**: psychological states — not relevant for machine agents

**Deontic logic** provides the semantic grounding. Instead of FIPA-ACL's
mentalistic BDI semantics (Beliefs, Desires, Intentions — unverifiable for LLMs),
each message type is defined by the deontic effects it produces:

- **O(p)**: it is *obligatory* that p
- **P(p)**: it is *permitted* that p
- **F(p)**: it is *forbidden* that p

Sending a COMMAND places the receiver under an obligation to respond or decline.
Sending a RESPONSE discharges that obligation. This is trackable and verifiable
without attributing mental states to any participant.

**Defeasible deontic logic** (Governatori et al.) handles the contrary-to-duty
problem: obligations are not absolute. A HANDOFF *defeats* the original receiver's
obligation; a VETO defeats any open obligation in scope. Standard monotonic
deontic logic cannot represent this; defeasible reasoning handles it naturally.

### Layer 2: Social Commitment Semantics (Singh)

Singh's commitment semantics provides a complementary perspective: instead of
asking "what does this message mean?", ask "who owes what to whom?".

The primitive is: **C(debtor, creditor, antecedent, consequent)**

> The debtor is committed to the creditor: *if* the antecedent holds, *bring
> about* the consequent.

Operations: **create**, **cancel** (by debtor), **release** (by creditor),
**delegate** (transfer to a third party).

A protocol is then a set of commitments and their operations. This framework is
the semantic foundation for the `CommitmentStore` — a live registry of open,
fulfilled, cancelled, and delegated commitments.

The key insight: Layer 1 (normative) and Layer 2 (social commitment) are not
competing frameworks — they answer different questions. Layer 1 defines what
each message *means*; Layer 2 tracks the social obligations that meaning creates.
Layer 1 generates Layer 2: each illocutionary act triggers commitment operations.

### Layer 3: Temporal

Neither Layer 1 nor Layer 2 captures the *when* dimension. Temporal logic adds:

- **Deadline**: obligation must be discharged within T
- **Ordering**: RESPONSE is only valid after the QUERY it correlates to
- **Duration**: STATUS is only valid while a COMMAND obligation is open
- **Expiry**: an unanswered QUERY becomes auto-DECLINED after T

These constraints are encoded in the `Message.deadline` field (added in this
redesign) and will be enforced by the rule engine in v3.

### Layer 4: Enforcement (v3)

The defeasibility rules are stated formally in this ADR but will be executed by
an optional Drools extension (`quarkus-qhorus-rules`, v3). The key principle:
normative enforcement is the mesh's responsibility, not the LLM's. An LLM that
sends the wrong message type (or fails to send one at all) will be corrected by
infrastructure, not by another LLM's judgment.

---

## Complete Type Definitions

### Deontic Effects and Commitment Operations

| Type | Searle category | Deontic effect | Commitment operation |
|---|---|---|---|
| `QUERY` | Directive (epistemic) | Creates: receiver obligated to inform or DECLINE | Creates `C(receiver→sender, inform_result)` |
| `COMMAND` | Directive (action) | Creates: receiver obligated to execute or DECLINE | Creates `C(receiver→sender, execute_and_report)` |
| `RESPONSE` | Assertive | Discharges: QUERY obligation | Discharges `C` matching `correlationId` |
| `STATUS` | Commissive | Extends: open COMMAND obligation window | No new commitment; extends deadline |
| `DECLINE` | Assertive (negative) | Discharges: obligation by refusal; creates secondary: explain_reason | Cancels `C`; creates `C(receiver→sender, explain_reason)` |
| `HANDOFF` | Declarative (constitutive) | Transfers: obligation to named target; original discharged | Delegates `C`; new `C(target→creditor)` created |
| `DONE` | Declarative (success) | Discharges: COMMAND obligation — successful | Fulfils `C` — terminal |
| `FAILURE` | Declarative (terminal) | Discharges: COMMAND obligation — unsuccessful; creates secondary: explain_failure | Cancels `C` by inability; defeasible secondary obligations may activate |
| `EVENT` | Perlocutionary record | None — no deontic footprint | No commitment created or discharged |

### Defeasibility Rules

These rules govern which obligations can be overridden:

| Rule | Effect |
|---|---|
| HANDOFF defeats original receiver's COMMAND obligation | The named target inherits the obligation; original receiver is discharged |
| VETO (future type) defeats any open obligation in channel scope | All open obligations terminated regardless of execution state |
| New COMMAND on same `correlationId` defeats previous COMMAND | Supersession: previous obligation replaced |
| DECLINE triggers secondary: `C(receiver→sender, explain_reason)` | Contrary-to-duty obligation: refusal must be explained |
| FAILURE triggers secondary: `C(receiver→sender, explain_failure)` | Contrary-to-duty obligation: failure must be explained |
| Deadline expiry triggers: infrastructure generates FAILURE | Temporal enforcement: overdue obligation terminated by the mesh |

### Temporal Semantics

| Type | Deadline behaviour | Ordering constraint | Duration constraint |
|---|---|---|---|
| `QUERY` | Deadline from channel config; expiry → auto-DECLINE | None | While QUERY obligation open |
| `COMMAND` | Deadline from channel config; expiry → auto-FAILURE | None | While COMMAND obligation open |
| `RESPONSE` | Inherits QUERY deadline | Must follow matching QUERY | Until QUERY obligation discharged |
| `STATUS` | None (extends COMMAND deadline) | Must follow open COMMAND | While COMMAND obligation open |
| `DECLINE` | Secondary obligation (explain) has its own deadline | After QUERY or COMMAND | Terminal |
| `HANDOFF` | Target inherits original deadline unless overridden | After COMMAND | Terminal |
| `DONE` | Recorded against deadline | After COMMAND | Terminal |
| `FAILURE` | Recorded against deadline; may be deadline-triggered | After COMMAND | Terminal |
| `EVENT` | None | None | None |

---

## HANDOFF as Constitutive Act

HANDOFF deserves special treatment. It is a *constitutive* speech act in the
sense of Demolombe & Louis (DEON 2006): it changes the institutional reality by
saying it. Unlike assertives (which describe a state) or directives (which
request an action), HANDOFF creates a new institutional fact — a transfer of
obligation with normative force.

This is why `target` is a required field (validated non-null at the MCP tool
layer), and why HANDOFF is terminal for the original sender. The commitment is
not just forwarded — it is transferred. The original debtor is discharged; the
named target becomes the new debtor.

---

## EVENT as Perlocutionary Record

EVENT has no deontic footprint — it creates no obligations, discharges none, and
participates in no commitment operations. It is excluded from agent context
(`isAgentVisible()` returns false) and from the enforcement layer (the rule
engine does not process EVENTs).

EVENT is the *perlocutionary* layer of the framework: it records the observable
effects of agent communication acts on downstream state. Austin's perlocutionary
act — the effect produced in the listener by saying something — has no equivalent
in FIPA-ACL and no explicit treatment in most agent communication frameworks.
In Qhorus, EVENT is the perlocutionary record: what actually happened as a result
of the communicative acts in the channel. The ledger (via `LedgerWriteService`)
preserves EVENTs permanently with SHA-256 tamper evidence.

---

## Completeness Argument

The 9-type taxonomy is complete over the obligation lifecycle state space. The
possible states of an obligation `C(debtor, creditor, antecedent, consequent)` are:

1. **Created** — by QUERY or COMMAND
2. **Extended** — by STATUS (renews the deadline without discharging)
3. **Fulfilled** — by RESPONSE (for QUERY) or DONE (for COMMAND)
4. **Refused** — by DECLINE (never accepted; triggers explain obligation)
5. **Failed** — by FAILURE (accepted but not completed; triggers explain obligation)
6. **Delegated** — by HANDOFF (transferred to a new debtor)
7. **Not a communication act** — EVENT (perlocutionary record only)

Every message type maps to exactly one lifecycle transition. No transition is
unrepresented. No type covers two transitions. The taxonomy is both complete and
non-overlapping over the obligation lifecycle.

---

## Relationship to Prior Work

### FIPA-ACL (Foundation for Intelligent Physical Agents, 2000)

FIPA-ACL defined approximately 22 communicative acts grounded in BDI (Belief,
Desire, Intention) modal logic semantics. The BDI semantic layer is correct in
structure but wrong in application to LLMs: mental state attribution to a
transformer is incoherent, and BDI preconditions are unverifiable in open
systems.

Qhorus keeps what FIPA got right:
- Structured performative metadata (envelope) separate from payload (content)
- Explicit typed taxonomy with distinct illocutionary categories

Qhorus discards what FIPA got wrong:
- BDI semantics → replaced with deontic + social commitment semantics
- Shared ontology requirement → content is free natural language text
- 22 performatives → 9 types with complete obligation lifecycle coverage

### Singh's Social Commitment Semantics (2000–2025)

Singh's school treats social commitments as the primitive, grounding ACL
semantics in observable social obligations rather than unverifiable mental states.
Qhorus adopts this framework as Layer 2: each message type's commitment
operations are formally specified using Singh's `C(debtor, creditor, antecedent,
consequent)` notation. The `CommitmentStore` is a direct instantiation of
Singh's commitment machine concept.

### Governatori's Defeasible Deontic Logic (2004–2024)

Governatori et al.'s DDL handles contrary-to-duty norms and norm revision with
linear computational complexity. The defeasibility rules in this ADR follow DDL's
superiority relation approach: when two norms conflict, the superior one prevails.
DECLINE defeating the original QUERY obligation, and VETO defeating all open
obligations in scope, are superiority relations in this sense.

### Kibble's Brandomian Scorekeeping (2006)

Kibble's application of Brandom's normative pragmatics frames dialogue states as
*deontic scoreboards* tracking commitments (what you are responsible for) and
entitlements (what you are justified in asserting). This is the most
philosophically coherent foundation but the least implemented. The open problem
— combining Brandom's scorekeeping model with defeasible logic computationally —
is partially instantiated by Qhorus: the `CommitmentStore` tracks commitments;
the defeasibility rules provide the revision mechanism.

### Modern LLM Frameworks (AutoGen, LangGraph, CrewAI, A2A, NLIP)

No current LLM multi-agent framework uses an explicit speech-act taxonomy. The
2026 survey "Beyond Message Passing: A Semantic View of Agent Communication
Protocols" (arXiv:2604.02369, Yuan et al.) — which analyses 18 protocols —
identifies the absence of semantic-layer enforcement as a systemic gap and calls
for protocol-level mechanisms for clarification, context alignment, and
verification. Qhorus's 9-type taxonomy addresses that gap.

NLIP (ECMA-430, December 2025) took the opposing design choice: delegate
speech-act classification to the LLM. Qhorus's position is that for reliability,
auditability, and enterprise compliance, semantic enforcement must be in the
infrastructure, not the LLM.

---

## Two-Part Message Structure

A consequence of this theoretical framework is the explicit separation of every
message into two parts:

```
COMMITMENT ENVELOPE (structured, infrastructure-reads, Drools-processable)
  messageType    — illocutionary type
  commitmentId   — links to CommitmentStore entry (auto-set by infrastructure)
  correlationId  — auto-generated for QUERY and COMMAND
  deadline       — when obligation must be discharged (from channel config)
  sender, target, channelId, artefactRefs, inReplyTo
  acknowledgedAt — when obligation was explicitly accepted (populated by CommitmentService.acknowledge())

LLM PAYLOAD (free text, opaque to infrastructure)
  content        — natural language message
```

The infrastructure operates exclusively on the envelope. It never reads `content`.
The LLM operates on `content`. The LLM is responsible for three things: choosing
`messageType` correctly, writing `content`, and optionally setting `target`,
`deadline`, and `artefactRefs` when relevant. Everything else is set or validated
by the infrastructure.

LLMs are better than humans at populating formal structured metadata consistently
at scale. The taxonomy is designed to be classifiable from natural language
context — this is the empirical validation target: ≥ 80% classification accuracy
for each type using a 1B parameter model (`Llama-3.2-1B-Instruct` via Jlama).
See `examples/agent-communication/ClassificationAccuracyTest.java`.

---

## Policy and Compliance Implications

The layered normative framework enables two distinct enterprise properties from
the same infrastructure:

**Policy-driven behaviour (prospective):** The mesh enforces correct behaviour at
runtime before harm occurs. Agents cannot deviate from approved workflows because
the infrastructure prevents it — not by trusting the LLM to reason correctly, but
by validating the typed envelope and enforcing defeasibility rules.

**Compliance demonstrability (retrospective):** Every normatively significant act
is attributed, typed, timestamped, and recorded in the tamper-evident ledger.
"Who authorised this?" is a database query, not log archaeology.

These properties map to EU AI Act requirements for high-risk AI systems:

| EU AI Act | Qhorus provision |
|---|---|
| Art. 9: Risk management | Policy rules (v3 Drools) approved by humans |
| Art. 13: Transparency | All acts typed, attributed, ledgered |
| Art. 14: Human oversight | VETO + HANDOFF-to-human built into the type system |
| Art. 17: Quality management | CommitmentStore tracks obligation fulfilment rates |

---

## Key References

- Austin, J.L. *How to Do Things with Words* (1962)
- Searle, J.R. *Speech Acts* (1969)
- Brandom, R. *Making It Explicit* (1994)
- Singh, M.P. "A Social Semantics for Agent Communication Languages" (IJCAI 1999)
- Kibble, R. "Speech Acts, Commitment and Multi-Agent Communication" (*CMAOT* 12(2), 2006)
- Governatori, G. & Rotolo, A. "Defeasible Logic: Agency, Intention and Obligation" (DEON 2004)
- Governatori, G. "Practical Normative Reasoning with Defeasible Deontic Logic" (2018)
- Demolombe, R. & Louis, V. "Speech Acts with Institutional Effects in Agent Societies" (DEON 2006)
- Yuan et al. "Beyond Message Passing: A Semantic View of Agent Communication Protocols" (arXiv:2604.02369, 2026)
- FIPA Communicative Act Library Specification (SC00037J)

---

## Consequences

- `MessageType` enum has 9 values with formal Javadoc encoding the deontic semantics
- `Message` entity carries `commitmentId`, `deadline`, `acknowledgedAt` as envelope fields
- MCP tools validate `requiresContent()` (DECLINE, FAILURE) and `requiresTarget()` (HANDOFF) at call time
- `CommitmentStore` tracks the full obligation lifecycle using Singh's model
- `quarkus-qhorus-rules` (v3) will enforce the defeasibility rules via Drools
- HITL types (APPROVAL, VETO, REDIRECT) are deferred until the human-in-the-loop design is settled
- ACK as a first-class type is deferred to v2; `acknowledgedAt` field bridges the gap

## Implementation

- `MessageType.java` — 9 enum values with helper methods `requiresCorrelationId()`,
  `requiresContent()`, `requiresTarget()`, `isTerminal()`
- `Message.java` — `commitmentId`, `deadline`, `acknowledgedAt` fields
- `QhorusMcpTools.java` / `ReactiveQhorusMcpTools.java` — validation and `deadline` parameter
- `examples/agent-communication/` — LLM classification accuracy baseline
