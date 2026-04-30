# The Qhorus Normative Layer

## What It Is and Why It Matters for Enterprise Agentic-AI

**Capability without accountability is not progress — it is liability in waiting.**

As AI agents take on consequential roles in the world — approving claims, processing payments,
filing regulatory reports, routing financial decisions — a second question matters as much as
whether they can perform the task: *can we prove what each agent committed to, to whom, under
what authorisation, and what happened when they failed to deliver?* Human institutions spent
centuries developing the infrastructure to answer that question: contracts, audits, professional
accountability frameworks, clearing houses, regulatory reporting. Multi-agent AI has built
remarkable capability while leaving that infrastructure entirely absent.

This is not a logging problem. Logs record events; they do not capture commitments. This is not
a workflow orchestration problem; orchestration tracks procedural steps, not deontic obligations
between autonomous agents. This is not a model quality problem; even perfectly-reasoning agents
operating without formal accountability infrastructure cannot produce the evidence that regulated
environments require.

The normative layer is the accountability infrastructure that multi-agent AI has been missing.
Grounded in thirty years of research at the intersection of speech act theory, deontic logic,
defeasible reasoning, and social commitment semantics — and now implemented as practical,
developer-facing infrastructure — it gives every agent interaction the formal status of an
accountable act: recorded, tracked, causally linked, tamper-evidently proven, and queryable by
the agents themselves at runtime.

**For business owners and compliance officers, the significance is not the technology — it is
the methodology.** The normative layer provides a formally-grounded governance framework for AI
that maps directly to the questions regulators, auditors, and boards already ask: what was
authorised, by whom, what obligations were created, what evidence exists, and who is accountable
when something goes wrong. This is not another layer added to the middleware stack. It is the
foundation that determines whether AI agents can operate legitimately in regulated environments —
and the methodology that gives organisations a defensible, auditable answer when accountability
is demanded. That answer cannot be retrofitted. It has to be built in from the start.

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

The first serious attempt to formalise inter-agent communication came from **FIPA** (Foundation for Intelligent Physical Agents), an IEEE standards body active from the late 1990s. FIPA's Agent Communication Language (FIPA-ACL) drew directly on Searle's speech act theory and defined a taxonomy of communicative acts — `inform`, `request`, `agree`, `refuse`, `failure` — grounded in the same philosophical tradition as Layer 1 above. FIPA understood that messages between agents are not just data transfers; they are *performatives* that create effects in the world by virtue of being uttered. The problem with FIPA was twofold. First, the delivery mechanism: the standards required heavyweight agent platforms and complex negotiation protocols that never made it into production systems. Second, and more fundamentally, the problem FIPA was solving did not yet exist at production scale in 1999. Cross-organisation agent interoperability between enterprise platforms — Siemens talking to British Telecom at the agent level — was not a real production requirement for most teams. The theory was right; the timing and delivery were wrong. Today both have changed. Multi-LLM deployments — Claude handling reasoning, Gemini handling structured extraction, fine-tuned specialists handling domain verification, all in the same production system — are the default assumption for enterprise resilience. The formal semantic grounding that FIPA pioneered is now solving a problem that actually exists at scale.

Meanwhile, **deontic logic** — the formal study of obligation, permission, and prohibition — was developing independently in computer science (Meyer, 1988; Governatori et al.) as a tool for reasoning about normative systems: contracts, regulations, commitments. Defeasible deontic logic, in particular, addressed the reality that obligations in the real world are not absolute: they can be transferred, excused, or overridden. This maps directly to Layers 2 and 3 — the CommitmentStore and the formal treatment of HANDOFF, DECLINE, and FAILURE as first-class exception-handling mechanisms rather than error conditions. Separately, **social commitment semantics** (Singh, 1998; Winikoff, 2007) proposed that the right primitive for multi-agent systems was not the BDI agent's internal mental state, but the observable commitment between agents — what one agent has promised another, whether it has been fulfilled, and what evidence exists. Layer 4, the normative ledger, is a direct implementation of this idea.

On the other side of the tradition, the modern wave of agentic-AI frameworks — **AutoGen**, **LangGraph**, **CrewAI**, **OpenAI Swarm**, **Letta**, and emerging protocol standards like **A2A** — arrived at similar patterns from a completely different direction: empirical engineering. They discovered, through building production systems, that you need handoff mechanisms, that agents need roles, that orchestrators need to track what subagents are doing, and that something has to handle failures gracefully. These frameworks encoded those patterns pragmatically, without reference to the academic tradition. The result is software that works but lacks formal grounding — which means it is hard to reason about correctness, impossible to audit systematically, and difficult to extend without breaking assumptions.

What we realised is that **these two traditions are not competing approaches — they are two perspectives on the same underlying system**, expressed at different levels of abstraction. FIPA and deontic logic describe, formally, the normative structure that modern agentic frameworks are implicitly implementing informally. They are looking at the same thing from opposite ends: one from mathematical precision, one from practical necessity. Qhorus unifies them. The 9-type message taxonomy is grounded in speech act theory, but a developer reads it as an enum. The CommitmentStore implements deontic lifecycle tracking, but a developer sees it as a state machine with a clear API. The normative ledger implements social commitment semantics, but a developer experiences it as an audit table they can query with `list_ledger_entries`. You do not need to know the theory to use it correctly — but the theory is what ensures that using it correctly is possible in the first place. The formal foundations provide three properties: *formal completeness* (the taxonomy covers all communicative act types in the academic literature — no established type is missing from the model), *consistency* (no two types mean the same thing; every type has a distinct formal definition), and *closure* (every obligation has exactly one terminal resolution path). This is completeness relative to the formal taxonomy, not completeness relative to every possible domain requirement. A practitioner whose domain requires PARTIAL_COMPLETION or DISPUTED has a principled extension point — a formally grounded system they can extend consistently — rather than an ad-hoc status field they are adding to an informal one. The foundations do not enumerate your domain for you; they ensure that whatever you build on top is coherent.

A reasonable objection is that formal agent frameworks have a poor track record of practitioner adoption. FIPA, OWL-S, WSMO, formal contract languages for web services — academics built them; practitioners ignored them and built simpler things that shipped. Why should this be different? Because every failed formal framework tried to be the *product*. FIPA-ACL was the interface developers wrote to. OWL-S was the contract language developers authored. The formal theory was exposed directly to practitioners who had no interest in it. Qhorus inverts this. The formal theory is invisible infrastructure. A developer calls `decline_commitment(id, reason)` — they do not need to know that DECLINE is a commissive cancellation, or that it maps to Layer 3 defeasible reasoning. The theory is the guarantee that the API is coherent and complete; it is not the API. Formal grounding as invisible foundation, not formal grounding as developer interface — that is why the adoption trajectory is different.

### Why formal semantics produce convergence — and hand-rolled protocols don't

There is a direct consequence of the academic lineage that matters practically: **without formal semantic grounding, independently implemented agent systems will each invent their own vocabulary for the same coordination concepts**. Ask five teams to build a multi-agent coordination protocol and you will get five different terms for decline, five different interpretations of what a handoff means, and five different answers to whether a silent agent is refusing or failing. This is not hypothetical — it is the current state of the agentic-AI landscape. AutoGen, LangGraph, CrewAI, Gastown, and Swarm each arrived at similar coordination patterns through empirical engineering and gave them incompatible names and semantics. An agent built for one framework cannot reliably compose with an agent built for another.

This is precisely what happened with KQML in the 1990s. KQML specified a set of speech act types — `tell`, `ask-if`, `subscribe` — but left interpretation to each implementor. Different teams read the same specification and built incompatible systems. The speech acts were named but not formally grounded; the same word meant different things to different implementors. FIPA-ACL corrected this by grounding the taxonomy in Searle's speech act theory. The formal grounding removed interpretive ambiguity: `inform` meant the same thing to a Siemens agent platform as to a British Telecom one. For the first time, independently built agent systems could interoperate.

Qhorus inherits this property. The 9-type taxonomy is not a naming convention — it is a provably complete classification of communicative acts derived from formal theory. An LLM trained on speech act theory already understands what COMMAND, DECLINE, and HANDOFF mean — not because it was trained on Qhorus, but because it was trained on the same formal tradition Qhorus is built on. Two independently implemented LLM agents, given the Qhorus normative layer to work within, are more likely to interpret DECLINE consistently than they would without it — because DECLINE maps to a specific, formally defined concept with an extensive training-data footprint in the academic literature. This is a hypothesis, not a proven fact. The formal grounding provides the strongest available basis for convergence; it does not guarantee it. Engine#189 begins testing the weaker claim: that a supervising LLM can consistently reconstruct accountability from a normative record. The stronger claim — that independently trained agents converge on the protocol without explicit guidance — remains to be demonstrated empirically.

**The practical consequence:** a Qhorus deployment can add a new LLM agent — from any provider, trained independently — and that agent will interact with the existing mesh without semantic negotiation. The normative layer is the shared vocabulary. Frameworks without one require semantic alignment to be achieved through convention, documentation, or repeated integration effort. At scale, with many agents from multiple providers, the cost of that alignment compounds rapidly.

The empirical test of this claim is described in the final section of this document and specified in full at [engine#189](https://github.com/casehubio/engine/issues/189).

---

## From Status Fields to Accountability

A reasonable objection to the normative layer is that its distinctions — DECLINED vs FAILED vs EXPIRED — are just status values. Any system can add status fields. What makes formally grounded speech acts different?

The difference is between **tracking** and **accountability**. A status field records what state a piece of work is in. A commitment records who is *accountable* for it — what they promised, to whom, under what authorisation, and whether they followed through. These answer different questions, and the difference becomes consequential when something goes wrong.

**The status field version of a production incident:**
An agent does not complete its assigned work. The system sees a timeout and re-assigns. Whether the agent explicitly refused, started and failed, timed out, or silently handed to someone else — all of these look identical. The re-assignment is the same regardless of cause. The investigation, if it happens, requires reading logs that were never designed to carry evidential weight.

**The accountability version of the same incident:**
The commitment state carries the distinction:

| What happened | Status field sees | Commitment sees | Correct response |
|---------------|------------------|-----------------|-----------------|
| Agent can't do this | Timeout | DECLINED — with stated reason | Re-route immediately; agent is fine |
| Agent tried and failed | Timeout | FAILED — with explanation | Investigate the agent before re-using |
| Agent handed to specialist | Timeout | DELEGATED — new obligor named | Track the chain; do not re-assign |
| Deadline passed, no response | Timeout | EXPIRED — precise timestamp | Escalate; something systemic is wrong |

At five agents this barely matters — you can investigate manually. At fifty agents running overnight, these distinctions determine whether the system recovers automatically or pages someone at 3am.

**The trust scoring argument.** If an agent can mark work complete without formally asserting "I, the named obligor, fulfilled what was asked of me," then counting completion rates for trust scoring is meaningless — you are scoring closure of work records, not quality of fulfilled obligations. With a commitment, DONE is an accountable assertion to a named party. That assertion is attached to the agent identity, timestamped, ledgered, and carries weight in the Bayesian trust model. An agent that marks work done without doing it will eventually be caught — a FLAGGED attestation decreases their trust score. Routing shifts accordingly. No human configures this. It emerges from the accountability record.

**The delegation argument.** When Agent A hands work to Agent B who hands it to Agent C, the delegation chain is a sequence of formally recorded HANDOFF acts, each with a named obligor at each step, each causally linked by `causedByEntryId`. Six months later, when accountability is demanded, the chain is in the ledger. With status fields, Agent A re-assigned a record to Agent B. The accountability question — who was responsible at each point — has no clean answer, because accountability is structural in the normative layer and inferred (imperfectly) from status fields.

**Adding status fields is not the same as adding accountability.** The state values are the surface. The accountability semantics — who promised what to whom, under what authorisation, with what formal consequence on failure — are what the normative layer provides. The values are incidental. The semantics are the point.

---

## Why Not a Workflow Engine?

A workflow engine (Temporal, Camunda, BPMN) handles predetermined paths. You define the process upfront — step A then step B, branch on condition C, retry on failure D. The engine executes that definition.

The normative layer is not a workflow engine. It is coordination infrastructure for agents whose outputs determine what happens next — not a configuration file written before execution.

In the insurance claim scenario, the compliance check DECLINES because Lloyd's syndicate approval is missing. A workflow engine would need a branch defined for this case upfront. The normative layer handles it because DECLINE is a first-class speech act that the coordinator can reason about at runtime. A new agent — one not in the original design — can join the channel, see the DECLINED commitment, and offer its capabilities. The coordination structure emerges from what agents do, not from what an engineer anticipated.

More precisely: workflow engines are the right tool when the process is known. The normative layer is the right infrastructure when autonomous agents are discovering the process. The two are not competing — a CaseHub case can use Quarkus Flow (a workflow worker) for the steps it knows upfront, and Qhorus channels for the coordination that emerges between agents. Workflow for the determined; normative layer for the adaptive.

---

## What This Enables in Practice

The normative layer changes the answers to seven questions that matter in every serious agentic
deployment. Without it, these questions either have no clean answer, or require hours of
archaeology across logs that were never designed to capture what was promised. With it, each is
a query.

### *"Who approved this decision, and what checks ran first?"* — Compliance audit

The FCA asks: "On 14 April, who approved the £180,000 payout and what checks were run before
that decision?" Without the normative layer, a developer manually reconstructs this from
disparate logs across multiple services — a process that takes hours and produces a narrative
that might be wrong. With the normative layer, a single `get_obligation_chain` call on the
payments channel returns the complete obligation history: sanctions screening (DONE), fraud
scoring (DONE, risk 0.12, ENDORSED by compliance officer), compliance check (DECLINED then
re-approved after HANDOFF to senior adjuster), payment attempt (FAILURE), CHAPS retry (DONE) —
with timestamps, agent identities, and causal links. The ledger IS the audit trail. It was built
as the system ran, not assembled afterwards. And the auditor does not need to trust the system's reporting: the SHA-256 hash chain provides integrity detection — any external modification to the record is cryptographically detectable. Note that this is integrity detection, not proof against insider tampering: an adversary with write access to both the application and the ledger store could reconstruct the chain. For environments requiring protection against insider tampering, the optional Ed25519 checkpoint publishing to an external transparency log removes this trust assumption entirely — the checkpoint exists outside the application's control and cannot be retroactively modified.

### *"What goes in the regulatory report, and how do we know it's complete?"* — Regulatory evidence

In regulated industries, filing a Solvency II pre-notification or an FCA disclosure is itself
a normative act — a COMMAND issued, a confirmation DONE, a reference number recorded. Without
the normative layer, regulatory reports are generated after the fact by extracting data from
disparate systems, assembling a narrative, and hoping nothing was missed. With the normative
layer, `list_ledger_entries` with `type_filter=COMMAND,DONE` on the compliance channel *is*
the regulatory evidence. Every pre-notification, every post-settlement report, every compliance
check is already in the ledger — complete, causally linked to the obligation that required it,
and tamper-evidently sealed. There is no separate reporting system. The process and the proof
are the same record.

### *"Which agent made the decision that caused this, and why?"* — Incident investigation

A payment processed incorrectly. A claim was declined that should have been approved. An
escalation never reached the right agent. Without the normative layer, answering these questions
means hours of distributed trace correlation across multiple services — and the answer, when it
arrives, is a reconstruction that cannot be independently verified. With the normative layer,
`get_causal_chain` on the failing ledger entry walks backwards through every HANDOFF and COMMAND
in the obligation's history in seconds. You see exactly where the chain diverged: which agent
held the obligation, what it decided, whether it was attested by a peer, and what happened next.
The answer is in the ledger, causally linked, and it was put there as the system ran.

### *"Which obligations are overdue, and how long have they been waiting?"* — SLA enforcement

Agents issue COMMANDs and do not always receive responses. In a conventional system, timeout
logic is bespoke — written per workflow, hard to standardise, easy to get wrong. With the
normative layer, `list_stalled_obligations` is a single query that surfaces every COMMAND with
no DONE, FAILURE, DECLINE, or HANDOFF, with the exact duration since it was issued. The Watchdog
module acts on this automatically. There is no polling, no timeout counter, no custom monitoring
code per workflow. Stalled obligations surface themselves because every COMMAND that was issued
is in the ledger, and any that was not resolved is trivially queryable.

### *"When a task was delegated and then failed, who is responsible?"* — Accountability under delegation

An orchestrator issues a COMMAND. Agent A says "not my domain" and HANDOFFs to Agent B. Agent B
fails. Without formal delegation tracking, responsibility is ambiguous — and in regulated
environments, ambiguity is not a process problem, it is a compliance failure. GDPR Article 22
and FCA PS20/1 both require clear attribution of automated decisions. With HANDOFF as a
first-class speech act recorded in the ledger, attribution is a structural property: Agent A
transferred the obligation at timestamp T, Agent B accepted it at T+4s, Agent B failed it at
T+47s with a stated reason. The causal chain is in the ledger, linked by `causedByEntryId`,
and it was built by the infrastructure — not by a human writing an incident report after the
fact.

### *"What did that agent actually do, and should we trust its output?"* — Debugging and observability

An agent produced the wrong output. Without the normative layer, reconstructing what it did
means correlating distributed traces across services, reading logs that were written for
operators rather than auditors, and making inferences about what happened from indirect evidence.
With the normative layer, `get_agent_history` returns every obligation the agent was party to,
in sequence — every COMMAND it received, every DONE or FAILURE it issued, every DECLINE or
HANDOFF it made. `summarise_telemetry` shows every tool it invoked, in order, with wall-clock
duration and token consumption. The complete reasoning trace is in the ledger. It required no
extra instrumentation. It was built as the system ran.

### *"Which agent should handle this task, given everything we know about it?"* — Trust derived from behaviour

The hardest operational question in a large agent mesh is not capability — capability is
declared. It is trust: of all capable agents, which has earned the right to handle this
particular task, given its track record and how its peers have rated its decisions?

In most agentic systems, trust is either hardcoded ("this agent is permitted to do X") or
absent ("we assume all agents are trustworthy"). Neither holds in production. The normative
layer, backed by casehub-ledger, provides a third option: trust derived from the immutable
ledger record of what agents have actually done.

**Attestations** are peer review verdicts stamped onto ledger entries. When an agent reviews
another's decision — a fraud score, a compliance ruling, a payment authorisation — it stamps a
`LedgerAttestation`. Attestations can also be written automatically by the framework when commitment outcomes carry unambiguous trust signals: a FULFILLED commitment on a COMMAND writes a SOUND attestation; a FAILURE writes a FLAGGED attestation. This automatic path is implemented — `LedgerWriteService.record()` writes attestations on DONE (SOUND, configurable confidence), FAILURE and DECLINE (FLAGGED, configurable confidence). The feedback loop from obligation outcome to trust score is closed without requiring explicit peer review for every interaction. Explicit attestations carry the values `SOUND`, `ENDORSED`, `FLAGGED`, or `CHALLENGED`, with evidence text and a
confidence score. These are not opinions in application state. They are immutable records in the
same tamper-evident ledger that holds every obligation.

**Bayesian Beta trust scoring** computes a per-actor score from direct attestation history.
Alpha accumulates positive evidence; beta accumulates negative evidence. As peers endorse or
challenge decisions, the distribution narrows and the score stabilises. An agent consistently
endorsed over hundreds of decisions has a fundamentally different score — and a fundamentally
different operational role — from one that has been challenged or has never been attested at all.
The score is a property of the ledger record, not of any configuration file.

**EigenTrust** propagates trust transitively through the mesh via power iteration (Kamvar et
al., 2003). If agent A has attested positively to B's decisions, and B has attested positively
to C's, A has a derived signal about C without ever interacting with it directly. The result is
a global trust share for every actor — a single number in [0.0, 1.0] reflecting standing across
the entire observed network of peer reviews, not just direct relationships.

**Discovery provenance** extends this to how agents came to exist in the mesh. CaseHub applies
the same framework to worker registration (ADR-0006): a worker's registration is a normative
act, recorded in the ledger with the same `causedByEntryId` causal chain as obligation lineage.
A worker introduced by a high-trust provisioner inherits a stronger initial deontic standing via
EigenTrust propagation. Provenance is not a label someone attached — it is a chain in the
ledger, independently verifiable.

The result: who to trust is derived from what agents have done and how their peers have judged
it. Trust accretes from behaviour. It propagates through relationships. It is anchored to
provenance. And it is queryable — the same infrastructure that surfaces obligation health
surfaces trust scores.

### The core shift

In a system without the normative layer, obligations live in workflow state that is hard to
query and impossible to audit across agent boundaries. Regulatory reports are reconstructed
from logs that were never designed to carry evidential weight. Trust is configuration. Delegation
trails go cold. Accountability is ambiguous by design.

With the normative layer, every commitment is infrastructure. Every delegation is traceable.
Every failure has a stated reason, formally attributed. Every agent's standing is derived from
what it has done and how it has been judged. Regulatory evidence is built as the system runs.
Trust is computed from behaviour, not asserted by configuration.

**The LLM reasons. The infrastructure enforces, records, and derives.** That is not a technical
detail. It is the difference between an AI system that is capable and one that is
*accountable* — and in any environment where accountability matters, only the latter is fit for
purpose.

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

---

### Scenario 2 — A Production Database Corruption (Software Engineering)

The insurance scenario demonstrates the normative layer in a regulated financial context. This second scenario demonstrates the same layer in a software engineering context — chosen because every speech act type becomes load-bearing under time pressure, and every distinction in the commitment lifecycle has immediate operational consequence. It is also a scenario every engineer has lived through.

**The incident:** A migration script deployed to production corrupts user records. Five agents must coordinate to diagnose, recover, and notify — under a 30-minute P0 SLA, with the deployment window closing.

| Agent | Role |
|---|---|
| `detection` | Monitors error rates, opens the case |
| `diagnosis` | Investigates root cause — needs DB access |
| `rollback` | Attempts to reverse the deployment |
| `recovery` | Recovers data from backup if rollback fails |
| `communication` | Notifies affected users once scope is known |

**Why every speech act type appears:**

QUERY: Before issuing a COMMAND to diagnose the corruption, the detection agent queries: *"Who has production DB read access?"* — capability negotiation before an obligation is created. Without QUERY as a distinct speech act, this is either a COMMAND issued blind (the agent may not have access) or free text that the system cannot act on.

COMMAND + ACKNOWLEDGED: The diagnosis agent receives a COMMAND and ACKNOWLEDGES it — confirming active acceptance, not just queuing. A 2-minute gap between COMMAND and ACKNOWLEDGED is informative. Silence is not.

STATUS: The rollback agent sends STATUS updates every 30 seconds: *"Schema rollback in progress — 3 of 7 tables complete."* These are assertive speech acts — truthful reports of current state — not state transitions. They extend the obligation window and allow the supervisor to distinguish "slow progress" from "silent failure."

DECLINE: The communication agent receives a COMMAND to notify users but cannot act yet — the scope of affected records is unknown. It sends DECLINE with reason: *"Scope not yet confirmed — will re-engage when diagnosis is complete."* This is a deliberate, reasoned refusal. The obligation is formally closed with explanation. The supervisor knows immediately that communication is waiting on diagnosis, not failed.

FAILURE: The rollback agent attempts schema reversal and discovers the data corruption is not reversible via rollback — the migration modified records, not only schema. It sends FAILURE: *"Schema rollback not sufficient — data changes are irreversible. Escalating to recovery path."* It tried. It could not complete. This is categorically different from DECLINE.

HANDOFF: The rollback agent formally transfers the obligation to the recovery agent: *"Transferring recovery obligation — backup from 02:00 contains clean records."* The recovery agent's OPEN commitment is causally linked to the rollback agent's FAILURE. The full chain: `COMMAND(supervisor→rollback) → FAILURE → HANDOFF(rollback→recovery) → FULFILLED(recovery)`.

DONE: The recovery agent completes the work. The DONE closes the commitment chain. Trust scoring fires: rollback FAILED (review its reliability for irreversible operations), recovery FULFILLED (reinforce for data recovery tasks).

EXPIRED: At the 30-minute mark, the user notification SLA expires. The communication agent's commitment — which was DECLINED pending scope confirmation — transitions to EXPIRED. This is not a failure of the communication agent; it is a systemic SLA breach caused by the extended diagnosis time. `list_stalled_obligations` surfaces it instantly, with the exact timestamp and cause.

**What the supervisor sees in one query:**

```
list_stalled_obligations(channelName: "case-p0-db-corruption/coordination")

→ corr-comms    COMMAND → EXPIRED      communication    Notification SLA breached [30min]
→ corr-rollback COMMAND → FAILURE      rollback         Data irreversible, escalated [22min]
```

Two lines. The supervisor knows: the rollback path was exhausted and correctly escalated; the notification SLA has breached. Neither requires reading a transcript or correlating logs. The obligation state is the operational picture.

**Without the normative layer**, the rollback FAILURE and the communication DECLINE both present as "agent did not complete assigned work." The system cannot distinguish them. The correct response to FAILURE (activate the recovery path immediately) and the correct response to DECLINE (wait for scope, then re-issue COMMAND) are different. During a live P0, treating them identically costs time — measured in affected users and revenue.

This scenario is the basis of a structured empirical test comparing the normative layer against LangChain4j coordination: [engine#189](https://github.com/casehubio/engine/issues/189). The experiment runs both variants five times with identical inputs and measures whether a supervising LLM can consistently distinguish FAILURE from DECLINE, attribute accountability correctly, and identify the precise SLA breach moment — from the record alone.

---

### The layered ecosystem

This same scenario is the reference example across the full Quarkus Native AI Agent Ecosystem:

| Layer | Library added | What changes in the scenario |
|---|---|---|
| 1 | casehub-qhorus + casehub-ledger | Synthetic agents, keyboard-driven, full obligation trail |
| 2 | + casehub-work | Claims become real work items with inboxes; agents pick up tasks from queues |
| 3 | + claudony | Agents are real LLM instances; coordinator reasons about which agent to assign |
| 4 | + casehub | Claims are real cases from the CaseHub engine; full production stack |

Each layer adds genuine capability to the same claim. The dashboard structure, the obligation
board builders, and the ledger query tools carry through unchanged. The scenario driver is the
only thing replaced at each layer — by an `@Alternative` CDI bean that connects to the layer's
own data source. Extension, not duplication.

---

---

## Related Documents

| Document | What it covers |
|----------|---------------|
| [normative-framework.md](normative-framework.md) | Entry point and navigation for this body of works |
| [multi-agent-framework-comparison.md](multi-agent-framework-comparison.md) | Feature-level comparison of Qhorus against AutoGen, LangGraph, CrewAI, Gastown, and others — including normative layer capabilities |
| [agent-protocol-comparison.md](agent-protocol-comparison.md) | How Qhorus, A2A, and ACP are complementary rather than competing |

*Quarkus Qhorus — the agent communication mesh for the Quarkus Native AI Agent Ecosystem.*
*Theoretical foundation documented in ADR-0005. Empirical hypothesis at [engine#189](https://github.com/casehubio/engine/issues/189).*
*Platform context: [CaseHub Platform](https://github.com/casehubio/parent/blob/main/docs/PLATFORM.md).*
