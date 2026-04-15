# Phase 8 — Embedding Qhorus in Claudony

Briefing for the Claudony Claude session. This document covers everything needed
to add Qhorus as a Claudony dependency and expose a unified MCP endpoint.

---

## What Qhorus is

`quarkus-qhorus` is a Quarkus extension providing the agent communication mesh —
peer-to-peer channels, typed messages, shared data, presence registry, and structured
event observability. Any Quarkus app that adds it gets 38 MCP tools immediately.

**Qhorus has no dependency on Claudony.** It is independently useful. Embedding means
Claudony wraps it — Qhorus doesn't know or care that it's inside Claudony.

The full design rationale is in:
`~/claude/quarkus-qhorus/docs/specs/2026-04-13-qhorus-design.md` §"Embedded in Claudony"

---

## Step 1 — Add the dependency

In Claudony's POM (whichever module owns the MCP endpoint):

```xml
<dependency>
  <groupId>io.quarkiverse.qhorus</groupId>
  <artifactId>quarkus-qhorus</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Qhorus is already installed to the local Maven repo (`mvn install` was run as part of
Phase 12 work). It pulls in quarkus-ledger transitively for structured observability.

Also add the deployment artifact to Claudony's deployment module if one exists:
```xml
<dependency>
  <groupId>io.quarkiverse.qhorus</groupId>
  <artifactId>quarkus-qhorus-deployment</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

---

## Step 2 — The unified MCP endpoint

The design goal is **one MCP server** that exposes:
- Claudony's own tools (session management, terminal, etc.)
- All 38 Qhorus tools (channels, messages, registry, observability)

`quarkus-mcp-server` handles tool registration automatically via `@Tool` annotations.
When Qhorus is on the classpath, its `QhorusMcpTools` bean registers all 38 tools
into the same MCP server Claudony is already using. No manual wiring needed.

Verify after adding the dependency that `GET /q/health` shows the `qhorus` feature
in the installed features list.

---

## Step 3 — Database

Qhorus uses Flyway to create its schema (V1–V8). These migrations live in the
`quarkus-qhorus` JAR on the classpath. Flyway picks them up automatically alongside
Claudony's own migrations.

**Ordering note:** Qhorus migrations are V1–V8. quarkus-ledger (pulled in transitively)
uses V1000–V1001. If Claudony has its own migrations, use a separate range (e.g. V100+)
or Flyway locations to avoid collision.

---

## Step 4 — Configuration

All Qhorus config is under `quarkus.qhorus.*`. Add to Claudony's `application.properties`:

```properties
# Qhorus agent communication mesh
quarkus.qhorus.enabled=true

# A2A compatibility endpoint (optional — for external orchestrator interop)
quarkus.qhorus.a2a.enabled=false

# Watchdog alerting (optional)
quarkus.qhorus.watchdog.enabled=false
```

quarkus-ledger config (structured observability, pulled in transitively):
```properties
quarkus.ledger.enabled=true
quarkus.ledger.hash-chain.enabled=true
quarkus.ledger.trust-score.enabled=false
```

---

## Step 5 — The dashboard observation layer

From the design spec: *"Claudony adds Qhorus as dependency. The Qhorus MCP tools
are registered on Claudony's Agent MCP endpoint alongside Claudony's session tools
and CaseHub worker tools."*

For Claudony's dashboard to observe agent activity on channels, it can use:

| Tool | What it shows |
|---|---|
| `list_channels` | All active channels |
| `list_instances` | Who's online, capabilities, last-seen |
| `get_channel_timeline` | All messages interleaved in order for a channel |
| `list_events` | Structured agent telemetry (tool calls, durations, token counts) |
| `channel_digest` | Summary of a channel's current state |
| `list_watchdogs` | Active alert conditions |

These are already implemented and tested — Claudony just calls them via the same MCP
server that agents use. No separate REST client needed.

---

## The 38 Qhorus MCP tools

Grouped by concern:

**Channels**
`create_channel` `find_channel` `list_channels` `channel_digest`
`pause_channel` `resume_channel` `clear_channel` `force_release_channel`
`set_channel_writers` `set_channel_admins` `set_channel_rate_limits`

**Messages**
`send_message` `check_messages` `get_replies` `search_messages`
`delete_message` `wait_for_reply` `cancel_wait`

**Presence / Registry**
`register` `deregister_instance` `list_instances`

**Shared Data / Artefacts**
`share_data` `get_shared_data` `list_shared_data`
`claim_artefact` `release_artefact` `revoke_artefact`

**Human-in-the-loop**
`request_approval` `respond_to_approval` `list_pending_approvals`
`list_pending_waits`

**Observers (read-only telemetry)**
`register_observer` `read_observer_events` `deregister_observer`

**Observability (Phase 12)**
`list_events` `get_channel_timeline`

**Watchdog alerting**
`register_watchdog` `delete_watchdog` `list_watchdogs`

---

## Key design constraint

`claudony_session_id` on `register` is **always optional**. Qhorus works identically
standalone or embedded. Don't add Claudony-specific required fields to Qhorus tools —
the protocol discipline is that all tools work the same regardless of host.

---

## Confirming it works

After embedding:

```bash
# Quarkus startup log should show both features
INFO features: [agroal, cdi, hibernate-orm, ledger, mcp-server-http, qhorus, rest, ...]

# An agent can register and send a message through Claudony's MCP endpoint
# (same endpoint Claudony's own tools are on)
```

Run Qhorus's existing test suite to verify nothing regressed:
```bash
cd ~/claude/quarkus-qhorus
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl runtime -Dno-format
# Expect: 557 tests, 0 failures
```

---

## What doesn't need to change in Qhorus

Nothing. Qhorus is complete through Phase 12. The embedding is purely additive —
Claudony takes a dependency, Qhorus doesn't change.

If something in Qhorus does need to change to support the embedding, open an issue
in `mdproctor/quarkus-qhorus` and the qhorus session picks it up.
