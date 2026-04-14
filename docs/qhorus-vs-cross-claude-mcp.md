# Qhorus vs cross-claude-mcp

> This document compares Qhorus (Quarkus Native) with its predecessor
> [cross-claude-mcp](https://github.com/rblank9/cross-claude-mcp) (Node.js),
> explaining what each is optimised for and why the differences exist.

---

## Feature comparison

| Feature | cross-claude-mcp (Node.js) | Qhorus (Quarkus Native) |
|---|---|---|
| Channel semantics | Single type — append only | 5 semantics: APPEND, COLLECT, BARRIER, EPHEMERAL, LAST_WRITE |
| Message types | 6 (no observer-only type) | 7 — adds `event` (observer-only telemetry, excluded from agent context) |
| `wait_for_reply` | Polls any new message from a non-self sender | UUID correlation IDs, keyed to PendingReply rows — concurrent waits are safe |
| Shared data | Blob stored by key, inline in message content | UUID artefact refs + claim/release lifecycle + chunked streaming |
| Instance addressing | By `instance_id` only | By id · by `capability:tag` · by `role:name` (tag-based broadcast) |
| HandoffMessage safety | No enforcement | Terminal for turn; in-flight results discarded on handoff |
| Termination conditions | `done` message only | Composable: done · max-messages · keyword · timeout · functional predicate |
| Agent discovery | None | `/.well-known/agent-card.json` (A2A compatible) |
| Transport | stdio + legacy SSE + Streamable HTTP | Streamable HTTP (MCP spec 2025-06-18) only |
| Runtime size | Node.js ~80 MB | GraalVM native image ~30 MB |
| Database | SQLite / PostgreSQL (raw SQL, schema is disposable) | Panache ORM + Flyway versioned migrations — schema survives upgrades |
| Embeddable | No | Yes — consumed as a Maven dependency by Claudony |

---

## Why each difference exists

### Channel semantics — 5 types instead of append-only

cross-claude-mcp has one implicit semantic: ordered append. This works for simple conversation threads but breaks for three common multi-agent patterns:

- **Fan-in** — multiple agents contribute, one reads the aggregate → needs COLLECT
- **Join gates** — proceed only when all named contributors have written → needs BARRIER
- **Authoritative state** — one writer; concurrent writes are a protocol error → needs LAST_WRITE

Without declared semantics, agents implement these patterns in their prompts, with no enforcement. Race conditions only appear as wrong output, not as errors. Declaring semantics at channel creation makes the contract explicit and enforceable server-side — borrowed from LangGraph's Pregel model.

### `wait_for_reply` — correlation IDs instead of sender filtering

The original polls for any new message from a non-self sender. Under concurrent requests — Alice waiting for Bob's reply while also waiting for Carol's reply — message N+1 might be Carol's reply to a different request. Alice receives it and misattributes it.

Qhorus uses UUID correlation IDs keyed to PendingReply rows. Each `request` carries a `correlation_id`; `wait_for_reply` registers a row and wakes only when a `response` carries the matching ID. Multiple concurrent waits are safe by construction, not by convention.

### Artefacts — UUID refs instead of inline payloads

cross-claude-mcp puts data inline in message `content`. Two failure modes:

1. Large content (analysis, plans, code) bloats the message table and hits MCP message size limits.
2. If multiple agents receive the same large payload, it is duplicated in memory for each.

Qhorus stores artefacts once, passes UUID references on messages, and uses claim/release reference counting so GC knows when it is safe to clean up. Chunked streaming (`append + last_chunk`) handles outputs that exceed a single tool call — borrowed from A2A's `TaskArtifactUpdateEvent` pattern.

### Addressing — capability and role modes

The original routes to `instance_id` only — the sender must know exactly who to reach. This breaks for dynamic agent pools where the set of available agents changes, and for broadcast patterns where all agents with a given skill should respond.

Qhorus adds two addressing modes borrowed from Letta's tag-based dispatch:
- `capability:code-review` — any available instance with that tag (load-balance or first-responder)
- `role:reviewer` — all instances in that role (broadcast + collect)

This decouples agent identity from agent capability and enables dynamic pool management without re-wiring sender logic.

### `event` message type — clean observability boundary

The original's six message types all appear in agent context. Routing decisions, queue depths, and system signals are mixed in with work messages, forcing agents to filter noise and risking prompt pollution.

`event` is excluded from agent context entirely — it exists for dashboards, telemetry pipelines, and orchestration layers. Separation keeps agent prompts clean and makes system observability a first-class concern without coupling it to agent behaviour.

### HandoffMessage is terminal

The original has no enforcement of handoff semantics. An agent can produce a `handoff` and then continue producing tool results in the same turn. If those results arrive after the handoff recipient has started work, the last writer wins silently — the same race condition that causes subtle bugs in AutoGen and Swarm pipelines.

Qhorus enforces that `handoff` is terminal: any in-flight results for that turn are logged and discarded once a `handoff` is produced.

### Versioned schema migrations instead of a disposable database

cross-claude-mcp treats its database as ephemeral — if the schema changes, restart with a fresh SQLite file. This works because the state it holds is transient: agents connect, coordinate, and disconnect. Nothing in the database needs to survive a server restart or a schema change.

Qhorus uses Flyway versioned migrations because its state carries meaning across restarts:

- Channel history may matter after a service restart
- `PendingReply` rows must survive a restart or `wait_for_reply` callers lose their correlation ID tracking
- `ArtefactClaim` reference counts must survive — if they're lost, artefacts get GC'd while agents are still consuming them
- Multiple instances may share one database, and they must agree on schema version

The migration concern is the price of the persistence guarantee. cross-claude-mcp implicitly says "state is ephemeral, restart freely." Qhorus says "state is durable, upgrade without data loss."

### Agent Card

cross-claude-mcp has no discovery mechanism. Any external orchestrator must be manually configured to know the server exists.

Qhorus publishes `/.well-known/agent-card.json` in the A2A format, making every deployment self-describing and discoverable by Google A2A orchestrators, Claudony, and any other ecosystem tool that reads agent cards.

---

## When to use each

**cross-claude-mcp** is the right tool when:
- You need something running in 30 seconds with zero infrastructure
- You have a single Claude instance coordinating with itself
- Concurrent requests in flight simultaneously is not a concern
- You want ~500 lines of code you can read and modify in an afternoon

**Qhorus** is the right tool when:
- You have N agents from multiple frameworks (not just Claude)
- Concurrent requests are in flight simultaneously — the correlation ID safety matters
- You want COLLECT fan-in or BARRIER join gates with server-side enforcement
- You want humans to interject into agent channels in real-time (`event` type)
- You are embedding in a larger system (Claudony) or distributing as a library
- You want A2A discoverability and ecosystem compatibility

---

## The short version

cross-claude-mcp solved the prototype problem. Qhorus solves the production problem.
