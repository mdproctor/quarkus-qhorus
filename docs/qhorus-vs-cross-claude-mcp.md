# Qhorus vs cross-claude-mcp

> cross-claude-mcp is the Node.js MCP server that Qhorus grew out of. This document
> explains what Qhorus adds, why each design decision diverges from the original,
> and which tool fits which situation.

---

## Feature comparison

| Feature | cross-claude-mcp (Node.js) | Qhorus (Quarkus) |
|---|---|---|
| Channel semantics | Append only | 5 declared semantics: APPEND, COLLECT, BARRIER, EPHEMERAL, LAST_WRITE — enforced server-side |
| Message types | 6 types, all visible to agents | 7 types — adds `event` (observer-only, excluded from agent context) |
| `wait_for_reply` | Polls any new message from non-self sender | UUID correlation IDs — multiple concurrent waits are safe by construction |
| Shared data | Blob inline in message content | UUID artefact refs + claim/release lifecycle + chunked streaming |
| Instance addressing | By `instance_id` only | By id · by `capability:tag` · by `role:name` |
| HandoffMessage | No enforcement — agent can keep producing after handoff | Terminal — in-flight results discarded once handoff is produced |
| Termination conditions | `done` message only | Composable: done · max-messages · keyword · timeout · functional predicate |
| Agent discovery | None | `/.well-known/agent-card.json` (A2A compatible) |
| LLM compatibility | Primarily Claude | Any MCP-capable agent — Claude, Cursor, OpenAI, custom frameworks |
| Transport | stdio + legacy SSE + Streamable HTTP | Streamable HTTP (MCP spec 2025-06-18) |
| Runtime | Node.js ~80 MB, seconds to ready | GraalVM native ~30 MB, milliseconds to ready — no warm-up ¹ |
| Database | SQLite / PostgreSQL, raw SQL, schema is disposable | Panache ORM + Flyway — versioned migrations, schema survives upgrades |
| Embeddable | No | Yes — Maven dependency, consumed by Claudony |

> ¹ GraalVM native is the target architecture. The build profile and GraalVM 25 are configured. Native
> validation is planned once the full tool surface is stable (after Phases 5–7).

---

## Why each design decision diverges

### Channel semantics

cross-claude-mcp has one implicit behaviour: ordered append. That handles conversation threads but breaks three patterns that come up constantly in real multi-agent systems:

- **Fan-in** — multiple agents contribute, one reads the aggregate → needs COLLECT
- **Join gates** — proceed only when all named contributors have written → needs BARRIER
- **Authoritative state** — one writer; a second concurrent writer is a bug → needs LAST_WRITE

Without declared semantics, agents implement these patterns in prompts with no enforcement. Race conditions surface as wrong output, not as errors — the hardest kind to debug. Qhorus declares the contract at channel creation and enforces it server-side, borrowing the model from LangGraph's Pregel state reducers.

### `wait_for_reply`

The original polls for any new message from a non-self sender. Under concurrent requests — Alice waiting for Bob's reply while simultaneously waiting for Carol's — message N+1 might be Carol's answer to a different question. Alice gets it and misattributes it.

Qhorus uses UUID correlation IDs. Each `request` carries one; `wait_for_reply` registers a PendingReply row and wakes only on a matching `response`. Multiple concurrent waits are safe by construction, not by agent discipline.

### Shared data and artefacts

Inline message content fails two ways at scale: large payloads hit MCP message size limits, and the same large payload gets duplicated in memory for every receiver.

Qhorus stores artefacts once and passes UUID references. Claim/release reference counting lets GC know when it is safe to delete — artefacts are not on a timer, they are cleaned up when all claiming agents have explicitly released them. Chunked streaming (`append + last_chunk`, borrowed from A2A's `TaskArtifactUpdateEvent`) handles outputs that exceed a single tool call.

### Instance addressing

The original routes by `instance_id` only — the sender must know exactly who to reach. This breaks when agent pools are dynamic or when you want any available specialist rather than a specific one.

Qhorus adds two modes from Letta's tag-based dispatch model:
- `capability:code-review` — any online instance with that tag
- `role:reviewer` — all instances with that role (broadcast + fan-in)

Agent identity and agent capability are decoupled. Pool membership can change without the sender caring.

### The `event` message type

All six of cross-claude-mcp's message types appear in agent context. System signals, routing decisions, and queue metrics land in the same stream as work messages. Agents filter noise; prompt pollution is a real risk.

`event` is never delivered to agents. It exists solely for dashboards, telemetry pipelines, and orchestration layers — Claudony, observability tools, the humans watching the system. The separation is enforced at the query level: every agent-facing query explicitly excludes EVENT.

### HandoffMessage safety

The original applies no enforcement after a `handoff` is sent. An agent can produce a handoff and continue generating tool results in the same turn. If those results arrive after the handoff recipient has started work, the last writer wins silently — the exact race that causes subtle wrong-state bugs in AutoGen and Swarm.

Qhorus treats `handoff` as terminal for a turn. Anything produced after it is logged and discarded.

### Versioned schema vs disposable database

cross-claude-mcp uses its database as scratch space: if the schema changes, restart with a fresh file. That is reasonable because its state is transient — agents connect, coordinate, and disconnect. Nothing in the database needs to outlive a restart.

Qhorus persists state that matters across restarts:
- Channel history may be important after a service restart
- `PendingReply` rows track in-flight `wait_for_reply` calls — lose them and callers are stuck
- `ArtefactClaim` reference counts gate GC — lose them and artefacts are deleted while agents are still using them
- Multiple instances sharing one database must agree on schema version

Flyway versioned migrations are the price of that durability guarantee. cross-claude-mcp says "state is ephemeral, restart freely." Qhorus says "state is durable, upgrade without data loss."

### GraalVM native image

The size difference (80 MB → 30 MB) understates the change. The more significant difference is execution model. GraalVM native image eliminates the JVM at runtime entirely: no bytecode interpretation, no JIT compilation, no class loading. Quarkus performs all of that work at build time — CDI wiring, classpath scanning, proxy generation, config binding. The resulting binary starts in milliseconds and runs at full throughput from the first request.

For an embedded coordination service, warm-up is not an academic concern. A JVM process taking 3–5 seconds to reach steady state is acceptable for a long-running web server; it is noticeable when Claudony is launching Qhorus as a dependency alongside other services.

Quarkus handles the Qhorus-specific native requirements: `quarkus-flyway-deployment` registers migration scripts for inclusion in the binary, `quarkus-scheduler` has native support, and Panache entities are covered by `quarkus-hibernate-orm-panache-deployment`.

### LLM-agnostic by design

cross-claude-mcp was built for Claude. Qhorus is built on MCP, which is an open standard with growing multi-vendor support. Any agent with an MCP client can connect — Claude, Cursor, OpenAI, a custom framework with an MCP adapter. A Claude agent and a GPT-4o agent can work on the same Qhorus channel without any changes to Qhorus.

The `claudony_session_id` field on Instance is always optional. Qhorus works standalone without Claudony, and without Claude.

### Agent Card

cross-claude-mcp has no discovery mechanism. External orchestrators need manual configuration to find it.

Qhorus publishes `/.well-known/agent-card.json` in A2A format, making every deployment self-describing. Google A2A orchestrators, Claudony, and any ecosystem tool that reads agent cards can discover and describe a Qhorus instance without prior configuration.

---

## When to use each

**cross-claude-mcp** is the right tool when:
- You need something running in minutes with no infrastructure
- You are coordinating a single Claude instance with itself
- Concurrent in-flight requests are not a concern
- You want ~500 lines of readable code you can fork and modify in an afternoon

**Qhorus** is the right tool when:
- Agents from multiple frameworks or LLMs need to coordinate
- Multiple requests can be in flight simultaneously and correct response attribution matters
- You want fan-in (COLLECT) or join gates (BARRIER) enforced by the server, not by prompt conventions
- Humans need to interject into agent conversations in real-time
- You are embedding a coordination layer into a larger system
- State must survive restarts and schema upgrades
- You want A2A discoverability out of the box

The one overhead worth naming: Qhorus is a Quarkiverse extension, so the initial scaffold follows Quarkiverse conventions — a three-module Maven structure, deployment module, annotation processor configuration. This is a one-time setup cost, not an ongoing development tax, and it is largely automated by the Quarkiverse project generator at code.quarkus.io.

---

## The short version

cross-claude-mcp solved the prototype problem. Qhorus solves the production problem.
