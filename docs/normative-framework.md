# The Qhorus Normative Framework — Body of Works

The normative layer is the accountability infrastructure that multi-agent AI has been missing.
These documents explain what it is, why it matters, how it compares to alternatives, and how
it fits into the broader agent communication landscape.

---

## Reading Order

**Start here if you want to build with Qhorus:**
→ [Agent Mesh Framework — Developer Guide](agent-mesh-framework.md) — message vocabulary, channel topology, agent lifecycle, Layer 1 example

**Start here if you want to understand the theory:**
→ [The Qhorus Normative Layer](normative-layer.md) — the thesis, the theory, the worked examples

**Then read this to see how it compares:**
→ [Multi-Agent Framework Comparison](multi-agent-framework-comparison.md) — feature-level comparison
against AutoGen, LangGraph, CrewAI, Gastown, and ten other frameworks

**Then read this to understand where it fits in the protocol landscape:**
→ [Agent Protocol Comparison](agent-protocol-comparison.md) — how Qhorus, A2A, and ACP are
complementary rather than competing

---

## What Each Document Covers

### [agent-mesh-framework.md](agent-mesh-framework.md)
The developer guide. Covers:
- The 9 message types in three groups — information exchange, obligation lifecycle, telemetry
- Channel semantics and the NormativeChannelLayout (3-channel topology)
- Agent lifecycle — register, announce, work loop, completion
- The CommitmentStore — the 7-state obligation lifecycle
- The normative ledger — 7 query tools and when to use each
- Human-in-the-loop — oversight channel, approval gate, watchdogs
- Layer 1 example: Secure Code Review, fully annotated
- Anti-patterns and quick-start template

### [normative-layer.md](normative-layer.md)
The foundational document. Covers:
- What the normative layer is and why accountability cannot be retrofitted
- The four theoretical layers (speech act theory → deontic logic → defeasible reasoning → social commitment semantics)
- The 30-year academic lineage (FIPA, deontic logic, social commitments) and why it matters that the foundations are proven
- Why formal semantics produce convergence where hand-rolled protocols diverge — the Tower of Babel problem
- Why adding status fields is not the same as adding accountability
- What the normative layer enables in practice (seven questions it answers)
- Two worked examples: an insurance claim (regulated industry) and a production incident (software engineering)
- The empirical hypothesis and the experiment designed to test it

### [multi-agent-framework-comparison.md](multi-agent-framework-comparison.md)
The technical comparison. Covers:
- Normative layer capabilities: which frameworks have commitment lifecycle tracking, accountability attribution, trust models, stalled obligation detection
- Coordination and communication features across all frameworks
- Human interaction and discovery features
- Platform and deployment features
- What Qhorus uniquely provides and where other frameworks genuinely excel

### [agent-protocol-comparison.md](agent-protocol-comparison.md)
The protocol landscape. Covers:
- How A2A (Google), ACP (IBM/BeeAI), and Qhorus answer different questions
- Why they are complementary rather than competing
- How a production system uses all three at different layers

---

## The Central Claim

Every multi-agent framework built without formal semantic grounding will produce a different
vocabulary for the same coordination concepts — a Tower of Babel where independently built
agents cannot be reliably composed. Formal semantics are the only mechanism that has
historically produced interoperability between independently built agent systems: FIPA-ACL
succeeded where KQML failed for exactly this reason.

The normative layer is not a log. It is not a workflow orchestrator. It is the formal
accountability infrastructure — grounded in thirty years of research — that gives every
agent interaction the status of an accountable act: recorded, causally linked, independently
verifiable, and queryable at runtime.

**The empirical test:** [engine#189](https://github.com/casehubio/engine/issues/189) is a
structured experiment comparing LangChain4j (no normative layer) against CaseHub + Qhorus
(normative layer enforced at protocol level) on a production incident response scenario.
The hypothesis: without the normative layer, independently implemented agents cannot
reliably distinguish FAILURE from DECLINE; with it, they always can.

---

*Part of the [CaseHub platform](https://github.com/casehubio/parent/blob/main/docs/PLATFORM.md).
Normative layer implemented in [casehubio/qhorus](https://github.com/casehubio/qhorus).
Theoretical foundation documented in ADR-0005.*
