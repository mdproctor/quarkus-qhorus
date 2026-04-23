# Quarkus Qhorus — Session Handover
**Date:** 2026-04-23 (fifteenth session — MessageType redesign complete; speech-act taxonomy; ADR-0005; Jlama issue found)

## What Was Done This Session

- **#87 fixed** — `ReactiveJpaMessageStore.countAllByChannel()` now uses GROUP BY instead of in-memory grouping
- **#88 complete** — Full MessageType redesign: 6-type enum replaced with 9-type speech-act taxonomy (QUERY, COMMAND, RESPONSE, STATUS, DECLINE, HANDOFF, DONE, FAILURE, EVENT); three new `Message` envelope fields (`commitmentId`, `deadline`, `acknowledgedAt`); 40+ REQUEST usages migrated; MCP tools updated with DECLINE/FAILURE/HANDOFF validation and `deadline` param; A2A `deriveState()` updated for FAILURE/DECLINE → "failed"
- **ADR-0005 written** — theoretical foundation: four-layer normative framework (speech acts + deontic + defeasible + social commitment semantics), completeness argument, prior work mapping
- **`examples/agent-communication/` module** — LangChain4j + Jlama (pure Java, `Llama-3.2-1B-Instruct-Jlama-Q4`); three enterprise scenario examples + classification accuracy baseline; README with provider-switching guide
- **Jlama known issue** — Quarkus 3.32.2 bootstrap JSON serializer fails with `Unsupported value type: [ALL-UNNAMED]`; fix is in `~/claude/quarkus-langchain4j/CLAUDE.md`
- **Research session capture** — `~/claude/2026-04-23-speech-acts-deontic-session-capture.md` (30 sections, covers theory, gap analysis, regulated markets, paper strategy, Governatori collaboration angle)

## Current State

- **Branch:** `main` (all merged and pushed)
- **Tests:** 724 runtime (44 `@Disabled` reactive), 120 testing module
- **Open issue:** none — #87 and #88 both closed
- **Uncommitted:** `.claude/settings.local.json` + 7 untracked plan/spec files in `docs/superpowers/`
- **Jlama examples:** compile but cannot run — see Known Issue below

## Known Issue: Jlama + Quarkus 3.32.2

Root cause: `quarkus-langchain4j-jlama` runtime JAR's `quarkus-extension.properties` declares `dev-mode.jvm-option.std.enable-native-access=ALL-UNNAMED`. Quarkus 3.32.2's `Json.appendValue()` can't serialise `Module` objects.

Fix location: `model-providers/jlama/runtime/pom.xml` — conditionalize `<enable-native-access>ALL-UNNAMED</enable-native-access>` on Java < 23.

Repo cloned with CLAUDE.md at: `~/claude/quarkus-langchain4j/` — open a new Claude session there to fix and install locally.

## Immediate Next Steps

1. **Fix Jlama** — open Claude in `~/claude/quarkus-langchain4j/`, follow CLAUDE.md, build and install locally, re-run `mvn test -pl examples/agent-communication` to verify
2. **CommitmentStore (v2)** — generalise `PendingReply` into full commitment store tracking QUERY/COMMAND obligations; `commitmentId` field in `Message` is the bridge
3. **Normative ledger entries (v2)** — expand `LedgerWriteService` to record COMMAND, DECLINE, FAILURE, HANDOFF, DONE; not just EVENT
4. **Paper** — session capture has the full theoretical foundation; Governatori is a known contact — see `~/claude/2026-04-23-speech-acts-deontic-session-capture.md` §27 for collaboration strategy

## Key Architecture Facts

- MessageType redesign: see `adr/0005-message-type-taxonomy-theoretical-foundation.md`
- Four-layer normative framework: speech acts (Layer 1) → social commitments (Layer 2) → temporal (Layer 3) → enforcement/Drools (Layer 4)
- Envelope/payload separation: `Message` envelope is machine-readable; `content` is LLM payload, opaque to infrastructure
- Breaking change: `REQUEST` removed; use `QUERY` (information) or `COMMAND` (action)

## References

| What | Path |
|---|---|
| Design spec (MessageType) | `docs/superpowers/specs/2026-04-23-message-type-redesign-design.md` |
| ADR-0005 (theoretical foundation) | `adr/0005-message-type-taxonomy-theoretical-foundation.md` |
| Research session capture | `~/claude/2026-04-23-speech-acts-deontic-session-capture.md` |
| Jlama fix instructions | `~/claude/quarkus-langchain4j/CLAUDE.md` |
| Garden entry (Jlama bug) | GE-20260423-878486 (jvm/) |
| Previous handover | `git show HEAD~1:HANDOFF.md` |
