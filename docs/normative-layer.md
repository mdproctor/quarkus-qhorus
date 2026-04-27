# The Qhorus Normative Layer

## What It Is and Why It Matters for Enterprise Agentic-AI

---

## The Four Layers

```
╔══════════════════════════════════════════════════════════════════════════════════╗
║                    THE QHORUS NORMATIVE LAYER STACK                            ║
║          Theoretical foundations of the agent communication mesh               ║
╚══════════════════════════════════════════════════════════════════════════════════╝

 ┌──────────────────────────────────────────────────────────────────────────────┐
 │  LAYER 4 — SOCIAL COMMITMENT SEMANTICS                                       │
 │  "Commitments between agents are observable, verifiable social contracts"    │
 │                                                                              │
 │   NORMATIVE LEDGER  ──  MessageLedgerEntry  ──  SHA-256 hash chain          │
 │                                                                              │
 │   Every speech act permanently recorded. causedByEntryId links each         │
 │   resolution (DONE/FAILURE/DECLINE) back to the COMMAND that created it.    │
 │   Tamper-evident. Queryable. The proof that an obligation existed and        │
 │   how it was resolved — forever.                                             │
 └───────────────────────────────────┬─────────────────────────────────────────┘
                                     │  every resolution proven here
 ┌───────────────────────────────────▼─────────────────────────────────────────┐
 │  LAYER 3 — DEFEASIBLE REASONING                                              │
 │  "Obligations can be overridden, transferred, or excused — with reason"      │
 │                                                                              │
 │   HANDOFF  ─── "I cannot do this — agent-C can, and I am transferring it"  │
 │   DECLINE  ─── "I must refuse — here is my stated reason"                  │
 │   FAILURE  ─── "I tried and could not complete — here is what happened"    │
 │                                                                              │
 │   The obligation does not simply vanish. It is formally resolved or         │
 │   formally transferred. Every exception has a record.                        │
 └───────────────────────────────────┬─────────────────────────────────────────┘
                                     │  unresolved obligations surface as stalled
 ┌───────────────────────────────────▼─────────────────────────────────────────┐
 │  LAYER 2 — DEONTIC LOGIC                                                     │
 │  "What agents are obligated, permitted, and prohibited from doing"           │
 │                                                                              │
 │   COMMITMENT STORE  ──  7 states  ──  full lifecycle tracking               │
 │                                                                              │
 │   OPEN ──► ACKNOWLEDGED ──► FULFILLED                                       │
 │              │               DECLINED                                        │
 │              │               FAILED                                          │
 │              └─────────────► DELEGATED ──► [continues with new agent]       │
 │                              EXPIRED   ──► [deadline passed, never resolved]│
 │                                                                              │
 │   Infrastructure tracks who owes what to whom — not the LLM.               │
 └───────────────────────────────────┬─────────────────────────────────────────┘
                                     │  COMMAND creates; DONE/FAILURE/DECLINE closes
 ┌───────────────────────────────────▼─────────────────────────────────────────┐
 │  LAYER 1 — SPEECH ACT THEORY                                                 │
 │  "Every message is an illocutionary act — doing something by saying it"      │
 │                                                                              │
 │   QUERY    ── ask for information       ── creates weak expectation         │
 │   COMMAND  ── ask for action            ── creates obligation  (→ Layer 2)  │
 │   RESPONSE ── answer a QUERY            ── discharges expectation           │
 │   STATUS   ── report progress           ── extends the obligation window    │
 │   DECLINE  ── refuse a QUERY/COMMAND    ── terminates with stated reason    │
 │   HANDOFF  ── transfer obligation       ── delegates formally  (→ Layer 3)  │
 │   DONE     ── successful completion     ── fulfills obligation              │
 │   FAILURE  ── failed completion         ── terminates with explanation      │
 │   EVENT    ── telemetry/observability   ── no obligation created            │
 └──────────────────────────────────────────────────────────────────────────────┘
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

### The core shift

In a system without the normative layer, obligations exist only in the orchestrator's head — usually encoded as workflow state, hard to query, impossible to audit, and meaningless across agent boundaries. With the normative layer, obligations are first-class infrastructure. They are created, tracked, transferred, and resolved by the mesh — not by the LLM. **The LLM reasons; the infrastructure enforces.** That separation is what makes enterprise-grade agentic systems possible.

---

*Quarkus Qhorus — the agent communication mesh for the Quarkus Native AI Agent Ecosystem.*
*Theoretical foundation documented in ADR-0005. Implementation at [casehubio/quarkus-qhorus](https://github.com/casehubio/quarkus-qhorus).*
