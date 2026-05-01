# Work and WorkItems in the Normative Layer

*This document builds on [normative-layer.md](normative-layer.md) and [agent-mesh-framework.md](agent-mesh-framework.md). Read those first. This document adds Layer 2 of the ecosystem stack — casehub-work — to the normative picture established there, and examines the principled boundary between the machine-agent layer and the human-agent layer.*

---

## Two Layers, Not One System

The Qhorus normative layer — its 9-type speech act taxonomy, the CommitmentStore, the three-channel layout — is designed for machine agents. That is not a limitation. It is a deliberate scope decision. Machines do not pause. They do not take on an obligation and set it aside while they attend to something else. When a machine agent cannot proceed it fails, delegates, or emits STATUS. The commitment lifecycle is correspondingly minimal: OPEN → ACKNOWLEDGED → one terminal state. The taxonomy is complete for the agents it addresses.

**casehub-work is not filling gaps in that taxonomy. It is the human-agent layer** — a formal, principled extension of the Qhorus normative model for obligations that are held, transferred, and eventually resolved on human timescales. The differences between WorkItem and Qhorus are not mismatches to reconcile. They are the deliberate boundary between two coherent layers.

```
Qhorus core          — machine-agent normative layer (complete for machines)
casehub-work         — human-agent normative extension
```

The extension is clean because it targets a coherent domain — human agency under obligation — and introduces nothing that conflicts with, overlaps, or duplicates the machine layer below.

---

## The Distinction That Matters

The normative layer uses "work" to mean any goal-directed activity commissioned by a COMMAND and discharged by DONE, FAILURE, or DECLINE. The obligation is what matters; the work is what fulfils it.

A **WorkItem** is something different. It is not the obligation. It is the mechanism by which an obligation is fulfilled when the agent is human.

The distinction is not duration. A machine agent can wait hours for a long-running process, poll for an external signal, or block on a BARRIER channel — it may hold an obligation open for a long time. What it does not do is hold an obligation *discontinuously*. A machine agent is either present and actively working its obligation, or it has formally released it via DONE, FAILURE, DECLINE, or HANDOFF. It does not set the obligation aside, switch to other work, and return later. It does not pause without either completing or transferring.

A human agent does exactly this. They receive a task, may set it aside while attending to something else, may partially delegate while retaining accountability, may be interrupted and return. The obligation is held discontinuously — across gaps in attention, across handoffs where the original holder remains accountable, across pauses that are neither failures nor transfers. That discontinuous holding is what the machine-agent model correctly omits and what the human-layer model must accommodate.

**Work** is the obligation. **WorkItem** is the human-layer implementation of an obligation held discontinuously.

---

## WorkItems Speak the Same Language

The Qhorus 9-type speech act taxonomy is not specific to machines. It describes communicative acts between any agents — including humans. When a WorkItem changes state, it is performing a speech act. The mapping is direct:

| WorkItem transition | Speech act | Normative meaning |
|---|---|---|
| Created → PENDING | Directive issued | An obligation has been commissioned; a human must act |
| PENDING → ASSIGNED | ACKNOWLEDGED | The obligation has been accepted; the assignee is now accountable |
| ASSIGNED → IN_PROGRESS | STATUS | Assertive: I am actively working on this; extend the window |
| IN_PROGRESS → DELEGATED | HANDOFF (see below) | Commissive: I am transferring this obligation |
| ASSIGNED/IN_PROGRESS → REJECTED | FAILURE or DECLINE | Tried and could not complete (FAILURE); reasoned refusal (DECLINE) |
| IN_PROGRESS → COMPLETED | DONE | Assertive fulfillment: I have discharged what was asked of me |
| → EXPIRED → ESCALATED | EXPIRED | Obligation passed its deadline with no terminal resolution |

The WorkItem lifecycle is not a status machine layered on top of Qhorus. It *is* the Qhorus commitment lifecycle, extended for obligations held discontinuously. The `OPEN → ACKNOWLEDGED → FULFILLED` path in the CommitmentStore maps onto `PENDING → ASSIGNED → COMPLETED`. The machine layer collapses the middle because a machine agent is either present and working or has formally released — there is no discontinuity to track. The human layer exposes it because discontinuous holding is the norm, not the exception.

---

## The Principled Boundary: Where the Layers Diverge

Two WorkItem concepts have no direct equivalent in the machine-agent layer. These are not oversights in Qhorus — they are correct omissions at the machine level that become necessary additions at the human level.

### SUSPENDED: a human-layer addition

When a human suspends a WorkItem — "I have taken this on but must pause" — they are neither failing nor delegating. They are holding the obligation while pausing active work. This state does not exist in the Qhorus commitment model, and correctly so: machine agents do not suspend. They execute or they stop.

SUSPENDED is a human-layer act. In the extension contract, it sits alongside DONE and FAILURE as a valid human-layer resolution of the IN_PROGRESS state — but unlike those, it is reversible, returning to IN_PROGRESS on resume.

### DELEGATED vs HANDOFF: richer accountability for humans

In Qhorus, HANDOFF transfers the obligation and releases the original obligor. Clean accountability boundary — the machine agent that hands off is done.

WorkItem DELEGATED retains the original assignee as `owner`. They remain accountable even after transfer. The delegation chain is recorded and ownership persists through it. This distinction — **transfer** (HANDOFF: releases) vs **sub-delegation** (DELEGATED: retains) — is a real concept in deontic logic, and it matters in practice for regulated environments. The person who delegated a £180,000 payment decision remains in the accountability chain even after the senior adjuster took it over.

Both are valid normative acts. The machine layer needs only transfer. The human layer needs both, and adds sub-delegation as a distinct act.

---

## The Extension Contract

These additions follow a deliberate design principle: the human layer must fit against the machine layer with no overlap, no conflict, and no redundancy. Every human-layer act either maps to an existing machine-layer act (WorkItem COMPLETED = DONE) or introduces a concept that has no machine-layer equivalent and no semantic overlap with any existing type (SUSPENDED, sub-delegation).

This also defines a path for future extension:

- **Community implementations** building human-layer or domain-specific extensions declare their extension types formally, proving non-overlap with core before use
- **Extensions that prove broadly applicable** — if SUSPENDED or sub-delegation turn out to be useful beyond human agents, across multiple independent implementations — become candidates for promotion into Qhorus core
- **Core evolves through demonstrated need**, not anticipation

For this to work, Qhorus must define the extension contract from the start: type namespacing (so `casehub-work.SUSPEND` cannot conflict with a future Qhorus `SUSPEND`), an extension registry (so the CommitmentStore and ledger handle declared extension types cleanly), and a promotion path (so an extension can graduate to core without breaking existing implementations).

The casehub-work human layer is the first test case for that contract.

---

## WorkItems Are the Oversight Channel Materialised

The `NormativeChannelLayout` from [agent-mesh-framework.md](agent-mesh-framework.md) defines three channels per case:

```
case-{caseId}/
├── work        (APPEND, all types) — agent coordination
├── observe     (APPEND, EVENT only) — telemetry
└── oversight   (APPEND, QUERY+COMMAND only) — human governance
```

The oversight channel is where human participation enters the system as a first-class normative act. But the framework describes the human as posting directly: "Human posts COMMAND on oversight channel." In practice, that is rarely how human judgment works. Humans need context, time to consider, possibly consultation — and a mechanism to receive and respond to requests without being connected to the agent mesh at the moment the QUERY arrives.

A WorkItem is that mechanism. When an agent posts to `oversight/QUERY`:

1. A WorkItem is created — PENDING — in the human's inbox
2. The human claims it — ASSIGNED (ACKNOWLEDGED)
3. The human reviews, possibly starts (IN_PROGRESS)
4. The human resolves it — COMPLETED (DONE)
5. The WorkItem completion drives `oversight/COMMAND` back into the mesh

The WorkItem IS the oversight channel made tangible for human time. The agent does not need to wait for the human to be online. The human does not need to know anything about Qhorus channels. The normative record is complete: the QUERY exists on the oversight channel, causally linked to the WorkItem creation, causally linked to the COMMAND that resulted from its completion.

Layer 1 (Qhorus alone) has synthetic agents posting on the oversight channel. Layer 2 (+casehub-work) replaces those synthetic posts with real human work, tracked with the same normative semantics, feeding back into the same channel infrastructure.

---

## Cross-Channel Correlation: The Unified Normative Narrative

The three-channel layout creates normative clarity — obligations separated from telemetry, governance separated from coordination. The cost: the unified causal narrative across all three channels is not directly queryable. The `causedByEntryId` link runs within a channel. When an oversight COMMAND causes a work DONE, that cross-channel causal relationship has no structural representation.

The normative narrative of a case spans all three channels:

```
work/COMMAND    "reviewer-001 → analyse contract §4.2"           [directive]
  observe/EVENT   tool: parse_pdf, tokens: 4,521                 [telemetry]
  observe/EVENT   tool: extract_clauses, count: 47               [telemetry]
  oversight/QUERY "ambiguous liability clause — approve reading?" [directive to human]
    WorkItem PENDING                                             [directive materialised]
    WorkItem ASSIGNED  (human claims it)                        [ACKNOWLEDGED]
    WorkItem IN_PROGRESS                                         [STATUS]
    WorkItem COMPLETED (human: strict reading approved)          [DONE]
  oversight/COMMAND "strict reading approved"                    [assertive]
work/DONE       "analysis complete, recommendation attached"     [assertive]
```

Each row is in a different channel. The current infrastructure reconstructs each channel's history independently. The merged causal view across all three is not yet available in a single query.

**Cross-channel correlation** restores this view without collapsing the channel separation. Each channel maintains its own tamper-evident hash chain (integrity preserved); cross-channel causal links are UUID references to entries in other chains, not embeddings. The DAG of causal provenance spans three independent chains — verifiable per-chain, navigable across chains.

The practical query this enables: given a `correlation_id` or a WorkItem UUID, return the complete normative narrative — every machine obligation, every observe EVENT, every human governance act — in causal order. Not three separate timelines. One story.

This is also what makes ProvenanceLink (the PROV-O causal graph across agents, cases, and WorkItems) tractable. The causal links already exist in each channel's ledger. Cross-channel correlation is the infrastructure that makes them navigable as a unified graph.

---

## The Secure Code Review, Revisited at Layer 2

The example in [agent-mesh-framework.md](agent-mesh-framework.md) Part 8 ends with a human posting to the oversight channel. Here is what that looks like with casehub-work added.

**Layer 1 — Reviewer escalates to human:**

```
oversight/QUERY  reviewer-001 → "Found potential data leak in auth module.
                                 Approve deployment hold?"
[...human posts COMMAND directly — synthetic in Layer 1...]
oversight/COMMAND human → "Hold approved. Escalate to security team."
```

**Layer 2 — Same scenario with casehub-work:**

```
oversight/QUERY  reviewer-001 → "Found potential data leak in auth module.
                                 Approve deployment hold?"
                                 [WorkItem created: PENDING]
                                 [Priority: CRITICAL  Category: security-review]
                                 [Candidate group: security-engineers]
                                 [Claim SLA: 4h  Completion SLA: 24h]

                 [alice claims → WorkItem: ASSIGNED (ACKNOWLEDGED)]
                 [alice reviews → WorkItem: IN_PROGRESS (STATUS)]
                 [alice: cannot confirm without senior sign-off]
                 [alice delegates → WorkItem: DELEGATED → bob (sub-delegation: alice retains as owner)]
                 [bob claims, reviews → WorkItem: ASSIGNED → IN_PROGRESS]
                 [bob: confirmed data leak, hold approved]
                 [WorkItem: COMPLETED (DONE)]

oversight/COMMAND bob → "Deployment hold approved. CVE-2026-04-789 filed."
```

What changes at Layer 2:

- The human act has a lifecycle — claim, review, delegate, resolve — each step a first-class normative act in the ledger
- Alice's delegation to Bob is a sub-delegation (not a HANDOFF): alice remains as `owner`, accountable in the chain even after bob resolves it
- If alice had not claimed within 4 hours, the WorkItem would have EXPIRED and surfaced in `list_stalled_obligations` alongside any machine COMMANDs with no DONE — human and machine obligations share the same stall-detection infrastructure
- The `oversight/COMMAND` from WorkItem completion carries `callerRef` — routing the outcome back to the exact obligation that issued the original QUERY without the mesh needing to know WorkItem internals

The causal chain in the normative ledger:

```
oversight-ledger:
  seq=1  QUERY    reviewer-001   "Approve deployment hold?"       causedBy: work/COMMAND(seq=47)
  seq=2  COMMAND  bob            "Deployment hold approved."      causedBy: oversight/seq=1

observe-ledger:
  seq=88  EVENT   tool: security-scan, duration: 4,241ms          causedBy: work/COMMAND(seq=47)

workitem-ledger (casehub-work audit):
  CREATED   by reviewer-001 (via Qhorus)          directive issued
  ASSIGNED  alice                                  ACKNOWLEDGED
  IN_PROGRESS alice                                STATUS
  DELEGATED alice → bob  (alice retains as owner)  sub-delegation
  ASSIGNED  bob                                    ACKNOWLEDGED
  IN_PROGRESS bob                                  STATUS
  COMPLETED bob   "CVE filed, hold approved"       DONE
```

Six months later: "Who approved the hold, what was the escalation path, and who bears accountability?" The answer is in the ledger — built as the system ran, not assembled from logs after the fact.

---

## What This Means for the Layered Ecosystem

The four-layer table from [normative-layer.md](normative-layer.md), with Layer 2 now fully specified:

| Layer | Added | What it brings |
|---|---|---|
| 1 | casehub-qhorus + casehub-ledger | Machine-agent normative infrastructure: speech acts, CommitmentStore, tamper-evident ledger |
| **2** | **+ casehub-work** | **Human-agent normative extension: WorkItems as the human-paced obligation layer — lifecycle, sub-delegation, expiry, escalation, audit** |
| 3 | + claudony | Real LLM instances as agents; coordinator reasons about capability and trust |
| 4 | + casehub | Full case orchestration; WorkItems are CMMN case work units within a case lifecycle |

The key property preserved across all layers: channels, message types, and ledger queries are unchanged. Layer 2 adds WorkItems as the oversight channel's human-layer implementation. It does not modify the machine layer below.

---

## Related Documents

| Document | What it covers |
|---|---|
| [normative-layer.md](normative-layer.md) | The four theoretical layers; speech act taxonomy; insurance claim and database corruption reference scenarios |
| [agent-mesh-framework.md](agent-mesh-framework.md) | Developer reference: channels, message types, CommitmentStore, NormativeChannelLayout, three-channel example |
| [casehub-work](https://github.com/casehubio/work) | WorkItem lifecycle, SPI contracts, expiry, delegation, labels, audit trail |

*Qhorus — the agent communication mesh for the Quarkus Native AI Agent Ecosystem.*
*Platform context: [CaseHub Platform](https://github.com/casehubio/parent/blob/main/docs/PLATFORM.md)*
