# Quarkus Qhorus — Claude Code Project Guide

## Project Type

type: java

**Stack:** Java 21 (on Java 26 JVM), Quarkus 3.32.2, GraalVM 25 (native image), quarkus-mcp-server 1.11.1

---

## What This Project Is

Qhorus is a Quarkus extension providing an agent communication mesh — the peer-to-peer coordination layer for multi-agent AI systems. It is the Quarkus port of cross-claude-mcp (`~/claude/cross-claude-mcp`), redesigned based on research into A2A, AutoGen, LangGraph, Swarm, Letta, and CrewAI.

Any Quarkus app adds `io.quarkiverse.qhorus:quarkus-qhorus` as a dependency and its agents immediately get:
- **Typed channels** with declared update semantics (APPEND, COLLECT, BARRIER, EPHEMERAL, LAST_WRITE)
- **Typed messages** (request · response · status · handoff · done · event)
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
| GitHub repo | `mdproctor/quarkus-qhorus` (→ `quarkiverse/quarkus-qhorus` when submitted) |
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
│       │   ├── MessageType.java         — enum: request|response|status|handoff|done|event
│       │   ├── PendingReply.java        — PanacheEntity (correlation ID tracking)
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
│       │   └── QhorusMcpTools.java      — @Tool methods (all MCP tools)
│       ├── watchdog/
│       │   ├── Watchdog.java            — PanacheEntity (condition-based alert registrations)
│       │   ├── WatchdogEvaluationService.java — condition evaluation logic
│       │   └── WatchdogScheduler.java   — @Scheduled driver (delegates to service)
│       └── api/
│           ├── AgentCardResource.java   — GET /.well-known/agent-card.json
│           └── A2AResource.java         — POST /a2a/message:send, GET /a2a/tasks/{id}
├── deployment/                          — Extension deployment (build-time) module
│   └── src/main/java/io/quarkiverse/qhorus/deployment/
│       └── QhorusProcessor.java         — @BuildStep: FeatureBuildItem + native config
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
- Optional modules (`a2a`, `watchdog`) require a `@TestProfile` that sets `quarkus.qhorus.<module>.enabled=true`.
- `RateLimiter` and `ObserverRegistry` are `@ApplicationScoped` in-memory beans — their state does NOT roll back with `@TestTransaction`. Use unique channel names and observer IDs per test to avoid cross-test interference.
- `check_messages` excludes `EVENT` messages by design — never assert EVENT counts via `check_messages`. Use `read_observer_events` (with a registered observer ID) to assert EVENT delivery in tests.
- `LedgerWriteService` silently skips EVENT messages whose content does not start with `{` (non-JSON). Structured telemetry events must include `tool_name` (String) and `duration_ms` (Long) — missing either → ledger entry skipped with a WARN log. Tests asserting ledger entries must use valid JSON payloads with both mandatory fields.

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
**GitHub repo:** mdproctor/quarkus-qhorus

**Automatic behaviours (Claude follows these at all times in this project):**
- **Before implementation begins** — check if an active issue exists. If not, run issue-workflow Phase 1 before writing any code.
- **Before any commit** — run issue-workflow Phase 3 to confirm issue linkage.
- **All commits should reference an issue** — `Refs #N` (ongoing) or `Closes #N` (done).
- **Code review fix commits** — when committing fixes found during a code review (superpowers:requesting-code-review or java-code-review), create or reuse an issue for that review work **before** committing. Use `Refs #N` pointing at the relevant epic even if it is already closed. Do not commit review fixes without an issue reference.

---

## Writing Style Guide

**The writing style guide at `~/claude-workspace/writing-styles/blog-technical.md` is mandatory for all blog and diary entries.** Load it in full before drafting. Complete the pre-draft voice classification (I / we / Claude-named) before generating any prose. Do not show a draft without verifying it against the style guide.

**Blog directory:** `blog/`
