# Normative Channel Layout

The NormativeChannelLayout is Qhorus's recommended channel topology for multi-agent coordination. It separates obligation-carrying acts, telemetry, and human governance into three dedicated channels — each with a distinct normative role and enforced type constraints.

## The 3-Channel Pattern

```
case-{caseId}/
├── work        APPEND   All obligation-carrying types (open — null allowedTypes)
├── observe     APPEND   Telemetry only (allowedTypes: EVENT)
└── oversight   APPEND   Human governance (allowedTypes: QUERY,COMMAND)
```

Create channels with `create_channel`:

```
create_channel("case-abc/work",      "Worker coordination", "APPEND")
create_channel("case-abc/observe",   "Telemetry",           "APPEND", allowed_types="EVENT")
create_channel("case-abc/oversight", "Human governance",    "APPEND", allowed_types="QUERY,COMMAND")
```

## Channel Roles

**`work`** — The primary coordination space. All 9 message types are permitted (null `allowedTypes` = open). Workers `QUERY` peers, `COMMAND` agents, `HANDOFF` to the next worker, and post `DONE` on completion. The CommitmentStore tracks every open obligation in this channel.

**`observe`** — Pure telemetry. Only `EVENT` messages. Agents post here for every significant tool call, state change, or decision point. No obligations are created — the constraint (`allowedTypes: EVENT`) is enforced at both the MCP tool layer and `MessageService` before any persistence occurs.

**`oversight`** — Human governance. Only `QUERY` and `COMMAND` messages. Agents post `QUERY` here when they need human input. Humans post `COMMAND` to inject directives. All entries appear in the normative ledger as first-class speech acts.

## `allowedTypes` Enforcement

The `allowedTypes` constraint is set when the channel is created and enforced at two layers:

1. **MCP tool layer (client):** `send_message` checks the policy before delegating to the service. Violations are rejected immediately — no round-trip to the database.

2. **`MessageService` (server):** Safety net for any non-MCP callers. Same `MessageTypePolicy` SPI, same `MessageTypeViolationException` (extends `IllegalStateException`, wrapped by `@WrapBusinessError`).

## `MessageTypePolicy` SPI

```java
@FunctionalInterface
public interface MessageTypePolicy {
    void validate(Channel channel, MessageType type);
    // throw MessageTypeViolationException to reject; return normally to permit
}
```

The default `StoredMessageTypePolicy` is `@ApplicationScoped`. Override with `@Alternative @Priority`:

```java
@Alternative
@Priority(10)
@ApplicationScoped
public class MyCustomPolicy implements MessageTypePolicy {
    @Override
    public void validate(Channel channel, MessageType type) {
        // custom logic — e.g. derive constraints from channel name prefix
    }
}
```

## Project Template

```
# Create the 3-channel normative layout:
create_channel("case-{id}/work",     "Worker coordination", "APPEND")
create_channel("case-{id}/observe",  "Telemetry",           "APPEND", allowed_types="EVENT")
create_channel("case-{id}/oversight","Human governance",    "APPEND", allowed_types="QUERY,COMMAND")

# Agent startup:
register("{workerId}", "{description}", ["{capabilities}"])
send_message("case-{id}/work", sender="{workerId}", type="STATUS", content="Starting: {goal}")

# During work — post to observe for every tool call:
send_message("case-{id}/observe", sender="{workerId}", type="EVENT", content='{"tool":"..."}')

# Signal completion:
share_data("{key}", description="{desc}", content=...)
send_message("case-{id}/work", sender="{workerId}", type="DONE", content="Done. Output: shared-data:{key}")

# If human input needed:
send_message("case-{id}/oversight", sender="{workerId}", type="QUERY",
             content="Ambiguous finding — include?", target="instance:human")
```

## Anti-Patterns

**EVENT on the work channel** — Mixes telemetry with obligations. The CommitmentStore stays clean only if EVENTs flow exclusively to `observe`.

**QUERY on the observe channel** — Rejected by `allowedTypes` enforcement. An EVENT-only channel cannot carry obligation-creating acts.

**Obligation-carrying acts on oversight** — The oversight channel is `QUERY`/`COMMAND` only. STATUS updates and DONE signals belong on `work`, not `oversight`.

## Examples

See `examples/normative-layout/` for deterministic CI tests proving the pattern works correctly. `SecureCodeReviewScenario` is the canonical Layer 1 reference (Pure Qhorus, no LLM).

See `examples/agent-communication/NormativeLayoutAgentTest` for the same scenario exercised with real Jlama agents (requires `-Pwith-llm-examples`).
