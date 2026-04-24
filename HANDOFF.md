# Quarkus Qhorus — Session Handover
**Date:** 2026-04-24 (sixteenth session — CommitmentStore ships; PendingReply deleted; 871 tests)

## What Was Done This Session

- **CommitmentStore complete** — full obligation lifecycle tracker (OPEN→ACKNOWLEDGED→FULFILLED/DECLINED/FAILED/DELEGATED/EXPIRED) replacing PendingReply. 13 tasks, all issues #89–#97 closed and merged to main.
- **PendingReply deleted** — entity, cleanup job, SPI, JPA impls, InMemory stores, contract tests all gone. No orphan references.
- **wait_for_reply migrated** — polls Commitment state; handles DECLINED, FAILED, DELEGATED, EXPIRED paths. cancel_wait and list_pending_waits also migrated.
- **2 new MCP tools** — `list_my_commitments` (obligor/requester/both filter) and `get_commitment` (full lifecycle detail by correlationId).
- **871 tests, 0 failures** — 725 runtime (44 @Disabled), 146 testing. Up 139 tests from before the session.
- **Key design fix** — unique constraint on correlationId removed. Delegation chains (HANDOFF) require multiple Commitment records sharing a correlationId; `findByCorrelationId` now prefers the non-terminal one.

## Current State

- **Branch:** `main` — everything merged and pushed to `casehubio/quarkus-qhorus`
- **All issues closed:** #89–#97
- **Uncommitted:** `.claude/settings.local.json`, some pom.xml files, a few test files — all pre-existing, not from this session

## Immediate Next Steps

1. **Run LLM examples** — `mvn test -pl examples/agent-communication -Pwith-llm-examples -Dno-format` — model is cached in `~/.jlama/` (downloaded end of previous session, exit 0). Get the classification accuracy numbers for the journal paper.
2. **Normative ledger entries (v3 prep)** — expand `LedgerWriteService` to record COMMAND, DECLINE, FAILURE, HANDOFF, DONE (not just EVENT). CommitmentStore is now the live view; ledger would be the immutable historical record.
3. **PR Jlama fixes upstream** — 3 commits in `~/claude/quarkus-langchain4j` to quarkiverse/quarkus-langchain4j. Fixes: devMode jvmOptions, ChatMemoryProcessor @BuildStep, JlamaProcessor @BuildStep.
4. **Paper** — contact Governatori; ADR-0005 is ready to share; session capture at `~/claude/2026-04-23-speech-acts-deontic-session-capture.md`.

## Key Architecture Facts

*Unchanged from previous handover — `git show HEAD~1:HANDOFF.md`*

Plus new:
- CommitmentStore: `Commitment` entity (no unique constraint on correlationId), `CommitmentStore` SPI, `CommitmentService` state machine. All in the `qhorus` named datasource PU.
- `CommitmentService` unit tests live in `testing/src/test/` (not `runtime/src/test/`) — module cycle prevention; `@Inject` field is package-private for direct wiring.
- `findByCorrelationId` returns active (non-terminal) commitment first; falls back to any if all are terminal.

## References

| What | Path |
|---|---|
| CommitmentStore design spec | `docs/superpowers/specs/2026-04-24-commitment-store-design.md` |
| ADR-0005 (theoretical foundation) | `adr/0005-message-type-taxonomy-theoretical-foundation.md` |
| Jlama fix instructions | `~/claude/quarkus-langchain4j/CLAUDE.md` |
| Research session capture | `~/claude/2026-04-23-speech-acts-deontic-session-capture.md` |
| Previous handover | `git show HEAD~1:HANDOFF.md` |
