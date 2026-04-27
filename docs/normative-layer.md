# The Qhorus Normative Layer

## What It Is and Why It Matters for Enterprise Agentic-AI

---

## The Four Layers

```
+================================================================================+
|  THE QHORUS NORMATIVE LAYER STACK                                              |
|  Theoretical foundations of the agent communication mesh                       |
+================================================================================+

+--------------------------------------------------------------------------------+
|  LAYER 4 -- SOCIAL COMMITMENT SEMANTICS                                        |
|  "Commitments between agents are observable, verifiable social contracts"      |
|                                                                                |
|   NORMATIVE LEDGER  --  MessageLedgerEntry  --  SHA-256 hash chain             |
|                                                                                |
|   Every speech act permanently recorded. causedByEntryId links each            |
|   resolution (DONE/FAILURE/DECLINE) back to the COMMAND that created it.       |
|   Tamper-evident. Queryable. The proof that an obligation existed and          |
|   how it was resolved -- forever.                                              |
+----------------------------------+---------------------------------------------+
                                   |  every resolution proven here
+-----------------------------------+--------------------------------------------+
|  LAYER 3 -- DEFEASIBLE REASONING                                               |
|  "Obligations can be overridden, transferred, or excused -- with reason"       |
|                                                                                |
|   HANDOFF  --- "I cannot do this -- agent-C can, transferring it"              |
|   DECLINE  --- "I must refuse -- here is my stated reason"                     |
|   FAILURE  --- "I tried and could not complete -- here is what happened"       |
|                                                                                |
|   The obligation does not simply vanish. It is formally resolved or            |
|   formally transferred. Every exception has a record.                          |
+----------------------------------+---------------------------------------------+
                                   |  unresolved obligations surface as stalled
+-----------------------------------+--------------------------------------------+
|  LAYER 2 -- DEONTIC LOGIC                                                      |
|  "What agents are obligated, permitted, and prohibited from doing"             |
|                                                                                |
|   COMMITMENT STORE  --  7 states  --  full lifecycle tracking                  |
|                                                                                |
|   OPEN --> ACKNOWLEDGED --> FULFILLED                                          |
|              |               DECLINED                                          |
|              |               FAILED                                            |
|              +-----------> DELEGATED --> [continues with new agent]            |
|                            EXPIRED   --> [deadline passed, never resolved]     |
|                                                                                |
|   Infrastructure tracks who owes what to whom -- not the LLM.                  |
+----------------------------------+---------------------------------------------+
                                   |  COMMAND creates; DONE/FAILURE/DECLINE closes
+-----------------------------------+--------------------------------------------+
|  LAYER 1 -- SPEECH ACT THEORY                                                  |
|  "Every message is an illocutionary act -- doing something by saying it"       |
|                                                                                |
|   QUERY    -- ask for information       -- creates weak expectation            |
|   COMMAND  -- ask for action            -- creates obligation (-> Layer 2)     |
|   RESPONSE -- answer a QUERY            -- discharges expectation              |
|   STATUS   -- report progress           -- extends the obligation window       |
|   DECLINE  -- refuse a QUERY/COMMAND    -- terminates with stated reason       |
|   HANDOFF  -- transfer obligation       -- delegates formally (-> Layer 3)     |
|   DONE     -- successful completion     -- fulfills obligation                 |
|   FAILURE  -- failed completion         -- terminates with explanation         |
|   EVENT    -- telemetry/observability   -- no obligation created               |
+--------------------------------------------------------------------------------+
```

---

## Theoretical Foundations — From Academic Tradition to Practical Implementation

The ideas behind these four layers did not originate with us. They have a 30-year academic lineage — and that lineage is precisely why they are the right foundations for enterprise agentic-AI.

The first serious attempt to formalise inter-agent communication came from **FIPA** (Foundation for Intelligent Physical Agents), an IEEE standards body active from the late 1990s. FIPA's Agent Communication Language (FIPA-ACL) drew directly on Searle's speech act theory and defined a taxonomy of communicative acts — `inform`, `request`, `agree`, `refuse`, `failure` — grounded in the same philosophical tradition as Layer 1 above. FIPA understood that messages between agents are not just data transfers; they are *performatives* that create effects in the world by virtue of being uttered. The problem with FIPA was not the theory — it was the abstraction level. The standards required heavyweight agent platforms, complex negotiation protocols, and infrastructure that never made it into production systems. The theory was right; the delivery mechanism was too academic to be adopted.

Meanwhile, **deontic logic** — the formal study of obligation, permission, and prohibition — was developing independently in computer science (Meyer, 1988; Governatori et al.) as a tool for reasoning about normative systems: contracts, regulations, commitments. Defeasible deontic logic, in particular, addressed the reality that obligations in the real world are not absolute: they can be transferred, excused, or overridden. This maps directly to Layers 2 and 3 — the CommitmentStore and the formal treatment of HANDOFF, DECLINE, and FAILURE as first-class exception-handling mechanisms rather than error conditions. Separately, **social commitment semantics** (Singh, 1998; Winikoff, 2007) proposed that the right primitive for multi-agent systems was not the BDI agent's internal mental state, but the observable commitment between agents — what one agent has promised another, whether it has been fulfilled, and what evidence exists. Layer 4, the normative ledger, is a direct implementation of this idea.

On the other side of the tradition, the modern wave of agentic-AI frameworks — **AutoGen**, **LangGraph**, **CrewAI**, **OpenAI Swarm**, **Letta**, and emerging protocol standards like **A2A** — arrived at similar patterns from a completely different direction: empirical engineering. They discovered, through building production systems, that you need handoff mechanisms, that agents need roles, that orchestrators need to track what subagents are doing, and that something has to handle failures gracefully. These frameworks encoded those patterns pragmatically, without reference to the academic tradition. The result is software that works but lacks formal grounding — which means it is hard to reason about correctness, impossible to audit systematically, and difficult to extend without breaking assumptions.

What we realised is that **these two traditions are not competing approaches — they are two perspectives on the same underlying system**, expressed at different levels of abstraction. FIPA and deontic logic describe, formally, the normative structure that modern agentic frameworks are implicitly implementing informally. They are looking at the same thing from opposite ends: one from mathematical precision, one from practical necessity. Qhorus unifies them. The 9-type message taxonomy is grounded in speech act theory, but a developer reads it as an enum. The CommitmentStore implements deontic lifecycle tracking, but a developer sees it as a state machine with a clear API. The normative ledger implements social commitment semantics, but a developer experiences it as an audit table they can query with `list_ledger_entries`. You do not need to know the theory to use it correctly — but the theory is what ensures that using it correctly is possible in the first place. The formal foundations are the guarantee that the system is complete (no obligation type is missing), consistent (no two types mean the same thing), and closed (every obligation has exactly one terminal resolution path).

---

## What This Enables in Practice

In most multi-agent systems today, agents communicate by passing messages. An orchestrator tells a subagent to do something. The subagent either responds or it doesn't. If it doesn't, you grep the logs. If it delegated to a third agent, you might never find out. If it declined with a reason, that reason lived in a string field somewhere. There is no system-level concept of *obligation* — no record that a commitment existed, was accepted, and was discharged or refused.

This works in demos. It breaks in production. Here is what changes:

### Compliance audit in regulated industries

The FCA asks: "On 14 April, who approved the £180,000 payout and what checks were run before that decision?" Without the normative layer, a developer manually reconstructs this from disparate logs across multiple services. With the normative layer, a single `get_obligation_chain` call on the payments channel returns the complete obligation history — sanctions screening (DONE), fraud scoring (DONE, risk 0.12), compliance check (DECLINED then re-approved after HANDOFF), payment attempt (FAILURE), retry (DONE) — with timestamps, agent identities, and the causal links between them. The ledger IS the audit trail. It was built as the system ran.

### Incident investigation

An order processed incorrectly. Which agent decided what, in what order, and why? With raw message passing you hunt through distributed traces. With the normative layer, `get_causal_chain` on the failing ledger entry walks backwards through every HANDOFF and COMMAND in the obligation's history. You see exactly where the chain diverged from expected behaviour.

### SLA monitoring at runtime

Agents issue COMMANDs and don't always get responses. In a conventional system you build bespoke timeout logic per workflow. With the normative layer, `list_stalled_obligations` is a single query — it finds every COMMAND that has no DONE, FAILURE, DECLINE, or HANDOFF, and tells you how long it has been waiting. The Watchdog module can act on this automatically. Stalled obligations surface themselves.

### Accountability under delegation

An orchestrator issues a COMMAND. Agent A says "not my domain" and HANDOFFs to Agent B. Agent B fails. Who is responsible? Without formal delegation tracking, this is ambiguous — and in a regulated system, ambiguity is liability. With HANDOFF as a first-class speech act recorded in the ledger, the delegation chain is explicit: Agent A transferred the obligation at timestamp T, Agent B accepted it, Agent B failed it. The causal chain is in the ledger, linked by `causedByEntryId`.

### Debugging at scale

An agent produced the wrong output. What did it do internally? `summarise_telemetry` shows every tool it invoked, how long each took, how many tokens it consumed. `get_agent_history` shows every obligation it was party to, in sequence. You can reconstruct the agent's reasoning trace from the immutable record without instrumenting anything after the fact.

### Participant trust and discovery provenance

In most agentic systems, trust is either hardcoded ("this agent is allowed to do X") or absent
("we assume all agents are trustworthy"). Neither holds in production. The normative layer, backed
by quarkus-ledger, provides a third option: trust derived from observable behaviour, propagated
transitively through the mesh, anchored to an immutable record.

**Attestations** are peer review verdicts stamped onto ledger entries. When an agent reviews
another agent's decision — a sanctions check, a fraud score, a compliance ruling — it stamps a
`LedgerAttestation` with a verdict: `SOUND`, `ENDORSED`, `FLAGGED`, or `CHALLENGED`, together
with evidence text and a confidence score. These are not opinions stored in application state;
they are immutable records in the same tamper-evident ledger that holds every obligation.

**Bayesian Beta trust scoring** (`ActorTrustScore`) computes a per-actor trust score from
direct attestation history. Each agent has an alpha value (accumulated positive evidence) and a
beta value (accumulated negative evidence). As peers endorse or challenge decisions, the
distribution narrows and the trust score stabilises. An agent that has been reviewed hundreds of
times and consistently endorsed has a fundamentally different score from one that has never been
attested or has been challenged. The score is a property of the ledger record, not of any
individual system's configuration.

**EigenTrust** (`EigenTrustComputer`) propagates trust transitively through the mesh via power
iteration — the same algorithm used in peer-to-peer reputation systems (Kamvar et al., 2003).
If agent A has attested positively to B's decisions, and B has attested positively to C's, then
A has a derived signal about C even without direct interaction. The result is a global trust share
for every actor in the mesh: a single number in [0.0, 1.0] that reflects the actor's standing
across the entire observed network of peer reviews, not just its direct relationships. New agents
entering the mesh with no attestation history receive a uniform prior; their score shifts as
peers observe and attest to their decisions.

**Discovery provenance** ties this to participant registration. CaseHub has adopted the same
four-layer framework for worker registration (ADR-0006): a worker's registration is a normative
act — a speech act that constitutively creates a participant with deontic consequences. The
engine incurs an obligation to consider the worker for capable work; the worker incurs an
obligation to accept work within its declared capabilities or decline with reason. The discovery
lineage of how a worker came to be known — statically declared, provisioned, self-announced, or
introduced by another participant — is recorded using the same `causedByEntryId` causal chain as
obligation lineage in Qhorus. Trust derives from this chain: a worker introduced by a
provisioner whose EigenTrust score is high inherits a correspondingly stronger initial deontic
standing than one that self-announced without a voucher.

The result: in a system built on the normative layer, **who to trust is derived from the
immutable record of what agents have done**, not from configuration that someone set up once and
forgot. Trust accretes from behaviour. It propagates through peer relationships. It is anchored
to the causal chain of how agents came to exist. And it is queryable — the same ledger tools
that surface obligation health surface trust scores.

### The core shift

In a system without the normative layer, obligations exist only in the orchestrator's head —
usually encoded as workflow state, hard to query, impossible to audit, and meaningless across
agent boundaries. With the normative layer, obligations are first-class infrastructure. They are
created, tracked, transferred, and resolved by the mesh — not by the LLM. Trust is derived from
observable behaviour, propagated through peer attestation, and anchored to tamper-evident
records. Participant provenance is a causal chain, not a configuration file. **The LLM reasons;
the infrastructure enforces, records, and derives.** That separation is what makes
enterprise-grade agentic systems possible.

---

## Grounded in a Real Scenario — Insurance Claim Processing

The best way to understand the normative layer is to watch it work. The following scenario — a
£180,000 commercial fire damage claim processed by a multi-agent AI system — uses every
capability the normative layer provides. It is also the reference example for the Quarkus Native
AI Agent Ecosystem: the same claim scenario is demonstrated at each layer of the stack, with each
library adding genuine capability to the same story.

### The agents and what they do

Nine agents collaborate to process the claim:

| Agent | Role |
|---|---|
| `claims-coordinator` | Orchestrates the full flow |
| `policy-validator` | Checks coverage terms and exclusions |
| `sanctions-screener` | Screens claimant against OFAC/HMT lists |
| `fraud-detection` | Runs ML-based fraud scoring |
| `damage-assessor` | Evaluates damage and estimates value |
| `compliance-officer` | Verifies FCA regulatory requirements |
| `senior-adjuster` | Approves payouts above £100,000 (Lloyd's threshold) |
| `payment-processor` | Handles BACS and CHAPS disbursements |
| `regulatory-reporter` | Files Solvency II pre- and post-settlement reports |

Four channels carry the communication: `claim-456` (main flow), `high-value-review`
(escalation), `compliance-checks` (regulatory), `payments`.

### The claim, step by step, and which layer handles it

**Step 1 — Is the policy valid?**
The coordinator sends a QUERY: *"Is policy UK-2024-789 active and does it cover commercial fire
damage?"* The policy-validator responds: *"Active, fire covered, maximum payout £500,000."*

*Layer 1 in action.* A QUERY is a speech act that creates an expectation. The RESPONSE
discharges it. No obligation was created — this is an information exchange, not a commitment.
The 9-type taxonomy distinguishes QUERY from COMMAND precisely because asking a question and
demanding an action are categorically different acts with different consequences.

---

**Step 2 — Sanctions screening**
COMMAND: *"Screen Acme Corp against OFAC and HMT sanctions lists."* The sanctions-screener
sends STATUS: *"Screening in progress."* Then DONE: *"No matches found."*

*Layer 2 in action.* COMMAND creates an obligation in the CommitmentStore: the
sanctions-screener now owes a result. STATUS extends the deadline window — the obligation is
live. DONE fulfills it and closes the commitment. If the sanctions-screener had gone silent, the
CommitmentStore would show OPEN past the deadline, and `list_stalled_obligations` would surface
it automatically.

---

**Step 3 — Fraud detection with telemetry**
COMMAND: *"Score this claim for fraud risk."* The fraud-detection agent invokes its ML model,
emits an EVENT — tool `ml-fraud-score`, duration 2,341ms, 1,200 tokens — then DONE: *"Risk
score: LOW (0.12)."*

*Layers 1 and 4 in action.* The COMMAND created an obligation (Layer 2). The EVENT is a
telemetry speech act — it carries observability data without creating a new obligation.
`summarise_telemetry` can later aggregate all EVENT entries to show model performance,
token consumption, and latency across the full claim. The normative ledger records both the DONE
(the fulfillment) and the EVENT (the evidence) as immutable entries, causally linked.

---

**Step 4 — The external surveyor who never responds**
COMMAND: *"Assess fire damage at 42 Industrial Way — dispatch a surveyor."* The damage-assessor
sends STATUS: *"Surveyor dispatched."* Then silence.

*Layer 2 and Layer 4 in action.* The obligation remains OPEN past its deadline — state
EXPIRED in the CommitmentStore. `list_stalled_obligations` returns this COMMAND immediately,
flagging it as stalled for 45+ seconds. The normative ledger holds the permanent record: a
COMMAND was issued, a STATUS was reported, and no terminal resolution ever arrived. No custom
timeout code. No polling loop. The infrastructure surfaces the failure.

---

**Step 5 — A QUERY bridges the gap**
Since the surveyor is unresponsive, the coordinator asks: *"Can you estimate the damage without a
site visit?"* QUERY → RESPONSE: *"Based on photos provided: estimated £180,000."*

Two information exchanges (Steps 1 and 5) and two obligations (Steps 2 and 4) have already
shown the difference between asking and committing. The taxonomy enforces that distinction at
the infrastructure level, not at the LLM reasoning level.

---

**Step 6 — The compliance check that fails**
COMMAND: *"Verify this claim meets FCA disclosure requirements for commercial fire payouts."*
DECLINE: *"Non-compliant. This claim exceeds £100,000 — Lloyd's syndicate approval is required
before settlement. Approval not on file."*

*Layer 3 in action.* A DECLINE is not an error. It is a first-class speech act — a formal
refusal with a stated reason. The CommitmentStore moves to DECLINED. The normative ledger records
the reason verbatim. The coordinator can now act on a specific, structured response rather than
parsing free text or catching an exception. Without Layer 3, "compliance failed" is a string.
With it, it is a typed speech act with a causal record.

---

**Step 7 — Escalation via HANDOFF**
COMMAND to compliance-officer: *"Request Lloyd's syndicate approval for a £180,000 commercial
payout."* The compliance-officer sends HANDOFF to senior-adjuster: *"Exceeds my authority — I
am transferring this obligation to the senior adjuster."* The senior-adjuster sends STATUS:
*"Consulting syndicate."* Then DONE: *"Syndicate approval granted. Reference: LLY-2026-04-789."*

*Layer 3 in full.* The HANDOFF is defeasible reasoning in action: the original obligation is not
abandoned, it is formally transferred. The CommitmentStore moves to DELEGATED, then — when the
senior-adjuster resolves it — to FULFILLED. The normative ledger records three entries, each
linked by `causedByEntryId`:
```
seq=1  COMMAND → causedByEntryId: null           (coordinator issued it)
seq=2  HANDOFF → causedByEntryId: <seq=1 id>     (compliance-officer transferred it)
seq=3  DONE    → causedByEntryId: <seq=2 id>     (senior-adjuster resolved it)
```
`get_causal_chain` on the DONE entry returns this complete ancestry. `get_obligation_chain`
returns the full history plus the live CommitmentStore state. This is the proof chain a regulator
would ask for: who was responsible, when the obligation transferred, and who discharged it.

---

**Steps 8–9 — Re-verification and regulatory filing**
With syndicate approval in hand, compliance re-verifies (DONE). The regulatory-reporter files
a Solvency II pre-notification — COMMAND, then an EVENT (external API call, 450ms), then DONE.
Reference `FCA-2026-04-001` recorded in the DONE entry's content field.

The normative ledger now contains a Solvency II audit trail that was built as the system ran.
No separate regulatory reporting system. No log scraping. The EVENT captures the API call
latency for performance monitoring; the DONE captures the regulatory reference for compliance
evidence.

---

**Step 10 — Payment failure and recovery**
COMMAND: *"Process BACS payout of £180,000 to Acme Corp."* FAILURE: *"BACS validation failed —
sort code 20-14-09 is not registered."*

Then: COMMAND: *"Retry via CHAPS."* STATUS: *"CHAPS processing."* DONE: *"Payment confirmed.
Reference: CHAPS-2026-04-001."*

Two obligations: one FAILED, one FULFILLED. The normative ledger captures both, with full
causal linkage. `get_obligation_stats` on the payments channel shows: total=2, fulfilled=1,
failed=1, fulfillment_rate=0.5. `get_agent_history` for `payment-processor` shows both entries
in sequence — the failure and the recovery — without any custom query.

---

**Steps 12–13 — Regulatory close-out and audit seal**
Post-settlement Solvency II report filed (DONE). Audit record sealed (DONE).

The final state: the normative ledger holds 18 entries across four channels, causally linked,
SHA-256 hash-chained, tamper-evident. Every COMMAND has a terminal resolution. The one stalled
obligation (the surveyor) is permanently recorded as unresolved — not deleted, not hidden,
permanently visible as a gap in the audit trail.

---

### What the dashboard shows

The Agent Mesh Dashboard — built with Tamboui TUI, running inside Quarkus — renders this state
in real time as the scenario advances step by step:

```
+==============================================================================+
|  Acme Corp -- Fire Damage Claim #456  [s: next  r: reset  q: quit]           |
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
|  corr-comp-1  COMMAND -> DECLINE  compliance-officer  Missing syndicate [o]  |
|  corr-surv    COMMAND -> STALLED  damage-assessor     Awaiting surveyor[~~]  |
+==============================================================================+
|  CONSOLE                                                                     |
|  [10:04:23] DONE -- CHAPS payment confirmed: CHAPS-2026-04-001               |
|  [10:03:47] FAILURE -- BACS rejected: invalid sort code 20-14-09             |
+==============================================================================+
```

The colour of each row is determined by the message type of the terminal entry — green for DONE,
red for FAILURE, orange for DECLINE, yellow for STALLED, magenta for HANDOFF in progress. The
channel health column turns yellow when stalled obligations exist, red when failures are
unrecovered. The entire display is driven by the seven ledger query tools — no custom dashboard
queries, no bespoke reporting logic.

### The layered ecosystem

This same scenario is the reference example across the full Quarkus Native AI Agent Ecosystem:

| Layer | Library added | What changes in the scenario |
|---|---|---|
| 1 | quarkus-qhorus + quarkus-ledger | Synthetic agents, keyboard-driven, full obligation trail |
| 2 | + quarkus-work | Claims become real work items with inboxes; agents pick up tasks from queues |
| 3 | + claudony | Agents are real LLM instances; coordinator reasons about which agent to assign |
| 4 | + casehub | Claims are real cases from the CaseHub engine; full production stack |

Each layer adds genuine capability to the same claim. The dashboard structure, the obligation
board builders, and the ledger query tools carry through unchanged. The scenario driver is the
only thing replaced at each layer — by an `@Alternative` CDI bean that connects to the layer's
own data source. Extension, not duplication.

---

*Quarkus Qhorus — the agent communication mesh for the Quarkus Native AI Agent Ecosystem.*
*Theoretical foundation documented in ADR-0005. Implementation at [casehubio/quarkus-qhorus](https://github.com/casehubio/quarkus-qhorus).*
