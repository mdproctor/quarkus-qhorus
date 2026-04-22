# Retrospective Issue Audit — quarkus-qhorus

Generated: 2026-04-20

## Summary

92 commits, 68 issues (#1–#68), 12 epics. All epics have populated Scope
checklists. Two epics use time-based naming; four commit clusters have no
issue coverage; four commits have uncaptured refs to existing issues.

---

## A — Epic Renames (capability-based naming)

| # | Current title | Corrected title |
|---|---|---|
| #32 | Phase 9 — A2A compatibility endpoint for external orchestrator interop | A2A compatibility endpoint for external orchestrator interop |
| #36 | Phase 10 — Human-in-the-loop controls | Human-in-the-loop controls and channel governance |

---

## B — New Issues for Uncovered Functional Commits

### B1 — Initial scaffold and Quarkiverse restructuring
**Scope:** Standalone (predates epic #1 child issues)
**Commits:**
- `7b3cafe` feat: initial Qhorus project scaffold (2026-04-13)
- `4f0475e` refactor: restructure to Quarkiverse extension conventions (2026-04-13)

**Proposed issue:** "Initial Qhorus scaffold and Quarkiverse extension structure"
`enhancement` | standalone

---

### B2 — quarkus-ledger supplement reconciliation
**Scope:** Standalone (related to #57 but separate breaking API changes)
**Commits:**
- `a58bd1c` fix: reconcile with quarkus-ledger supplement refactoring (2026-04-18)
- `a2cf9be` fix(test): switch from Flyway to Hibernate schema generation for tests (2026-04-18)

**Proposed issue:** "Reconcile with quarkus-ledger supplement refactoring — correlationId field + test schema"
`enhancement` | standalone

---

### B3 — Agent protocol comparison documentation
**Scope:** Standalone docs (no code impact)
**Commits (9):**
- `da878b4` docs: add Qhorus vs cross-claude-mcp comparison document (2026-04-14)
- `c7a58c4` docs: add A2A vs ACP vs Qhorus comparison document
- `1611642` docs: reference agent-protocol-comparison.md from DESIGN.md
- `dc01262` docs: add comprehensive multi-agent framework comparison table
- `7345b98` docs: clarify disposable vs durable DB distinction in comparison doc
- `f9c4704` docs: correct framing of Quarkiverse conventions
- `e265641` docs: incorporate nuanced feedback — native warm-up, LLM-agnostic
- `366346b` docs: full editorial pass on comparison doc
- `927dd15` docs: add phases 10-12 to design roadmap doc

**Proposed issue:** "Comparative analysis documentation — A2A, ACP, multi-agent frameworks, cross-claude-mcp"
`documentation` | standalone

---

### B4 — Retrospective blog documentation
**Scope:** Standalone docs
**Commits:**
- `7aa9755` docs: blog entry mdp03 — Phase 12 observability and Claudony MCP blocker (2026-04-16)
- `33fc59a` docs: retrospective blog entries — Day Zero through Phase 12 + ledger reconciliation (2026-04-20)

**Proposed issue:** "Retrospective blog documentation — session diary entries Phases 1–12"
`documentation` | standalone

---

## C — Commits with Missing Refs to Existing Issues (note only)

Cannot be fixed without amending commits. Documented for the record.

| Commit | Subject | Should ref |
|---|---|---|
| `b4cc0de` | test: creative edge-case review — 64 new tests, 2 critical bugs fixed | #21 |
| `ee6d912` | test: address Phase 2 code review | #20 |
| `c2b043a` | test: address Phase 3 code review — two critical bugs | #20 |
| `a855fda` | test: address Phase 1 code review — coverage gaps | #20 |
| `c89e4d5` | docs: ADR-0001 — MCP tool return type strategy | #55 |
| `7c46314` | docs: update ADR-0001 and DESIGN.md — @WrapBusinessError | #56 |
| `e475ae2` | docs: update ecosystem design doc reference to claudony | #1 |

---

## D — Excluded Commits (trivial, no issue needed)

| Commit | Reason |
|---|---|
| `ed46230`, `4a4428f`, `6300c63`, `169cf6ef`, `c3cb03f`, `7d650c6`, `22e9d75`, `69c6914` | Session handovers — operational artifacts |
| `bdf1716` | Session wrap / meta-docs |
| `4e1d151` | Idea log entry |
| `d56c78f` | chore: add .worktrees/ to .gitignore |
| `5e8d8fe` | chore: remove accidentally committed sources |
| `8168eea`, `1a560f6`, `eb9e8f7` | Project briefing docs |
| `d238b19` | Minor CLAUDE.md cleanup (1 line removed) |
| `a6a8bd3` | DESIGN.md sync — covered by epics #7 and #12 |
| `ff29335`, `a57655b` | DESIGN.md phase-completion status updates |
| `c0b880b` | DESIGN.md phase-completion status update |
| `a8620d9` | DESIGN.md phase-completion status update |
| `b379115` | DESIGN.md phase-completion status update |
| `646df78` | CLAUDE.md Work Tracking rule addition |

---

## Actions

1. Rename epic #32 (drop "Phase 9 —" prefix)
2. Rename epic #36 (drop "Phase 10 —" prefix)
3. Create and close 4 new issues (B1–B4)
4. Section C: note only, no git history amendment

---

# Update — 2026-04-22

Extension covering commits from 2026-04-20 through 2026-04-22.
All work was tracked through issue-workflow during implementation.
No new issues needed — mapping to existing closed issues only.

## Summary

55 new commits → mapped to existing issues #73–#87 across epic #73 and
standalone issues #74, #82–#87.

---

## Issue #74 — Reactive store layer (closed 2026-04-20)

**Commits:**
| Hash | Subject |
|---|---|
| `50a66a6` | feat(store): Reactive*Store interfaces for all 5 domains |
| `cf4003f` | fix(ledger): use EntityManager for LedgerAttestation — plain @Entity not Panache |
| `5704d0f` | feat(store): reactive Panache repo helpers for all 7 entity types |
| `2e7dc87` | feat(store): ReactiveJpaChannelStore + ReactiveJpaMessageStore |
| `9a2ea28` | feat(testing): InMemoryReactive*Store wrappers + unit tests (all 5 domains) |
| `3492d53` | feat(store): ReactiveJpaInstanceStore + ReactiveJpaDataStore + ReactiveJpaWatchdogStore |
| `f4d041f` | test(store): ReactiveJpa*Store integration tests + reactive test profile |
| `6aa6567` | fix(test): add reactive.url and hibernate-reactive.database.generation to test profile |
| `144bea4` | fix(ledger): track LedgerAttestation EntityManager fix |
| `8a7f49a` | fix(testing): add missing Watchdog NOT NULL fields in test factory |

---

## Epic #73 — Reactive dual-stack (closed 2026-04-21)

### Issue #76 — QhorusMcpToolsBase extraction

| Hash | Subject |
|---|---|
| `1db84e7` | refactor(mcp): extract QhorusMcpToolsBase — records, mappers, validators |

### Issue #77 — Reactive services (all 5 domains + ledger)

| Hash | Subject |
|---|---|
| `5daa2a3` | feat(channel): ReactiveChannelService — Uni<T> mirror of ChannelService |
| `508c45b` | feat(instance): ReactiveInstanceService — Uni<T> mirror of InstanceService |
| `c0a4263` | feat(message): ReactiveMessageService — core send + poll methods |
| `911c9b4` | feat(data): ReactiveDataStore#hasClaim + ReactiveDataService |
| `51a7e55` | feat(watchdog,ledger): ReactiveWatchdogService + ReactiveLedgerWriteService |

### Issue #78 — ReactiveQhorusMcpTools (39 tools)

| Hash | Subject |
|---|---|
| `d99f6d3` | feat(mcp): ReactiveQhorusMcpTools — 20 pure reactive tools (Task 1/2) |
| `3207fac` | feat(mcp): ReactiveQhorusMcpTools — 19 @Blocking tools (Task 2/2) |

### Issue #79 — Build-time activation + reactive REST

| Hash | Subject |
|---|---|
| `f8a80df` | feat(deploy): reactive activation build step + config |
| `2599a9f` | feat(api,mcp): @IfBuildProperty/@UnlessBuildProperty on conflicting beans |
| `94beac1` | feat(api): ReactiveAgentCardResource + ReactiveA2AResource |

### Issue #80 — Contract test bases + @Disabled reactive runners

| Hash | Subject |
|---|---|
| `c19c336` | test(store): contract base classes — 5 domains, blocking + reactive runners |
| `9249974` | test(service): contract base classes + blocking runners + @Disabled reactive runners |
| `15ed391` | test(smoke): @Disabled ReactiveSmokeTest skeleton |

### Issue #81 — DESIGN.md + ADR-0003 + CLAUDE.md documentation

| Hash | Subject |
|---|---|
| `df4ea69` | docs: DESIGN.md + ADR-0003 + CLAUDE.md — reactive dual-stack (#81) |

---

## Epic #82 — Named datasource isolation + MessageStore SPI closure (closed 2026-04-22)

### Issue #83 — MessageStore SPI aggregate methods + bypass closure

| Hash | Subject |
|---|---|
| `d52b6ec` | feat(store): add countAllByChannel + distinctSendersByChannel to MessageStore SPI |
| `98e910f` | feat(testing): InMemoryMessageStore aggregate methods + contract tests |
| `49634dd` | feat(store): implement JpaMessageStore aggregate methods |
| `b5ebfc0` | feat(store): implement ReactiveJpaMessageStore aggregate methods |
| `610ad6e` | refactor(mcp): route aggregate queries through MessageStore SPI in QhorusMcpTools |
| `311aa1e` | refactor(mcp,watchdog): close final EntityManager bypasses — SPI fully sealed |
| `c91251f` | test: add JPA integration tests for MessageStore aggregate methods |
| `0ae6ded` | fix: ordering consistency, reactive comment, and test assertion quality |

### Issue #86 — PendingReplyStore as sixth SPI interface (closed 2026-04-22)

| Hash | Subject |
|---|---|
| `84f5b49` | feat: add PendingReplyStore as sixth store SPI interface |
| `caa7e01` | fix: code quality corrections for PendingReplyStore SPI |
| `7315305` | refactor: wire PendingReplyStore into MessageService and PendingReplyCleanupJob |
| `f83889c` | fix: deleteExpiredBefore returns long count, cleanup job uses single call |

### Issue #84 — Named datasource migration

| Hash | Subject |
|---|---|
| `0da83ea` | feat(config): migrate to named datasource 'qhorus' — quarkus.datasource.qhorus.* |
| `6da81b4` | fix(test): add named qhorus datasource config to TestProfile restarts |
| `c8e962f` | docs(adr): ADR-0004 named datasource isolation + ledger coupling revisit marker |

---

## Excluded — 2026-04-22 update (trivial/session artifacts)

| Commit | Reason |
|---|---|
| `0eacd99`, `91baa31`, `bdf12aa`, `ee76ed9`, `546027b` | Session handovers |
| `6d15a23`, `26a3f60`, `42d2231` | Blog entries |
| `6f931a2`, `8266b6f` | Design spec / plan docs |
| `22f14e2`, `dbb1c38` | Minor plan/doc fixes |
| `bdf1716`, `ed4623` | Session wrap / meta-docs |
| `4e1d151` | Idea log entry |
| `28c190e` | CLAUDE.md testing convention update |

