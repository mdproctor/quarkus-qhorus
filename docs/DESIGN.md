# CaseHub Qhorus — Design Document

## Overview

Qhorus is a CaseHub library providing a peer-to-peer agent communication
mesh for multi-agent AI systems. Any Quarkus app adds `casehub-qhorus` as a
dependency and its agents get typed channels, typed messages, a shared data
store with artefact lifecycle management, an instance registry with capability
tags, and `wait_for_reply` long-polling with correlation IDs.

Primary design specification: `docs/specs/2026-04-13-qhorus-design.md`

How Qhorus relates to A2A and ACP: `docs/agent-protocol-comparison.md`

Full multi-agent framework comparison (Qhorus, A2A, ACP, AutoGen, LangGraph, CrewAI, Letta, Swarm, MCP): `docs/multi-agent-framework-comparison.md`

---

## Component Structure

Maven multi-module layout:

| Module | Artifact | Purpose |
|---|---|---|
| Parent | `casehub-qhorus-parent` | BOM, version management |
| API | `casehub-qhorus-api` | Enums, SPIs, and extension interfaces — no JPA |
| Runtime | `casehub-qhorus` | Extension runtime — entities, services, MCP tools, REST |
| Deployment | `casehub-qhorus-deployment` | Build-time processor — feature registration, native config |

---

## Technology Stack

| Concern | Choice | Notes |
|---|---|---|
| Runtime | Java 21 (on Java 26 JVM) | `maven.compiler.release=21` |
| Framework | Quarkus 3.32.2 | Quarkus platform; casehub-parent for BOM |
| Persistence | Hibernate ORM + Panache (active record) | Panache `PanacheEntityBase`, UUID PKs |
| Reactive persistence | Hibernate Reactive Panache (optional) | `quarkus-hibernate-reactive-panache`; `@Alternative` reactive SPI beans; activate with a reactive datasource |
| Schema migrations | Flyway | `V1__initial_schema.sql`; consuming app owns datasource config |
| MCP transport | `quarkus-mcp-server-http` 1.11.1 | Streamable HTTP (MCP spec 2025-06-18) |
| JDBC (dev/test) | H2 (optional dep) | PostgreSQL for production |
| Native image | GraalVM 25 | `JAVA_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/...` |

---

## Domain Model

Seven Panache entities across four packages. All use UUID primary keys set in
`@PrePersist`; timestamps set in `@PrePersist` / `@PreUpdate`.

### Channel (`runtime/channel/`)

| Entity | Key Fields | Notes |
|---|---|---|
| `Channel` | `id UUID`, `name`, `semantic`, `barrierContributors`, `allowedWriters`, `adminInstances`, `rateLimitPerChannel`, `rateLimitPerInstance`, `createdAt`, `lastActivityAt` | Write ACL, management ACL, and rate limits are all nullable (null = unrestricted) |
| `ChannelSemantic` | `APPEND \| COLLECT \| BARRIER \| EPHEMERAL \| LAST_WRITE` | Enum; stored as STRING |

### Message (`runtime/message/`)

| Entity | Key Fields | Notes |
|---|---|---|
| `Message` | `id BIGINT` (sequence), `channelId UUID FK`, `sender`, `messageType`, `content TEXT`, `correlationId`, `inReplyTo`, `replyCount` (denormalized), `artefactRefs` | Sequence PK for ordering |
| `MessageType` | `REQUEST \| RESPONSE \| STATUS \| HANDOFF \| DONE \| EVENT` | `EVENT` is observer-only (`isAgentVisible() = false`) |
| `PendingReply` | `id UUID`, `correlationId` (unique), `instanceId`, `channelId`, `expiresAt` | Tracks `wait_for_reply` correlation |

### Instance (`runtime/instance/`)

| Entity | Key Fields | Notes |
|---|---|---|
| `Instance` | `id UUID`, `instanceId` (unique string), `description`, `status` (online/offline/stale), `claudonySessionId` (optional), `lastSeen`, `registeredAt` | `claudonySessionId` always optional |
| `Capability` | `id UUID`, `instanceId UUID FK`, `tag` | One row per tag per instance |

### Shared Data (`runtime/data/`)

| Entity | Key Fields | Notes |
|---|---|---|
| `SharedData` | `id UUID`, `data_key` (unique), `content TEXT`, `createdBy`, `description`, `complete BOOLEAN`, `sizeBytes` (denormalized) | Column `data_key` (not `key` — reserved word in H2) |
| `ArtefactClaim` | `id UUID`, `artefactId UUID FK`, `instanceId UUID FK`, `claimedAt` | Claim/release lifecycle for GC |

---

## Services

All services are `@ApplicationScoped`. Mutating methods are `@Transactional`.

### Blocking services (default stack)

| Service | Package | Responsibilities |
|---|---|---|
| `ChannelService` | `runtime.channel` | create, findByName, setAllowedWriters, setAdminInstances, listAll, updateLastActivity |
| `MessageService` | `runtime.message` | send (increments `replyCount`, updates `channel.lastActivityAt`), pollAfter (excludes EVENT), findById, findByCorrelationId |
| `InstanceService` | `runtime.instance` | register (upsert + capability replacement), heartbeat, findByInstanceId, findByCapability, listAll, markStaleOlderThan |
| `DataService` | `runtime.data` | store (create or chunked append), getByKey, getByUuid, listAll, claim, release, isGcEligible |
| `LedgerWriteService` | `runtime.ledger` | Writes `AgentMessageLedgerEntry` on every structured EVENT; runs in `REQUIRES_NEW` transaction — failure is non-fatal and does not roll back `send_message` |

### Reactive services (`quarkus.qhorus.reactive.enabled=true`)

`@Alternative @ApplicationScoped` mirrors that return `Uni<T>` throughout and use `Panache.withTransaction()` for mutations.

| Service | Backed by | Notes |
|---|---|---|
| `ReactiveChannelService` | `ReactiveChannelStore` | Full CRUD + updateLastActivity |
| `ReactiveMessageService` | `ReactiveMessageStore` + `ReactiveChannelStore` | send, findById, pollAfter, pollAfterBySender; PendingReply methods not yet reactive |
| `ReactiveInstanceService` | `ReactiveInstanceStore` | register (atomically replaces capabilities), findByInstanceId, findByCapability, listAll |
| `ReactiveDataService` | `ReactiveDataStore` | store, getByKey, getByUuid, listAll, claim (idempotent via `hasClaim`), release, isGcEligible |
| `ReactiveWatchdogService` | `ReactiveWatchdogStore` | register, listAll, findById, delete — new service (no blocking counterpart) |
| `ReactiveLedgerWriteService` | `ReactiveAgentMessageLedgerEntryRepository` | recordEvent via `Panache.withTransaction()`; no `REQUIRES_NEW` equivalent in reactive Panache — error isolation is the caller's responsibility |

**Key invariants:**
- `MessageService.send()` always calls `ChannelService.updateLastActivity()` — channel `lastActivityAt` is always current.
- `MessageService.pollAfter()` filters out `EVENT` messages — agent context is never polluted with telemetry.
- `DataService.isGcEligible()` requires `complete = true AND claimCount = 0` — incomplete artefacts never GC-eligible.
- `InstanceService.register()` replaces capability tags on every upsert — no stale tags accumulate.
- `LedgerWriteService.recordEvent` runs in `REQUIRES_NEW` — a ledger write failure logs a warning and is swallowed; the message transaction is unaffected.
- Services inject `*Store` interfaces — Panache calls are isolated in `Jpa*Store` implementations; alternative backends activate via CDI `@Alternative @Priority(1)`.
- `AgentMessageLedgerEntryRepository` uses `EntityManager` directly (not Panache entity statics) because `LedgerEntry` is a plain `@Entity` in casehub-ledger. `ReactiveAgentMessageLedgerEntryRepository` (`@Alternative`) implements `ReactiveLedgerEntryRepository` via `AgentMessageReactivePanacheRepo`; inactive by default — consumers activate via `quarkus.arc.selected-alternatives` alongside a reactive datasource.

---

## Persistence Abstraction

Five domain store interfaces under `runtime/store/`, JPA implementations under
`runtime/store/jpa/`, in-memory implementations in `casehub-qhorus-testing`.

### Blocking stores (default)

| Interface | Query Object | Key extras |
|---|---|---|
| `ChannelStore` | `ChannelQuery(namePattern, semantic, paused)` | — |
| `MessageStore` | `MessageQuery(channelId, afterId, limit, excludeTypes, sender, target, contentPattern, inReplyTo)` | `countByChannel`, `deleteAll` |
| `InstanceStore` | `InstanceQuery(capability, status, staleOlderThan)` | `putCapabilities` (replace-all), `findCapabilities` |
| `DataStore` | `DataQuery(createdBy, complete)` | `putClaim`, `deleteClaim`, `countClaims`, `hasClaim` |
| `WatchdogStore` | `WatchdogQuery(conditionType)` | — |

### Reactive stores (`quarkus.qhorus.reactive.enabled=true`)

Mirror interfaces under `runtime/store/` with `Uni<T>` returns. `@Alternative` JPA implementations in `runtime/store/jpa/`. `@Alternative @Priority(1)` in-memory implementations in `casehub-qhorus-testing`.

| Interface | JPA impl | InMemory impl |
|---|---|---|
| `ReactiveChannelStore` | `ReactiveJpaChannelStore` | `InMemoryReactiveChannelStore` |
| `ReactiveMessageStore` | `ReactiveJpaMessageStore` | `InMemoryReactiveMessageStore` |
| `ReactiveInstanceStore` | `ReactiveJpaInstanceStore` | `InMemoryReactiveInstanceStore` |
| `ReactiveDataStore` | `ReactiveJpaDataStore` | `InMemoryReactiveDataStore` |
| `ReactiveWatchdogStore` | `ReactiveJpaWatchdogStore` | `InMemoryReactiveWatchdogStore` |

`InMemoryReactive*Store` delegates to the corresponding `InMemory*Store` via `Uni.createFrom().item(...)` — all state and logic stay in the blocking in-memory store; the reactive wrapper is pure delegation.

Consumers add `casehub-qhorus-testing` at test scope to activate in-memory
stores automatically — no database required for unit tests. See
[ADR-0002](../adr/0002-persistence-abstraction-store-pattern.md) and
[ADR-0003](../adr/0003-reactive-dual-stack.md).

---

## Configuration

`QhorusConfig` (`runtime.config`): `@ConfigMapping(prefix = "quarkus.qhorus")`

| Property | Default | Meaning |
|---|---|---|
| `quarkus.qhorus.cleanup.stale-instance-seconds` | 120 | Threshold for `markStaleOlderThan` |
| `quarkus.qhorus.cleanup.data-retention-days` | 7 | Days before old messages/data purge |

Consuming app owns all datasource config — none in the extension's `application.properties`.

---

## MCP Tool Surface

All tools exposed via `QhorusMcpTools` (`@ApplicationScoped`, active by default) at the `/mcp` Streamable HTTP endpoint. `QhorusMcpTools extends QhorusMcpToolsBase` — all 23 response records, 7 entity→DTO mappers, and 3 validation helpers live in the abstract base, shared with `ReactiveQhorusMcpTools`. Return types are public records in `QhorusMcpToolsBase`.

### Instance management
| Tool | Returns | Notes |
|---|---|---|
| `register` | `RegisterResponse` | Upserts instance + capability tags; returns active channels + online instances snapshot |
| `list_instances` | `List<InstanceInfo>` | Optional `capability` filter; capabilities batch-fetched in single `IN ?1` query |

### Channel management
| Tool | Returns | Notes |
|---|---|---|
| `create_channel` | `ChannelDetail` | Parses semantic; optional `allowed_writers`, `admin_instances`, `rate_limit_per_channel`, `rate_limit_per_instance` |
| `set_channel_writers` | `ChannelDetail` | Update write ACL; null = open to all |
| `set_channel_admins` | `ChannelDetail` | Update management ACL; null = open to any caller |
| `set_channel_rate_limits` | `ChannelDetail` | Update per-channel and per-instance rate limits (messages/min); null = unlimited |

### Observers
| Tool | Returns | Notes |
|---|---|---|
| `register_observer` | `ObserverRegistration` | In-memory subscription to EVENT messages on named channels; no `Instance` row created |
| `deregister_observer` | `DeregisterObserverResult` | Remove observer subscription |
| `read_observer_events` | `CheckResult` | Returns only EVENT messages from a subscribed channel; cursor-based pagination |
| `list_channels` | `List<ChannelDetail>` | Message counts fetched in single GROUP BY query (no N+1) |
| `find_channel` | `List<ChannelDetail>` | Case-insensitive LIKE on name OR description |

### Messaging
| Tool | Returns | Notes |
|---|---|---|
| `send_message` | `MessageResult` | Auto-generates `correlationId` for REQUEST type; enforces LAST_WRITE semantics |
| `check_messages` | `CheckResult` | `@Transactional`; dispatches by channel semantic (see below) |
| `get_replies` | `List<MessageSummary>` | Direct replies by `inReplyTo` |
| `search_messages` | `List<MessageSummary>` | Case-insensitive content LIKE; excludes EVENT |

### Shared data
| Tool | Returns | Notes |
|---|---|---|
| `share_data` | `ArtefactDetail` | Chunked upload via `append` + `last_chunk` |
| `get_shared_data` | `ArtefactDetail` | By key or UUID; throws on neither provided |
| `list_shared_data` | `List<ArtefactDetail>` | Includes incomplete artefacts |
| `claim_artefact` | `String` | Prevents GC while claimed |
| `release_artefact` | `String` | GC-eligible when all claims released |

### Channel semantic dispatch in `check_messages`

`checkMessages` is `@Transactional` and dispatches by `channel.semantic`:

| Semantic | Behaviour |
|---|---|
| `APPEND` / `LAST_WRITE` | Standard cursor-based polling (`afterId`, `limit`, optional `sender` filter in query) |
| `EPHEMERAL` | Fetch messages then delete them atomically — single-consumer delivery |
| `COLLECT` | Fetch ALL non-EVENT messages, clear channel atomically — ignores `afterId` cursor |
| `BARRIER` | Block until all `barrierContributors` have sent a non-EVENT message; then deliver all + clear; returns `barrierStatus` string while pending |

`CheckResult` carries an optional `barrierStatus` field (`null` for non-BARRIER channels).

### Key invariants
- LAST_WRITE same-sender write overwrites the existing message in place (same ID, no new row).
- LAST_WRITE different-sender write throws `IllegalStateException` naming the current writer.
- BARRIER with null/empty `barrierContributors` blocks permanently (configuration error guard).
- EVENT messages are excluded from all agent-visible delivery paths and from BARRIER contributor tracking.
- `send_message` enforces `allowed_writers` ACL when set — rejects senders not matching any entry (bare instance ID, `capability:tag`, or `role:name`). EVENT messages bypass this check.
- `pause_channel`, `resume_channel`, `force_release_channel`, `clear_channel` enforce `admin_instances` when set — reject callers not in the list. Null/empty list = open governance (any caller permitted).
- `send_message` enforces rate limits via an in-memory 60-second sliding window (`RateLimiter` bean). Per-channel limit counts across all senders; per-instance limit is isolated per sender. EVENT messages bypass. Limits reset on restart.
- `send_message` rejects registered observers — observer IDs are read-only and cannot write to any channel.
- `register_observer` registrations are ephemeral (`ObserverRegistry` bean); `list_instances` never returns observer IDs.

---

## Build Roadmap

| Phase | Status | What |
|---|---|---|
| **1 — Core data model + services** | ✅ Done | Entities, services, Flyway V1, 33 tests |
| **2 — MCP tools** | ✅ Done | 14 `@Tool` methods in `QhorusMcpTools`; 8 return-type records; 87 tests |
| **3 — Channel semantics** | ✅ Done | LAST_WRITE, EPHEMERAL, COLLECT, BARRIER enforced; 117 tests |
| **4 — Correlation + wait_for_reply** | ✅ Done | PendingReply, SSE keepalives; correlation isolation tests |
| **5 — Artefacts** | ✅ Done | Claim/release in MCP tools, artefact_refs in message flow; GC lifecycle tests |
| **6 — Addressing** | ✅ Done | target field (instance/capability/role); read-side filter; EVENT bypass; BARRIER+role semantics; 41 tests |
| **7 — Agent Card** | ✅ Done | `/.well-known/agent-card.json`; A2A-compatible; 22 tests |
| **8 — Embed in Claudony** | ⬜ Pending | Unified MCP endpoint |
| **9 — A2A compat** | ✅ Done | `POST /a2a/message:send`, `GET /a2a/tasks/{id}`; guarded by `quarkus.qhorus.a2a.enabled`; 29 tests |
| **10 — Human-in-the-loop controls** | ✅ Done | pause/resume, approval gate, cancel_wait, force_release, revoke_artefact, delete_message, clear_channel, deregister_instance, channel_digest, watchdog alerting (optional); 103 tests |
| **11 — Access control and governance** | ✅ Done | Write permissions (V5, `allowed_writers`, 23 tests); admin role (V6, `admin_instances`, 23 tests); rate limiting (V7, `RateLimiter`, 21 tests); observer mode (`ObserverRegistry`, `register_observer`, `read_observer_events`, 15 tests) — 82 tests total |
| **12 — Structured observability** | ✅ Done | `AgentMessageLedgerEntry` (casehub-ledger subclass, V1002); `LedgerWriteService` captures structured EVENTs; `list_events` MCP tool (channel/agent/time filters, cursor pagination); `get_channel_timeline` MCP tool; 36 tests (557 total) |
| **13 — Persistence abstraction** | ✅ Done | Store + scan(Query) pattern; JPA impls; testing/ module; 88 new tests (646 total) |
| **14 — Reactive dual-stack** | ✅ Done | Reactive*Store (5 domains) + ReactiveJpa*Store + InMemoryReactive*Store (#74, #75); QhorusMcpToolsBase extraction (#76); Reactive*Service + ReactiveLedgerWriteService (#77); ReactiveQhorusMcpTools — 39 tools returning Uni<T> (#78); build-time activation + ReactiveAgentCardResource + ReactiveA2AResource (#79); contract test bases + @Disabled reactive runners (#80) |
| **15 — Documentation** | ✅ Done | DESIGN.md, ADR-0003, CLAUDE.md (#81) |

---

## MCP Tool Design

### Return type strategy: String vs Structured

Qhorus's 39 `@Tool` methods return structured records (`ChannelDetail`, `MessageResult`,
`List<...>`, etc.). At Phase 8 these share a `/mcp` endpoint with Claudony's 8 tools,
which return `String`.

**This is intentional and correct.** The consumers are different:

| Tool set | Consumer | Why |
|---|---|---|
| Claudony (8 tools) | Claude AI — reads and reasons about text | "Created 'my-session' (id=abc)" is exactly right; no parsing needed |
| Qhorus (39 tools) | AI agents — process responses programmatically | `lastId` for pagination, `activeChannels` after register — must be reliable field access, not text parsing |

The decision not to unify toward a single return type is recorded in
[ADR-0001](../adr/0001-mcp-tool-return-type-strategy.md).

### Error handling

**String-returning tools** use Option A: catch exceptions, return `"Error: ..."` text.
Claude reads the error like any other tool output.

**Structured-returning tools** use `@WrapBusinessError({IllegalArgumentException.class, IllegalStateException.class})`
at the class level on `QhorusMcpTools`. The quarkus-mcp-server interceptor converts those
exceptions to `ToolCallException`, which the library serialises as `isError: true` tool
content — not a JSON-RPC protocol error. The interceptor only fires on `@Tool`-annotated
methods (checked via `context.getMethod().isAnnotationPresent(Tool.class)`), so non-tool
callers via CDI injection (e.g. `A2AResource`) receive `ToolCallException` and must handle
it alongside the original exception type.

---

## Testing Strategy

- `@QuarkusTest` + `@TestTransaction` per test method — each test rolls back, no data leakage
- H2 in-memory datasource for all tests; Flyway runs V1 migration at boot
- No mocks — all tests exercise real Panache against real H2
- Test classes mirror domain packages: `channel/`, `message/`, `instance/`, `data/`
- `SmokeTest` exercises the full cross-domain workflow in one boot
- Semantic test classes (`LastWriteSemanticTest`, `EphemeralSemanticTest`, `CollectSemanticTest`, `BarrierSemanticTest`) verify each enforcement contract in isolation
- MCP tool test classes mirror tool groups: `InstanceToolTest`, `ChannelToolTest`, `MessagingToolTest`, `SharedDataToolTest`
- Current test count: ~800 (708 runtime + 92 testing)
- `quarkus.datasource.reactive=false` set in test `application.properties` — prevents Hibernate Reactive from booting in H2 test contexts (no reactive H2 driver exists)

### Reactive testing

- **Store unit tests**: 10 `InMemory*StoreTest` + `InMemoryReactive*StoreTest` classes each extend an abstract `*StoreContractTest` base (in `testing/src/test/.../contract/`). The reactive runner unwraps `Uni` via `.await().indefinitely()` in factory methods — assertion code is identical across both stacks.
- **Service contract tests**: 5 abstract `*ServiceContractTest` bases in `runtime/src/test/.../service/`. Blocking runners (`@QuarkusTest @TestTransaction`) run actively. Reactive runners are `@Disabled` — reactive services call `Panache.withTransaction()` which requires a native reactive datasource driver; H2 has none.
- **ReactiveTestProfile**: activates reactive service `@Alternative` beans via `quarkus.arc.selected-alternatives`. Does NOT activate `@IfBuildProperty` beans (`ReactiveQhorusMcpTools`, `ReactiveAgentCardResource`, `ReactiveA2AResource`) — those require the property at build time.
- **ReactiveSmokeTest**: `@Disabled` skeleton with 8-step workflow comments; mirrors `SmokeTest.fullMeshWorkflow()`; enable when Docker/PostgreSQL Dev Services is available.
