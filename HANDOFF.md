# Quarkus Qhorus — Session Handover
**Date:** 2026-04-27 — Epic #110 ledger query capabilities session

---

## What Was Done This Session

- **Epic #110 closed** — 6 ledger query capabilities implemented, tested, documented, merged to main.
  - 6 new repository methods: `findAllByCorrelationId`, `findAncestorChain`, `findStalledCommands`, `countByOutcome`, `findByActorIdInChannel`, `findEventsSince`
  - Enhanced `list_ledger_entries`: `correlation_id` filter + `sort` (asc/desc) + `entry_id` in output
  - 5 new MCP tools: `get_obligation_chain`, `get_causal_chain`, `list_stalled_obligations`, `get_obligation_stats`, `get_telemetry_summary`
  - All tools mirrored in `ReactiveQhorusMcpTools`; 6 response records added to `QhorusMcpToolsBase`
  - 866 tests, 0 failures (up from 783)
- **CLAUDE.md updated** — `@TestTransaction` + `REQUIRES_NEW` + `@BeforeEach` gotcha documented; project structure updated (~47 tools, new repo methods, new response records).
- **Design doc updated** — 5 new tools added to MCP surface; milestone 12 description updated.
- **Garden entries submitted** — `GE-20260427-452889` (@TestTransaction + REQUIRES_NEW @BeforeEach) and `GE-20260427-c77ee9` (JPA stub overload fallthrough).
- **Claudony doc still pending** — `claudony-agent-mesh-framework.md` line 705 needs `agent_id?`→`sender?` (carried from previous session).

## Current State

- **Branch:** `main` — everything merged and pushed
- **Open epics:** #119 (MCP consistency — #121 for-review decisions pending), #122 (agent mesh dashboard example)
- **`#98` parked** — classification accuracy baseline; run when stepping away from laptop
- **Jlama PRs** — submitted upstream, waiting on quarkiverse reviewers

## Immediate Next Step

Address pending decisions in **#121** — 8 items (A–H) need explicit choices before any implementation (each is a potential breaking change). Read #121, decide each, create child issues under #119 or close with rationale.

Alternatively: begin **#122** (agent mesh dashboard example) — `examples/agent-mesh-dashboard/` module, `ClaimScenarioDriver`, `ObligationBoardBuilder`, Tamboui TUI. Spec at `docs/superpowers/specs/2026-04-26-ledger-query-capabilities-design.md` §§6–8.

## Key Architecture Facts

*Unchanged — `git show HEAD~1:HANDOFF.md`*

Plus new:
- `findAncestorChain` is iterative (no recursive JPQL) — visited Set for cycle protection, `Collections.reverse()` for oldest-first ordering
- `@TestTransaction` + `REQUIRES_NEW` + `@BeforeEach`: REQUIRES_NEW suspends the outer test transaction; JPA EntityManager loses visibility of uncommitted entities after resume. Fix: all setup inside `@Test` body, not `@BeforeEach`
- `countByOutcome` semantics: COMMAND→HANDOFF→DONE yields `delegated=1` AND `fulfilled=1` simultaneously — each message type counted independently

## References

| What | Path |
|---|---|
| Latest blog | `blog/2026-04-27-mdp02-six-ways-to-query.md` |
| MCP for-review decisions | GitHub issue #121 |
| Agent mesh dashboard spec | `docs/superpowers/specs/2026-04-26-ledger-query-capabilities-design.md` §§6–8 |
| Previous handover | `git show HEAD~1:HANDOFF.md` |
