# Quarkus Qhorus — Session Handover
**Date:** 2026-04-16 (sixth session)

## What Was Done This Session

**quarkus-ledger (new repo, ~/claude/quarkus-ledger)**
- Created standalone Quarkiverse extension extracted from quarkus-tarkus
- DESIGN.md, README ecosystem context, integration guide, examples.md, runnable example
- @ConfigRoot fix (quarkus.ledger.* keys now recognised), Flyway ordering warning documented
- 33 unit tests; garden entry GE-20260415-d7a439 (Hortora/garden#57)
- HANDOFF.md + docs/DESIGN.md written

**quarkus-tarkus** — migrated to quarkus-ledger (WorkItemLedgerEntry, 69 tests)

**Phase 12 — Structured observability** (closed #50–#54)
- AgentMessageLedgerEntry, LedgerWriteService, list_events, get_channel_timeline
- 36 new tests; 521 → 557 tests

**Phase 12 housekeeping**
- DESIGN.md Phase 12 ✅
- @ConfigRoot fix deployed to quarkus-ledger and Qhorus CLAUDE.md updated

**Claudony unblocking work (done in this session, not Qhorus repo)**
- McpServer.java → quarkus-mcp-server migration (ClaudonyMcpTools, issue #52)
- Test layer restructuring: ClaudonyMcpToolsTest + McpProtocolTest (issue #53)
- Phase 8 briefing written: docs/phase8-claudony-integration.md

**Error handling — this session (Qhorus side)**
- Option A: claim_artefact and release_artefact (String tools) wrapped
- list_events Instant.parse and delete_watchdog UUID.fromString: better error messages
- @WrapBusinessError({IAE, ISE}) on QhorusMcpTools — all 37 structured-return @Tool
  methods now produce isError:true instead of JSON-RPC -32603 errors (issue #56)
- 80 test assertions updated: IllegalArgumentException → ToolCallException
- A2AResource updated: catches ToolCallException alongside IAE
- ToolErrorHandlingTest: 4 tests (CDI + HTTP level)
- ADR-0001: MCP tool return type strategy documented (String vs Structured, Option C)

**Docs**
- ADR-0001: adr/0001-mcp-tool-return-type-strategy.md
- DESIGN.md §MCP Tool Design added
- Phase 8 briefing updated: 39 tools (not 38), hardening prerequisite noted

## Current State

- **Tests:** 561 passing, 0 failing
- **Open issues:** none in quarkus-qhorus
- **Phase 8:** blocked on Claudony hardening (issue #55) — do not start until complete
- **Claudony:** other Claude session working on issue #55 (error handling + timeouts + tests)

## Immediate Next Step

Wait for Claudony issue #55 to close. Then start **Phase 8** (embed Qhorus in Claudony):
- Run issue-workflow Phase 1 in this repo first
- Brief: `docs/phase8-claudony-integration.md`
- Prerequisite check: `mvn test -pl runtime` must show 561 green before touching Claudony

## Critical Note for Phase 8

`@WrapBusinessError` is now on `QhorusMcpTools`. Any Claudony code that calls
`QhorusMcpTools` methods via CDI injection will receive `ToolCallException` (not the raw
`IllegalArgumentException`/`IllegalStateException`) when a @Tool method fails. Handle
accordingly — see A2AResource.java for the pattern.

## References

| What | Path |
|---|---|
| Design spec | `docs/specs/2026-04-13-qhorus-design.md` |
| Implementation tracker | `docs/DESIGN.md` (all phases ✅; Phase 8 pending) |
| MCP tool strategy ADR | `adr/0001-mcp-tool-return-type-strategy.md` |
| Phase 8 briefing | `docs/phase8-claudony-integration.md` |
| Claudony hardening plan | `docs/claudony-mcp-test-improvements.md` |
| quarkus-ledger | `~/claude/quarkus-ledger/` |
| Previous handover | `git show HEAD~1:HANDOFF.md` |
