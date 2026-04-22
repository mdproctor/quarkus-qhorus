# Quarkus Qhorus — Session Handover
**Date:** 2026-04-22 (thirteenth session — named datasource + SPI fully sealed)

## What Was Done This Session

Claudony requested two things: (1) named datasource isolation per ecosystem convention, (2) SPI completeness audit. Both delivered.

- **Named datasource** — all Qhorus entities on `quarkus.datasource.qhorus.*` / `quarkus.hibernate-orm.qhorus.*` / `quarkus.flyway.qhorus.*`. Default datasource retained in tests only (quarkus-ledger library beans inject `@Default EntityManager` — library code, can't change). ADR-0004 written with ledger coupling revisit marker.
- **SPI audit** — 3 `Message.getEntityManager()` bypasses closed in `QhorusMcpTools`, `ReactiveQhorusMcpTools`, `WatchdogEvaluationService`. Two new `MessageStore` methods added: `countAllByChannel()` and `distinctSendersByChannel()`.
- **PendingReplyStore** — sixth store SPI interface added (bonus from a rogue subagent; closed the last JPA leak in `wait_for_reply`).
- CLAUDE.md updated, blog written, 3 forage entries submitted, GitHub issues closed.

## Current State

- **Branch:** `main`
- **Tests:** 716 runtime (44 skipped = `@Disabled` reactive runners) + 120 testing module + 4 examples
- **Open issue:** #87 — `ReactiveJpaMessageStore.countAllByChannel()` uses in-memory `listAll()` instead of GROUP BY; harmless while `@Disabled`, needs fixing before reactive goes live
- **Uncommitted:** `.claude/settings.local.json` + 6 untracked plan files in `docs/superpowers/plans/`

## Key Architecture Facts for Claudony Integration

- **Embedding app config:** must use `quarkus.datasource.qhorus.*`, NOT `quarkus.datasource.*`
- **PU packages:** `quarkus.hibernate-orm.qhorus.packages=io.quarkiverse.qhorus.runtime,io.quarkiverse.ledger.runtime`
- **Ledger coupling:** `AgentMessageLedgerEntry extends LedgerEntry` — both in "qhorus" PU until quarkus-ledger gets its own PU (ADR-0004 revisit trigger)
- **`@TestProfile` restarts:** must include full `quarkus.datasource.qhorus.*` block in `getConfigOverrides()` — test `application.properties` is NOT re-read on context restart
- **Six SPI interfaces:** ChannelStore, MessageStore, InstanceStore, DataStore, WatchdogStore, PendingReplyStore — all have InMemory alternatives in `quarkus-qhorus-testing`
- **SPI fully sealed:** no `getEntityManager()` or Panache statics anywhere outside JPA store implementations and `AgentMessageLedgerEntryRepository`

## Immediate Next Step

**Claudony integration** — five concrete tasks:
1. Rename `quarkus.datasource.*` → `quarkus.datasource.qhorus.*` in Claudony's `application.properties`
2. Fix the wrong package in Claudony spec (`runtime.entity` → `runtime`)
3. Add `quarkus-qhorus-testing` as test-scope dep in Claudony's pom.xml
4. Remove `%test.quarkus.datasource.*` / `%test.quarkus.hibernate-orm.*` from Claudony tests
5. Fix `MeshResourceInterjectionTest` — replace `UserTransaction` + Panache deletes with `@AfterEach` InMemory store clears

Also: send Claudony the 5 feedback points from this session (wrong package path, PendingReplyStore gap now closed, Flyway versioning convention, test bootstrapping risk, inaccurate EntityManager statement in spec).

## References

| What | Path |
|---|---|
| Named datasource ADR | `adr/0004-named-datasource-isolation.md` |
| Previous handover | `git show HEAD~1:HANDOFF.md` |
| Blog entry | `blog/2026-04-22-mdp01-named-datasource-rogue-agent.md` |
| Claudony spec (reviewed) | `~/claude/claudony/docs/superpowers/specs/2026-04-22-ecosystem-persistence-isolation-design.md` |
