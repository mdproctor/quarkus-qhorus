# Quarkus Qhorus — Claude Code Project Guide

## Project Type

type: java

**Stack:** Java 21 (on Java 26 JVM), Quarkus 3.32.2, GraalVM 25 (native image), quarkus-mcp-server 1.11.1

---

## What This Project Is

Qhorus is a Quarkus extension providing an agent communication mesh — the peer-to-peer coordination layer for multi-agent AI systems. It is the Quarkus port of cross-claude-mcp (`~/claude/cross-claude-mcp`), redesigned based on research into A2A, AutoGen, LangGraph, Swarm, Letta, and CrewAI.

Any Quarkus app adds `io.quarkiverse.qhorus:quarkus-qhorus` as a dependency and its agents immediately get:
- **Typed channels** with declared update semantics (APPEND, COLLECT, BARRIER, EPHEMERAL, LAST_WRITE)
- **Typed messages** (query · command · response · status · decline · handoff · done · failure · event) — 9-type speech-act taxonomy; see ADR-0005
- **Shared data store** with artefact lifecycle management (claim/release, UUID refs, chunked streaming)
- **Instance registry** with capability tags and three addressing modes (by id · by capability · by role)
- **`wait_for_reply`** long-poll with correlation IDs and SSE keepalives
- **Agent Cards** at `/.well-known/agent-card.json` for A2A ecosystem discovery
- **Structured event observability** — every EVENT message creates an `AgentMessageLedgerEntry` (via `quarkus-ledger`) with SHA-256 tamper evidence; queryable via `list_events` and `get_channel_timeline` MCP tools

Qhorus is designed to be embedded in Claudony (`~/claude/claudony`) as part of the broader Quarkus Native AI Agent Ecosystem, and eventually submitted to Quarkiverse.

---

## Quarkiverse Naming

This project follows Quarkiverse naming conventions throughout:

| Element | Value |
|---|---|
| GitHub repo | `casehubio/quarkus-qhorus` (→ `quarkiverse/quarkus-qhorus` when submitted) |
| groupId | `io.quarkiverse.qhorus` |
| Parent artifactId | `quarkus-qhorus-parent` |
| Runtime artifactId | `quarkus-qhorus` |
| Deployment artifactId | `quarkus-qhorus-deployment` |
| Root Java package | `io.quarkiverse.qhorus` |
| Runtime subpackage | `io.quarkiverse.qhorus.runtime` |
| Deployment subpackage | `io.quarkiverse.qhorus.deployment` |
| Config prefix | `quarkus.qhorus` |
| Feature name | `qhorus` |

---

## Ecosystem Context

```
casehub (orchestration engine)   quarkus-qhorus (communication mesh)
           ↑                              ↑
           └──────── claudony (integration layer) ──────────┘
```

Qhorus has no dependency on CaseHub or Claudony — it is the independent communication layer.

---

## Project Structure

```
quarkus-qhorus/
├── runtime/                             — Extension runtime module
│   └── src/main/java/io/quarkiverse/qhorus/runtime/
│       ├── config/QhorusConfig.java     — @ConfigMapping(prefix = "quarkus.qhorus")
│       ├── channel/
│       │   ├── Channel.java             — PanacheEntity
│       │   ├── ChannelSemantic.java     — enum: APPEND|COLLECT|BARRIER|EPHEMERAL|LAST_WRITE
│       │   └── ChannelService.java
│       ├── message/
│       │   ├── Message.java             — PanacheEntity
│       │   ├── MessageType.java         — enum: QUERY|COMMAND|RESPONSE|STATUS|DECLINE|HANDOFF|DONE|FAILURE|EVENT (speech-act taxonomy, see ADR-0005)
│       │   ├── Commitment.java          — PanacheEntity (full obligation lifecycle: OPEN→FULFILLED/DECLINED/FAILED/DELEGATED/EXPIRED)
│       │   ├── CommitmentState.java     — enum: 7 states, isTerminal()
│       │   ├── CommitmentService.java   — state machine: open/acknowledge/fulfill/decline/fail/delegate/expireOverdue
│       │   └── MessageService.java
│       ├── instance/
│       │   ├── Instance.java            — PanacheEntity
│       │   ├── Capability.java          — PanacheEntity (capability tags)
│       │   └── InstanceService.java
│       ├── data/
│       │   ├── SharedData.java          — PanacheEntity
│       │   ├── ArtefactClaim.java       — PanacheEntity (claim/release lifecycle)
│       │   └── DataService.java
│       ├── ledger/
│       │   ├── AgentMessageLedgerEntry.java         — JPA JOINED subclass of LedgerEntry (quarkus-ledger)
│       │   ├── AgentMessageLedgerEntryRepository.java — typed repository; findByChannelId
│       │   └── LedgerWriteService.java              — writes ledger entry on every structured EVENT
│       ├── mcp/
│       │   ├── QhorusMcpToolsBase.java  — abstract base: 23 records, 7 mappers, 3 validators
│       │   ├── QhorusMcpTools.java      — blocking @Tool methods (39); @UnlessBuildProperty(reactive.enabled)
│       │   └── ReactiveQhorusMcpTools.java — reactive @Tool methods returning Uni<T>; @IfBuildProperty(reactive.enabled)
│       ├── watchdog/
│       │   ├── Watchdog.java            — PanacheEntity (condition-based alert registrations)
│       │   ├── WatchdogEvaluationService.java — condition evaluation logic
│       │   └── WatchdogScheduler.java   — @Scheduled driver (delegates to service)
│       └── api/
│           ├── AgentCardResource.java   — GET /.well-known/agent-card.json (@UnlessBuildProperty)
│           ├── A2AResource.java         — POST /a2a/message:send, GET /a2a/tasks/{id} (@UnlessBuildProperty)
│           ├── ReactiveAgentCardResource.java — reactive Uni<Response> agent card (@IfBuildProperty)
│           └── ReactiveA2AResource.java       — reactive A2A endpoints (@IfBuildProperty)
├── deployment/                          — Extension deployment (build-time) module
│   └── src/main/java/io/quarkiverse/qhorus/deployment/
│       ├── QhorusBuildConfig.java       — @ConfigRoot(BUILD_TIME): quarkus.qhorus.reactive.enabled
│       └── QhorusProcessor.java         — @BuildStep: FeatureBuildItem + reactive bean activation
├── testing/                             — InMemory*Store + InMemoryReactive*Store (@Alternative @Priority(1)) for consumer unit tests
├── examples/
│   ├── examples/type-system/            — Fast regression tests for the 9-type taxonomy; runs in CI with no model (MessageTaxonomyTest)
│   └── examples/agent-communication/    — Real LLM agent examples (Jlama); 3 enterprise scenarios + accuracy baseline; activate with -Pwith-llm-examples
├── docs/specs/                          — Design specs
└── .github/                             — Quarkiverse CI workflows
```

---

## Build and Test

```bash
# Build all modules
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn clean install

# Run tests (runtime module)
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test

# Run specific test
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -Dtest=ClassName -pl runtime

# Native image build (requires GraalVM)
JAVA_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home \
  mvn package -Pnative -DskipTests
```

**Use `mvn` not `./mvnw`** — maven wrapper not configured on this machine.

**Testing conventions:**
- `@TestTransaction` + RestAssured HTTP calls do NOT share a transaction — injected writes are uncommitted and invisible to the HTTP handler. Avoid `@TestTransaction` on tests that mix injected calls with RestAssured. Use unique names per test for isolation instead.
- For tests requiring concurrent execution (e.g. cancel-while-blocking), use `@Inject ManagedExecutor executor` rather than raw `ExecutorService` — `ManagedExecutor` propagates Quarkus CDI context so `@Transactional` works on background threads.
- Optional modules (`a2a`, `watchdog`) require a `@TestProfile` that sets `quarkus.qhorus.<module>.enabled=true`. Any `@TestProfile` that causes Quarkus to restart must also include the full `quarkus.datasource.qhorus.*` block (db-kind, jdbc.url, username, password) plus `quarkus.datasource.qhorus.reactive=false` and `quarkus.hibernate-orm.qhorus.database.generation=drop-and-create` in `getConfigOverrides()` — Quarkus restarts do not inherit test `application.properties` from prior context.
- `RateLimiter` and `ObserverRegistry` are `@ApplicationScoped` in-memory beans — their state does NOT roll back with `@TestTransaction`. Use unique channel names and observer IDs per test to avoid cross-test interference.
- `check_messages` excludes `EVENT` messages by design — never assert EVENT counts via `check_messages`. Use `read_observer_events` (with a registered observer ID) to assert EVENT delivery in tests.
- `LedgerWriteService` silently skips EVENT messages whose content does not start with `{` (non-JSON). Structured telemetry events must include `tool_name` (String) and `duration_ms` (Long) — missing either → ledger entry skipped with a WARN log. Tests asserting ledger entries must use valid JSON payloads with both mandatory fields.
- `*Store` interfaces are the persistence seam — seven interfaces: `ChannelStore`, `MessageStore`, `InstanceStore`, `DataStore`, `WatchdogStore`, `PendingReplyStore` (approval gates only), `CommitmentStore` (full obligation lifecycle). Services inject stores, not Panache entity statics. Integration tests (`@QuarkusTest`) inject `*Store` directly; unit tests use `InMemory*Store` from `quarkus-qhorus-testing`.
- `CommitmentService` unit tests live in `testing/src/test/...` (not `runtime/src/test/...`) to avoid a module cycle — the testing module provides `InMemoryCommitmentStore` used for CDI-free wiring. `service.store = store` sets the dependency directly.
- `CommitmentStoreContractTest` (abstract, in `testing/src/test/`) has two runners: `InMemoryCommitmentStoreTest` (blocking) and `InMemoryReactiveCommitmentStoreTest` (reactive, wraps with `.await().indefinitely()`).
- `quarkus.http.test-port=0` is set in test `application.properties` to use a random port — avoids conflicts when other Quarkus apps (e.g. Claudony) are running on 8081. Requires `mvn clean` to take effect after the property is added.
- Tests run against a **named 'qhorus' datasource** (`quarkus.datasource.qhorus.*`) — the Qhorus named PU. A default datasource is also configured in test properties to satisfy quarkus-ledger library beans that inject `@Default EntityManager` (library code; cannot be changed). Both PUs require schema generation: `quarkus.hibernate-orm.database.generation=drop-and-create` (default PU) and `quarkus.hibernate-orm.qhorus.database.generation=drop-and-create` (qhorus PU). `quarkus.datasource.qhorus.reactive=false` suppresses `FastBootHibernateReactivePersistenceProvider` for the named PU.
- `Reactive*Store` / `InMemoryReactive*Store` follow the same seam as blocking stores. Unit tests use `InMemoryReactive*Store` from `quarkus-qhorus-testing` via direct instantiation (no CDI) and `.await().indefinitely()` to unwrap. `ReactiveJpa*Store` integration tests use `@QuarkusTest @TestProfile @RunOnVertxContext UniAsserter` with `Panache.withTransaction()` wrapping mutations.
- Reactive JPA integration tests cannot use H2 — H2 has no native async driver and `vertx-jdbc-client` alone does not register a Quarkus reactive pool factory. Only `quarkus-reactive-pg-client` (PostgreSQL + Docker) enables reactive `@QuarkusTest`. Write reactive JPA tests as `@Disabled` until Docker is available.
- `@IfBuildProperty` and `@UnlessBuildProperty` are BUILD-TIME annotations — setting `quarkus.qhorus.reactive.enabled=true` in a `QuarkusTestProfile.getConfigOverrides()` does NOT activate or deactivate those beans. Only the build-time value matters. Tests that need to exercise `ReactiveQhorusMcpTools` must be compiled with the property set, which is not currently supported in the CI H2 test environment.
- `ReactiveTestProfile` (in `runtime/src/test/.../service/`) activates reactive `@Alternative` service beans via `quarkus.arc.selected-alternatives`. Use it only on `@Disabled` reactive service runners — calling `Panache.withTransaction()` on H2 without a reactive driver will fail at runtime, even with the profile active.
- Reactive service tests (`Reactive*ServiceTest`) are all `@Disabled` — reactive services call `Panache.withTransaction()` which needs a native reactive datasource driver. Enable by removing `@Disabled` and adding PostgreSQL Dev Services to `ReactiveTestProfile` when Docker is available.
- Store contract tests use abstract base classes (`*StoreContractTest` in `testing/src/test/.../contract/`) with two concrete runners each: blocking (`InMemory*StoreTest`) and reactive (`InMemoryReactive*StoreTest`). The reactive runner wraps every factory method with `.await().indefinitely()`. Assertion code is identical across both stacks (inherited from base).
- When working in a git worktree, always use `mvn -f /absolute/path/to/worktree/pom.xml` — do not rely on `cd` since shell CWD resets between Bash tool calls.
- `examples/type-system/` runs in CI (no model, no Jlama). `examples/agent-communication/` is behind `-Pwith-llm-examples` — requires local Jlama fixes installed from `~/claude/quarkus-langchain4j` and model in `~/.jlama/` (~700MB, first run only). See `examples/agent-communication/README.md`.

**Quarkiverse format check:** CI runs `mvn -Dno-format` to skip the enforced Quarkiverse code formatting. Run `mvn` locally to apply formatting (via Quarkiverse parent's formatter plugin).

---

## Java and GraalVM on This Machine

```bash
# Java 26 (Oracle, system default) — use for dev and tests
JAVA_HOME=$(/usr/libexec/java_home -v 26)

# GraalVM 25 — use for native image builds only
JAVA_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home
```

**Native gotcha:** `quarkus-mcp-server` 1.11.1 fixed sampling and elicitation in native image — they were silently broken in earlier versions. Always use ≥ 1.11.1.

---

## Design Document

`docs/specs/2026-04-13-qhorus-design.md` is the primary design spec. It incorporates research from A2A, AutoGen, LangGraph, OpenAI Swarm, Letta, and CrewAI.

---

## Work Tracking

**Issue tracking:** enabled
**GitHub repo:** casehubio/quarkus-qhorus

**Automatic behaviours (Claude follows these at all times in this project):**
- **Before implementation begins** — check if an active issue exists. If not, run issue-workflow Phase 1 before writing any code.
- **Before any commit** — run issue-workflow Phase 3 to confirm issue linkage.
- **All commits should reference an issue** — `Refs #N` (ongoing) or `Closes #N` (done).
- **Code review fix commits** — when committing fixes found during a code review (superpowers:requesting-code-review or java-code-review), create or reuse an issue for that review work **before** committing. Use `Refs #N` pointing at the relevant epic even if it is already closed. Do not commit review fixes without an issue reference.

---

## Writing Style Guide

**The writing style guide at `~/claude-workspace/writing-styles/blog-technical.md` is mandatory for all blog and diary entries.** Load it in full before drafting. Complete the pre-draft voice classification (I / we / Claude-named) before generating any prose. Do not show a draft without verifying it against the style guide.

**Blog directory:** `blog/`
