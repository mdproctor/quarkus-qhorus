# Qhorus — Session Handover
**Date:** 2026-04-13

## What Was Done

Project scaffolded from scratch in this session:
- `pom.xml` — Quarkus 3.32.2, `quarkus-mcp-server-http` 1.11.1, Panache ORM, H2 + PostgreSQL
- `CLAUDE.md` — full project guide with ecosystem context
- `docs/specs/2026-04-13-qhorus-design.md` — complete design spec (Mermaid diagrams, data model, MCP tool surface, build roadmap)
- GitHub repo created and pushed: https://github.com/mdproctor/qhorus
- No Java code written yet — all scaffold and spec

## What Qhorus Is

Quarkus Native port of `~/claude/cross-claude-mcp` (Node.js), redesigned after deep research into A2A, AutoGen, LangGraph, Swarm, Letta, CrewAI. It is the peer-to-peer communication layer in a three-project Quarkus Native AI Agent Ecosystem:

```
casehub (orchestration)   qhorus (mesh)
         ↑                      ↑
         └──── claudony (integration layer) ────┘
```

Qhorus has **no dependency on CaseHub or Claudony** — standalone first.

## Key Design Decisions

- **Transport:** Streamable HTTP only (MCP spec 2025-06-18). Legacy SSE deprecated.
- **Extension:** `quarkus-mcp-server-http` v1.11.1 — native-tested. Do NOT use the Java MCP SDK (no native support).
- **Channel semantics:** 5 types — APPEND (default), COLLECT, BARRIER, EPHEMERAL, LAST_WRITE. Declared at channel creation.
- **Message types:** 7 — `request · response · status · handoff · done · event`. `event` is observer-only (not in agent context).
- **`wait_for_reply`:** correlation ID (UUID, not positional). Registers `PendingReply` row. SSE keepalives every 30s.
- **Artefacts:** `artefact_refs: List<UUID>` on messages, not inline payloads. `claim/release` lifecycle for GC.
- **Addressing:** by `instance_id` · by `capability:tag` · by `role:name` (Letta tag model).
- **HandoffMessage is terminal for a turn** — in-flight results discarded on handoff (prevents Swarm/AutoGen race).
- **A2A:** complementary, not competing. Serve `/.well-known/agent-card.json`. Optional A2A endpoint later.

> **Native gotcha:** `quarkus-mcp-server` 1.11.1 fixed sampling + elicitation in native image — they were silently broken before. Always use ≥ 1.11.1 for native builds.

## Immediate Next Step — Phase 1: Data Model + Services

Build in this order (spec § Build Roadmap):

1. **Entities** (`src/main/java/dev/qhorus/`):
   - `channel/Channel.java` — PanacheEntity, fields: id, name, description, semantic (enum), barrierContributors, createdAt, lastActivityAt
   - `message/Message.java` — PanacheEntity, fields: id, channelId, sender, messageType (enum), content, correlationId, inReplyTo, replyCount, createdAt
   - `instance/Instance.java` + `Capability.java`
   - `data/SharedData.java` + `ArtefactClaim.java`
   - `message/PendingReply.java` — correlationId, instanceId, channelId, expiresAt

2. **Services** — ChannelService, MessageService, InstanceService, DataService. Panache queries only, no raw SQL.

3. **Config** — `config/QhorusConfig.java` (`@ConfigMapping(prefix = "qhorus")`)

4. **Smoke test** — `SmokeTest.java` hitting `/q/health`

Full data model ER diagram: `docs/specs/2026-04-13-qhorus-design.md` § Data Model

## References

| What | Path |
|---|---|
| Design spec | `docs/specs/2026-04-13-qhorus-design.md` |
| Ecosystem design | `~/claude/cross-claude-mcp/docs/superpowers/specs/2026-04-13-quarkus-ai-ecosystem-design.md` |
| Source project | `~/claude/cross-claude-mcp` (Node.js — read tools.mjs for current MCP tool semantics) |
| Claudony (for embedding ref) | `~/claude/claudony` |
| CaseHub (for SPI context) | `~/claude/casehub` |
