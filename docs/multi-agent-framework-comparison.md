# Multi-Agent Framework Comparison

Comparison of Qhorus against other notable agent communication frameworks,
protocols, and platforms. Rows are features and characteristics; columns are
projects. Shared capabilities are visible at a glance; differentiators stand out.

> **Legend:** ✅ supported · ❌ not supported · 🔶 partial or planned

---

## Part 1 — Coordination and Communication

| Feature | cross-claude-mcp | **Qhorus** | A2A (Google) | ACP (IBM/BeeAI) | AutoGen (Microsoft) | Swarm (OpenAI) | LangGraph | Letta/MemGPT | CrewAI | MCP (Anthropic) |
|---|---|---|---|---|---|---|---|---|---|---|
| **Coordination model** | Channel pub/sub | Channel mesh (N:N) | Task delegation | Run request | Group conversation | Handoff chain | State graph | Single stateful agent | Role-based crew | Tool invocation |
| **Topology** | N:N channels | N:N channels | 1:1 orchestrator→specialist | 1:1 caller→agent | 1:N group chat | Sequential 1:1 handoffs | DAG / graph nodes | 1:1 user→agent | Crew (sequential or hierarchical) | 1:1 LLM→tool server |
| **Agent addressing** | By instance_id only | Name · `capability:tag` · `role:name` | Endpoint URL | Endpoint URL | Agent name (in-process) | Function/agent object | Node name (in-process) | Agent ID (REST) | Role name (in-process) | Server endpoint URL |
| **Channel / topic semantics** | Single type (append) | **5 declared, enforced server-side** (APPEND, COLLECT, BARRIER, EPHEMERAL, LAST_WRITE) | None | None | None | None | State reducers (in-process) | None | None | None |
| **Typed message taxonomy** | 6 types, all agent-visible | **7 types** — adds `event` (observer-only, excluded from agent context) | Opaque task content | Opaque run content | Role-based (user / assistant / function) | Role-based | User-defined node outputs | human / tool / system / reasoning | Task outputs (untyped) | Tool results / resource content |
| **wait_for_reply / correlation** | Polls any non-self message — unsafe under concurrent requests | **UUID correlation IDs** — safe under N concurrent waits; PendingReply table | Poll task status | SSE stream or poll | Blocking turn-based | Sequential only | `interrupt()` / resume | Request / response | Sequential task execution | Synchronous tool calls |
| **State persistence** | SQLite / PostgreSQL (disposable) | **PostgreSQL — durable, versioned schema (Flyway)** | Task state (ephemeral server-side) | Run state (server-side) | In-memory conversation history | In-memory only | In-memory or custom checkpointer | PostgreSQL / SQLite (durable) | In-memory | Stateless tools |
| **Shared data / artefacts** | Blob inline in message content | **UUID artefact refs + claim/release GC + chunked streaming** | Task artifacts (append / last_chunk) | Run outputs | Passed via conversation context | Function args / return values | Graph state | Memory blocks (archival, recall, in-context) | Task output chaining | Resource content / tool results |
| **Multi-agent coordination patterns** | Append-only channels | COLLECT fan-in · BARRIER join gates · EPHEMERAL routing · LAST_WRITE authoritative state | Sequential delegation only | Point-to-point only | Round-robin / selector / custom | Handoff (one agent at a time) | Conditional branching, parallel nodes | Single agent focus | Sequential / hierarchical crew | None (single LLM) |

---

## Part 2 — Human Interaction and Discovery

| Feature | cross-claude-mcp | **Qhorus** | A2A (Google) | ACP (IBM/BeeAI) | AutoGen (Microsoft) | Swarm (OpenAI) | LangGraph | Letta/MemGPT | CrewAI | MCP (Anthropic) |
|---|---|---|---|---|---|---|---|---|---|---|
| **Human in the loop** | Human can post (no enforcement) | **First-class sender; BARRIER gates require human contribution; `event` type for real-time observation** | ❌ No concept | ✅ Designed for human→agent calls | ✅ `HumanProxyAgent`; confirmation requests | 🔶 Return to human | ✅ `interrupt()` / resume for HITL | ✅ Core use case (user ↔ agent) | 🔶 Human feedback between tasks | Via LLM confirmation |
| **Agent discovery** | Manual / hardcoded | **Instance registry + capability tags + Agent Card** (Phase 7) | **Agent Card** at `/.well-known/agent-card.json` | Registry-based | ❌ Hardcoded in code | ❌ Hardcoded | ❌ None | Agent directory (Letta Cloud) | ❌ None | ❌ Configured endpoints |
| **Observability** | ❌ None | **`event` message type** — observer-only, never pollutes agent context | Task status + artifacts | Run events / streaming | Logging hooks | ❌ None | **LangSmith** integration | Built-in tracking | Verbose mode only | ❌ None |
| **HandoffMessage safety** | ❌ No enforcement — agent can keep producing after handoff | ✅ Terminal — in-flight results discarded once handoff produced | N/A | N/A | 🔶 Agent return signals intent | Sequential only | Via graph transitions | N/A | Sequential only | N/A |
| **Termination conditions** | `done` message only | Composable: done · max-messages · keyword · timeout · functional predicate | Task completion | Run completion | Max turns · human confirm · custom | Return value | Graph `END` node | Session end | Task completion | Tool result |

---

## Part 3 — Platform and Deployment

| Feature | cross-claude-mcp | **Qhorus** | A2A (Google) | ACP (IBM/BeeAI) | AutoGen (Microsoft) | Swarm (OpenAI) | LangGraph | Letta/MemGPT | CrewAI | MCP (Anthropic) |
|---|---|---|---|---|---|---|---|---|---|---|
| **Transport** | stdio · SSE · MCP Streamable HTTP | **MCP Streamable HTTP** (spec 2025-06-18) | REST (HTTP) | REST + SSE | In-process / gRPC (AutoGen 0.4+) | In-process (Python) | In-process / REST (LangServe) | REST API | In-process (Python) | stdio · Streamable HTTP · SSE |
| **Language / framework** | Node.js | **Java / Quarkus** — any MCP client connects | Protocol-level (language-agnostic) | Protocol-level (language-agnostic) | Python | Python | Python (JS beta) | Python | Python | Protocol-level |
| **LLM binding** | Primarily Claude | **LLM-agnostic** — any MCP client | LLM-agnostic | LLM-agnostic | Multi-LLM (OpenAI, Azure, Gemini…) | OpenAI API | Multi-LLM | Multi-LLM | Multi-LLM | LLM-agnostic |
| **Long-term memory** | ❌ None | ❌ None (coordination layer only) | ❌ None | ❌ None | 🔶 In-session history only | ❌ None | 🔶 Via checkpointer (pluggable) | ✅ **Core feature** — archival, recall, in-context memory blocks | 🔶 Via tools | 🔶 Via resource providers |
| **Code execution** | ❌ None | ❌ None | ❌ None | ❌ None | ✅ **Built-in** (Docker sandbox) | ❌ None | 🔶 Via tools | 🔶 Via tools | 🔶 Via tools | 🔶 Via tool calls |
| **Embeddable as library** | ❌ Standalone server only | ✅ **Quarkus extension** — Maven dependency | ❌ Protocol only | ❌ Protocol only | ✅ Python package | ✅ Python package | ✅ Python package | ✅ Python package | ✅ Python package | ✅ SDK |
| **Native image / runtime size** | Node.js ~80 MB | **GraalVM native target ~30 MB, milliseconds to ready** | N/A (HTTP calls) | N/A (HTTP calls) | Python runtime | Python runtime | Python runtime | Python runtime | Python runtime | Depends on host |
| **A2A compatible** | ❌ | ✅ Phase 9 (optional endpoint) | ✅ **Native** | 🔶 | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| **ACP compatible** | ❌ | 🔶 Phase 9b (same pattern as A2A) | 🔶 | ✅ **Native** | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| **Production maturity** | Prototype / demo | Production-grade Quarkiverse extension | ✅ v1.0 (Google, April 2025) | Active development | ✅ Production (Microsoft) | ⚠️ Experimental / educational | ✅ Production (LangChain) | ✅ Production (commercial) | ✅ Production (commercial) | ✅ Production (Anthropic, 2025) |
| **Primary use case** | Claude agent coordination (prototype) | **Multi-agent mesh coordination in production Quarkus systems** | External orchestrator→specialist delegation | Human/system→agent invocation | Research and enterprise multi-agent | Teaching agent handoff patterns | Stateful workflow graphs | Long-term memory agents | Role-based agent teams | LLM tool access |

---

## What Qhorus Uniquely Provides

Across all frameworks surveyed, Qhorus is the only system that combines:

1. **N:N channel mesh** — agents coordinate without knowing each other's addresses
2. **Declared, enforced channel semantics** — COLLECT, BARRIER, EPHEMERAL, LAST_WRITE (borrowed from LangGraph's Pregel model but enforced server-side, not in-process)
3. **UUID correlation IDs for wait_for_reply** — safe under concurrent requests, not positional
4. **Artefact refs with claim/release lifecycle** — not inline content duplication
5. **`event` message type** — clean observability boundary, never pollutes agent context
6. **Durable state with versioned schema** — survives restarts, upgrades, and rolling deploys
7. **Java/Quarkus native** — JVM ecosystem, GraalVM native target, embeddable as a Maven dependency
8. **Human as first-class participant** — not a proxy or callback, a peer

---

## Where Other Frameworks Excel

| Framework | Standout capability |
|---|---|
| **Letta/MemGPT** | Long-term memory — archival, recall, and in-context memory blocks for persistent agent identity |
| **AutoGen** | Code execution — built-in Docker sandbox; mature group chat orchestration |
| **LangGraph** | Graph-based workflow — conditional branching, parallel execution, HITL via `interrupt()` |
| **CrewAI** | Role-based simplicity — easiest entry point for "a team of agents with roles" patterns |
| **Swarm** | Pedagogical clarity — best for understanding agent handoff fundamentals |
| **A2A** | Ecosystem standard — the broadest external orchestrator compatibility |
| **MCP** | Tool access standard — universal LLM-to-tool connection layer |

---

*Research sources: Google A2A v1.0, Microsoft AutoGen, OpenAI Swarm, LangGraph (Pregel model),
Letta (MemGPT), CrewAI, MCP spec 2025-06-18, IBM/BeeAI ACP.*
