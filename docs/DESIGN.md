# Quarkus Qhorus — Design Document

## Overview

Qhorus is a Quarkus extension providing a peer-to-peer agent communication
mesh for multi-agent AI systems. Any Quarkus app adds `quarkus-qhorus` as a
dependency and its agents get typed channels, typed messages, a shared data
store with artefact lifecycle management, an instance registry with capability
tags, and `wait_for_reply` long-polling with correlation IDs.

Primary design specification: `docs/specs/2026-04-13-qhorus-design.md`

How Qhorus relates to A2A and ACP: `docs/agent-protocol-comparison.md`

Full multi-agent framework comparison (Qhorus, A2A, ACP, AutoGen, LangGraph, CrewAI, Letta, Swarm, MCP): `docs/multi-agent-framework-comparison.md`

---

## Component Structure

Maven multi-module layout following Quarkiverse conventions:

| Module | Artifact | Purpose |
|---|---|---|
| Parent | `quarkus-qhorus-parent` | BOM, version management |
| Runtime | `quarkus-qhorus` | Extension runtime — entities, services, MCP tools, REST |
| Deployment | `quarkus-qhorus-deployment` | Build-time processor — feature registration, native config |

---

## Technology Stack

| Concern | Choice | Notes |
|---|---|---|
| Runtime | Java 21 (on Java 26 JVM) | `maven.compiler.release=21` |
| Framework | Quarkus 3.32.2 | Inherits `quarkiverse-parent:21` |
| Persistence | Hibernate ORM + Panache (active record) | Panache `PanacheEntityBase`, UUID PKs |
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

| Service | Package | Responsibilities |
|---|---|---|
| `ChannelService` | `runtime.channel` | create, findByName, setAllowedWriters, setAdminInstances, listAll, updateLastActivity |
| `MessageService` | `runtime.message` | send (increments `replyCount`, updates `channel.lastActivityAt`), pollAfter (excludes EVENT), findById, findByCorrelationId |
| `InstanceService` | `runtime.instance` | register (upsert + capability replacement), heartbeat, findByInstanceId, findByCapability, listAll, markStaleOlderThan |
| `DataService` | `runtime.data` | store (create or chunked append), getByKey, getByUuid, listAll, claim, release, isGcEligible |

**Key invariants:**
- `MessageService.send()` always calls `ChannelService.updateLastActivity()` — channel `lastActivityAt` is always current.
- `MessageService.pollAfter()` filters out `EVENT` messages — agent context is never polluted with telemetry.
- `DataService.isGcEligible()` requires `complete = true AND claimCount = 0` — incomplete artefacts never GC-eligible.
- `InstanceService.register()` replaces capability tags on every upsert — no stale tags accumulate.

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

All tools exposed via `QhorusMcpTools` (`@ApplicationScoped`) at the `/mcp` Streamable HTTP endpoint. Return types are public static records nested in the class.

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
| **12 — Structured observability** | ⬜ Pending | Mandatory `event` payload schema; `list_events` query tool; channel timeline API; audit trail queryable by time range and agent |

---

## Testing Strategy

- `@QuarkusTest` + `@TestTransaction` per test method — each test rolls back, no data leakage
- H2 in-memory datasource for all tests; Flyway runs V1 migration at boot
- No mocks — all tests exercise real Panache against real H2
- Test classes mirror domain packages: `channel/`, `message/`, `instance/`, `data/`
- `SmokeTest` exercises the full cross-domain workflow in one boot
- Semantic test classes (`LastWriteSemanticTest`, `EphemeralSemanticTest`, `CollectSemanticTest`, `BarrierSemanticTest`) verify each enforcement contract in isolation
- MCP tool test classes mirror tool groups: `InstanceToolTest`, `ChannelToolTest`, `MessagingToolTest`, `SharedDataToolTest`
- Current test count: 117
