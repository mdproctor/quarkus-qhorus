# Normative Layer — Summary and Critique

*A reading guide for the normative body of work. Summarises how the three
core documents layer and support each other, identifies the gaps, and frames
what needs to be strengthened. This document is a living critique — updated
as the body of work grows.*

---

## The Three Documents

| Document | Role |
|---|---|
| [normative-layer.md](normative-layer.md) | Theory and business case — the *why* |
| [agent-mesh-framework.md](agent-mesh-framework.md) | Machine-agent developer guide — the *how for code* |
| [work-and-workitems.md](work-and-workitems.md) | Human-agent normative extension — the *how for people* |

Each addresses a distinct layer without overlapping the others. They reference
each other precisely and build without duplication.

---

## How They Layer

### normative-layer.md — The Foundation

Establishes the theoretical and business case. The four-layer stack — speech
act theory (Layer 1), deontic logic (Layer 2), defeasible reasoning (Layer 3),
social commitment semantics (Layer 4) — is not invented here; it has a 30-year
academic lineage (FIPA, Meyer, Singh, Winikoff). The document makes the argument
that this lineage is precisely why it is the right foundation: formal completeness,
consistency, and closure are properties of the taxonomy, not aspirations for it.

The business framing is direct: the seven questions regulators, auditors, and
boards actually ask — who approved this, what checks ran, which agent is
accountable, where is the delegation chain — are each answered by a single ledger
query. Not by log archaeology. By infrastructure that was built as the system ran.

### agent-mesh-framework.md — The Machine Layer

Translates the theory into developer-facing infrastructure. The 9-type message
taxonomy becomes an enum. The CommitmentStore lifecycle becomes a state machine
with a clear API. The ledger becomes a query surface with eight tools.

The NormativeChannelLayout — three channels per case: `work` (all types),
`observe` (EVENT only), `oversight` (QUERY+COMMAND only) — is the principal
structural contribution. Separating obligations from telemetry from human
governance is not just organisation; it makes each concern independently
queryable with no signal pollution.

The anti-patterns section is as important as the API: EVENT on the work channel,
silent failure, obligation flooding, large payloads in message bodies — each one
describes what the CommitmentStore looks like when the pattern is violated.

### work-and-workitems.md — The Human Layer

The key argument: machine-agent and human-agent obligations differ in one
fundamental dimension — **obligation continuity**. A machine agent can wait
hours for a long-running process, block on a signal, or hold an obligation open
across many STATUS updates — duration is not the distinction. What a machine
agent does not do is hold an obligation *discontinuously*: set it aside, attend
to other things, and return. It is either present and working, or it has formally
released via DONE, FAILURE, DECLINE, or HANDOFF.

A human agent holds obligations discontinuously by nature — stepping away,
switching context, partially delegating while retaining accountability, returning
after interruption. Every state WorkItem introduces beyond the machine-layer
minimum — ACKNOWLEDGED, IN_PROGRESS, SUSPENDED, DELEGATED — is a normative
artifact of that discontinuous holding, not of duration.

The document establishes this cleanly: casehub-work is not filling gaps in the
machine-layer taxonomy. It is the human-agent layer — a principled extension of
the Qhorus normative model for obligations held discontinuously.

The seam between layers is the oversight channel. Layer 1 has a synthetic human
posting COMMAND directly. Layer 2 replaces that synthetic post with a real
WorkItem lifecycle — claim, review, possibly delegate, resolve — and the resulting
COMMAND feeds back into the mesh. The channel is unchanged. The message types are
unchanged. The seam is invisible to the machine layer.

---

## What the Three Documents Establish Together

**The vertical coherence is the primary achievement.** Theory → machine
implementation → human extension, each layer adding genuine capability without
modifying the layer below. The layered ecosystem table — Qhorus alone, plus
casehub-ledger, plus casehub-work, plus Claudony, plus CaseHub — can be read
as a single coherent specification where each addition is principled rather than
incremental.

**Formal completeness is a real property, not a claim.** The 9-type taxonomy is
derived from the formal literature. FAILURE and DECLINE are not interchangeable
status values; they are categorically distinct speech acts with different
CommitmentStore transitions and different operational implications. The anti-pattern
"silent failure" exists precisely because a machine agent that posts nothing leaves
OPEN in the CommitmentStore forever — the type system is what makes silence
detectably wrong.

**The extension model is clean.** SUSPENDED and sub-delegation (DELEGATED with
retained owner) are correctly identified as human-layer concepts with no
machine-layer equivalent and no semantic overlap with any existing type. SUSPENDED
exists because humans can pause an obligation without failing or transferring it —
a machine agent in the same situation either continues or formally releases. The
formal extension contract — prove non-overlap before use, namespace new types,
define a promotion path to core — is the right governance mechanism.

---

## Gaps and Critique

### 1. Cross-Channel Causal Correlation *(implemented)*

`causedByEntryId` is a UUID reference that resolves across all channels. An
oversight COMMAND can causally link to a work DONE; `get_causal_chain` now
traverses channel boundaries. `get_obligation_activity` walks the full causal
DAG — not just correlationId — so oversight escalations with a different
correlationId appear in the narrative alongside the work obligation that
triggered them. See Part 6 of `agent-mesh-framework.md`.

The remaining open question is ProvenanceLink (PROV-O graph across agents,
cases, and WorkItems) — the formal specification of the broader causal graph
once WorkItem audit entries participate in the same DAG as Qhorus ledger
entries. The cross-channel foundation is in place; WorkItem integration is the
next step.

### 2. WorkItem Integration into Trust Scoring

The machine layer has a closed feedback loop: `LedgerWriteService` writes
`LedgerAttestation` on DONE (SOUND), FAILURE (FLAGGED), and DECLINE (FLAGGED).
Those attestations feed the Bayesian Beta trust score. The loop is implemented.

The human layer does not yet close this loop. When a WorkItem reaches COMPLETED,
does that produce a LedgerAttestation on the originating QUERY entry? Who is the
attestor — the human who resolved it, or the system? And for sub-delegation
chains: if Alice delegates to Bob and Bob completes, does Alice's trust signal
propagate to Bob's track record via EigenTrust? The deontic logic of retained
ownership (DELEGATED) implies it should. The implementation does not yet specify
this.

### 3. The Extension Contract Is Underspecified

Type namespacing, extension registry, and promotion path are described as needed
in work-and-workitems.md. They are not yet implemented or formally specified.
Without them, community or domain-specific extensions risk the same fragmentation
problem KQML had: same vocabulary, incompatible semantics across implementors.
This needs to be designed before external extensions exist, not retrofitted after.

Specifically needed:
- A namespacing convention for extension types (`casehub-work.SUSPEND` vs a
  future Qhorus `SUSPEND`)
- A declaration mechanism so the CommitmentStore and ledger handle extension
  types without hardcoding
- A promotion criteria definition — what evidence justifies moving an extension
  type into core?

### 4. The Empirical Hypothesis Remains Unverified

normative-layer.md makes a specific claim: that formal semantic grounding
produces convergence across independently trained LLM agents. Two agents trained
independently on the same formal tradition will interpret DECLINE consistently —
because DECLINE maps to a specific, formally defined concept with an extensive
training-data footprint.

This is framed honestly as a hypothesis (engine#189). But it is load-bearing.
If independently trained agents do not converge on the protocol without explicit
guidance, the interoperability argument weakens significantly. The empirical test
needs to run.

### 5. The Oversight Channel Pattern Needs Load-Bearing Examples

The oversight channel is where the two layers meet — machine agents posting
QUERY, humans resolving via WorkItem, COMMAND feeding back into the mesh. This
is architecturally elegant. It is also the least-tested path in the current
normative test suite.

The Layer 1 example ends with a human posting COMMAND directly (synthetic). The
Layer 2 description in work-and-workitems.md shows the WorkItem lifecycle in
prose. There is no canonical executable test that demonstrates the full path:
QUERY → WorkItem creation → WorkItem lifecycle → COMMAND → downstream DONE — with
the cross-channel ledger capturing the causal chain.

That test is the Layer 2 equivalent of `SecureCodeReviewScenario.java` and needs
to be written.

---

## What Needs Strengthening

In rough priority order:

1. **ProvenanceLink specification** — Cross-channel causal links are implemented.
   The next step is specifying how WorkItem audit entries participate in the same
   causal DAG as Qhorus ledger entries, enabling a full PROV-O graph across
   agents, cases, and human obligations.

2. **WorkItem → LedgerAttestation path** — Specify the attestation model for
   human obligations. Define how COMPLETED, REJECTED, and EXPIRED WorkItems
   produce trust signals, and how sub-delegation chains affect EigenTrust
   propagation.

3. **Extension contract specification** — Namespacing, registry, promotion
   criteria. Design this before external extensions exist.

4. **Layer 2 canonical test** — An executable end-to-end test for the oversight
   channel + WorkItem lifecycle path. Make the human-layer seam as well-tested
   as the machine layer.

5. **engine#189 empirical test** — Run the LLM convergence experiment. The
   interoperability argument needs evidence, not just theory.

---

## Related Documents

| Document | What it covers |
|---|---|
| [normative-framework.md](normative-framework.md) | Navigation entry point for the full body of works |
| [normative-channel-layout.md](normative-channel-layout.md) | Three-channel pattern and `MessageTypePolicy` SPI reference |
| [normative-objections.md](normative-objections.md) | Counter-arguments to the normative layer approach |
| [multi-agent-framework-comparison.md](multi-agent-framework-comparison.md) | Feature comparison against AutoGen, LangGraph, CrewAI, and others |
| [agent-protocol-comparison.md](agent-protocol-comparison.md) | Positioning against A2A and ACP |

*Qhorus — the agent communication mesh for the Quarkus Native AI Agent Ecosystem.*
*Platform context: [CaseHub Platform](https://github.com/casehubio/parent/blob/main/docs/PLATFORM.md)*
