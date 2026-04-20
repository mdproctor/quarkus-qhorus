# Idea Log

Undecided possibilities — things worth remembering but not yet decided.
Promote to an ADR when ready to decide; discard when no longer relevant.

---

## 2026-04-20 — Channel intent markers linking Claude conversations to tasks

**Priority:** high
**Status:** active

Every Claude-to-Claude channel should carry a structured **task context** as its
opening message — goalId, intent, initiator — forming a boundary marker that
links the entire conversation to a WorkItem or epic. The ledger captures this
at channel open and channel close (DONE message), giving a complete arc: task →
conversation → decision/outcome. Claudony aggregates across channels to produce
human-readable summaries of what Claudes discussed and why, without requiring
explicit WorkItems for every exchange. The implicit record lives in the ledger;
WorkItems are only created when humans need formal accountability.

**Context:** Brainstorm on Qhorus reactive migration, 2026-04-20. Discussion on
when Claudes should use Qhorus direct messaging vs WorkItems surfaced a deeper
gap: even "casual" agent-to-agent coordination produces decisions that humans
should be able to trace. The insight: conversations don't need explicit WorkItems
to be accountable — but they do need a structured "why" marker at the channel
level. This is distributed tracing (OpenTelemetry-style) applied to agent intent.
Qhorus today has correlationId at the message level but no goal context at the
channel level — channels are anonymous. This closes that gap.

**Promoted to:**
