# Qhorus Agent Mesh Framework — Developer Guide

> How agents communicate, coordinate, and stay accountable through typed channels.
> This is the Layer 1 reference: pure Qhorus, no Claudony, no CaseHub, no LLM required.

---

## Who This Is For

You are building a multi-agent system and adding `casehub-qhorus` as a dependency. You want to
know how agents talk to each other, how obligations are tracked, how humans stay in the loop, and
how you end up with an audit trail that proves what happened.

This guide walks you from the message vocabulary through the full agent lifecycle, with a
complete worked example at the end.

**Read this first if you are new to the normative layer.**
For the theoretical foundations — why formal speech act semantics matter, and how the normative
layer compares to other frameworks — read [normative-layer.md](normative-layer.md).

---

## Part 1 — The Message Vocabulary

Everything in Qhorus is a message. But not all messages mean the same thing. Before you write
a single line of code, you need the vocabulary: nine message types, each with a precise
semantic role.

### Why types matter

In most messaging systems, a message is a bag of bytes with a topic label. The system delivers it;
the application decides what it means. This works until you have ten agents and no one agrees on
what "failed" means. Did the agent refuse? Did it try and fail? Did it hand off silently? Status
fields and free-text fields cannot answer these questions reliably.

Qhorus message types are **performatives** — each type does something by being sent. A COMMAND
does not say "please act." It creates an obligation. A DONE does not say "I finished." It
fulfills one. The type carries the semantics so your code does not have to infer them from content.

### The nine types in three groups

Think of the types in three natural groups: **information exchange**, **obligation
lifecycle**, and **telemetry**.

```
GROUP 1 — INFORMATION EXCHANGE (no obligation created or closed)
────────────────────────────────────────────────────────────────
  QUERY    ─── "Do you know X?"
               → creates a weak expectation (not a commitment)
               → addressed to a peer; peer is expected but not obligated to respond

  RESPONSE ─── "Here is X."
               → directly answers a QUERY (use correlationId to link them)
               → discharges the expectation created by QUERY

  STATUS   ─── "Still working on it — 40% done."
               → assertive; truthfully reports current state
               → extends the deadline window on an open COMMAND obligation
               → does NOT create a new obligation

GROUP 2 — OBLIGATION LIFECYCLE (creates, extends, or closes a commitment)
──────────────────────────────────────────────────────────────────────────
  COMMAND  ─── "Do X."
               → creates a commitment in the CommitmentStore: OPEN
               → the recipient is now the obligor; you are the requester
               → must be resolved by one of the four terminal types below

  DONE     ─── "I did X successfully."  [TERMINAL — closes COMMAND]
               → fulfills the commitment: OPEN → FULFILLED
               → use when the task is fully complete

  FAILURE  ─── "I tried X and could not complete it."  [TERMINAL — closes COMMAND]
               → closes the commitment: OPEN → FAILED
               → content must explain what happened — "unknown error" is not acceptable
               → categorically different from DECLINE: you tried, it failed

  DECLINE  ─── "I cannot or will not do X, because..."  [TERMINAL — closes COMMAND]
               → closes the commitment: OPEN → DECLINED
               → content must state the reason — this is a reasoned refusal, not a timeout
               → categorically different from FAILURE: you did not try

  HANDOFF  ─── "I am transferring X to agent Y."  [TERMINAL — for sender; OPEN — for recipient]
               → formally delegates: CommitmentStore OPEN → DELEGATED
               → target is required (instance:id, capability:tag, or role:name)
               → a new commitment opens for the named recipient
               → creates a causal link in the ledger (causedByEntryId)

GROUP 3 — TELEMETRY (no obligation, no commitment)
────────────────────────────────────────────────────
  EVENT    ─── "I did Y (observability only)."
               → no obligation created or discharged
               → use freely for tool calls, state changes, decision points
               → the substrate for the telemetry dashboard and performance monitoring
```

### Visualising a typical obligation

Here is what a COMMAND lifecycle looks like in time:

```
     Agent A                                  Agent B
        │                                         │
        │── COMMAND "Analyse AuthService.java" ──►│  CommitmentStore: OPEN
        │                                         │
        │                                      [working...]
        │◄── STATUS "Reading file, 30% done" ───-─│  deadline window extended
        │                                         │
        │◄── STATUS "Found 3 issues, writing report" │  deadline window extended
        │                                         │
        │◄── DONE "Report at shared-data:auth-v1" ─│  CommitmentStore: FULFILLED
        │                                         │
```

And a QUERY / RESPONSE exchange (no CommitmentStore entry):

```
     Agent A                                  Agent B
        │                                         │
        │── QUERY "Is the refresh path in scope?" ►│  (weak expectation only)
        │                                         │
        │◄── RESPONSE "Yes — line 142, same pattern" │  expectation discharged
        │                                         │
```

And a DECLINE (Agent B cannot do the work):

```
     Agent A                                  Agent B
        │                                         │
        │── COMMAND "Run performance tests" ──────►│  CommitmentStore: OPEN
        │                                         │
        │◄── DECLINE "No test environment access" ─│  CommitmentStore: DECLINED
        │                                         │  (obligation formally closed)
        │   (Agent A can now re-route immediately)
```

The difference between FAILURE and DECLINE matters operationally:

| What happened | Message | Correct next action |
|---|---|---|
| Agent tried and something broke | FAILURE | Investigate the agent; retry with repair |
| Agent cannot or will not attempt | DECLINE | Re-route to a capable agent immediately |
| Agent timed out (no response) | *(nothing)* | CommitmentStore shows OPEN → eventually EXPIRED |

These answers come from the type, not from parsing the content field.

---

## Part 2 — Channels

A channel is a named, typed message pipeline with declared semantics. Agents send messages to
channels, not to each other directly. Other agents read from channels.

### Creating a channel

```
create_channel(
    name:        "case-abc/work",
    description: "Worker coordination",
    semantic:    "APPEND",
    allowed_types: null        # null means all 9 types are permitted
)
```

Channel names are free strings. The convention `case-{id}/{purpose}` groups channels by case —
Qhorus does not enforce this, but the normative layout depends on it.

### The five channel semantics

The `semantic` field is not just metadata. It controls how `check_messages` behaves:

```
APPEND    ─── Messages accumulate in order.
              check_messages returns new messages since cursor.
              ──────────────────────────────────────────────────────────────────────
              Use for: standard coordination (the default for most channels)

BARRIER   ─── All declared contributors must post before the channel opens.
              check_messages blocks until every contributor has written.
              ──────────────────────────────────────────────────────────────────────
              Use for: parallel research teams — everyone must finish before
              a synthesiser reads the results. Example:
              create_channel("research/convergence", semantic="BARRIER",
                             barrier_contributors="analyst-1,analyst-2,analyst-3")

COLLECT   ─── All accumulated messages delivered atomically when polled.
              check_messages returns everything and clears the channel.
              ──────────────────────────────────────────────────────────────────────
              Use for: fan-out work queues — each agent reads its batch once

EPHEMERAL ─── Messages consumed on read.
              check_messages returns and deletes in one operation.
              ──────────────────────────────────────────────────────────────────────
              Use for: time-critical commands that must not be re-delivered

LAST_WRITE ── Only the latest message from each sender is kept.
              check_messages returns the current state, not history.
              ──────────────────────────────────────────────────────────────────────
              Use for: shared state displays — e.g. current case status
```

### Channel constraints — `allowed_types`

Channels can enforce which message types are permitted. This is the `allowed_types` parameter:

```
create_channel("case-abc/observe", ..., allowed_types="EVENT")
# → only EVENT messages accepted; QUERY on this channel is rejected at the MCP layer

create_channel("case-abc/oversight", ..., allowed_types="QUERY,COMMAND")
# → only QUERY and COMMAND accepted; STATUS on this channel is rejected
```

Enforcement happens at two levels. The MCP tool layer (`send_message`) checks the policy
before touching the database. `MessageService` enforces it again as a safety net for any
non-MCP caller. Both layers use the same `MessageTypePolicy` SPI — override it with
`@Alternative @Priority` if you need custom constraint logic.

---

## Part 3 — The NormativeChannelLayout

A single channel could hold everything. You could put QUERY, COMMAND, STATUS, DONE, and EVENT
all in one place. The ledger would record it all; the CommitmentStore would track it.

The problem is **signal pollution**. A telemetry EVENT alongside a COMMAND makes the commitment
view noisy. A QUERY on a channel meant for human governance creates an obligation neither party
intended. As agent count grows, a single channel becomes an undifferentiated stream where
obligation health, telemetry, and human governance are impossible to reason about separately.

The NormativeChannelLayout separates concerns at the channel level:

```
case-{caseId}/
├── work        APPEND   allowedTypes: null (all 9 types)
│                        Agent-to-agent coordination. All obligation-carrying
│                        acts live here. The CommitmentStore is cleanest when
│                        this channel has no EVENTs.
│
├── observe     APPEND   allowedTypes: EVENT
│                        Pure telemetry. Every tool call, state change, and
│                        decision point. No obligations created here — ever.
│                        The dashboard streams this channel as the activity log.
│
└── oversight   APPEND   allowedTypes: QUERY,COMMAND
                         Human governance. Agents post QUERY here when they
                         need human input. Humans post COMMAND to inject
                         directives. All entries are first-class ledger events.
```

### Why this separation matters

```
WITHOUT SEPARATION                       WITH NORMATIVE LAYOUT
──────────────────────────────────────   ─────────────────────────────────────
work channel                             work channel
  ├── COMMAND (creates obligation)          ├── COMMAND (creates obligation)
  ├── EVENT   (mixes with obligations)      ├── STATUS  (extends deadline)
  ├── QUERY   (creates obligation)          ├── QUERY   (creates obligation)
  ├── STATUS  (extends deadline)            ├── DONE    (closes obligation)
  ├── EVENT   (...)                         └── HANDOFF (transfers obligation)
  ├── COMMAND (creates obligation)
  ├── EVENT   (...)                        observe channel
  └── DONE    (which COMMAND?)               ├── EVENT
                                             ├── EVENT
  → CommitmentStore query returns             └── EVENT  ← clean telemetry only
    mixed signal: EVENTs pollute
    the obligation view               oversight channel
                                       ├── QUERY (from agent to human)
                                       └── COMMAND (from human to agent)
```

`list_stalled_obligations` on the work channel returns only stalled COMMANDs — no EVENT noise.
`get_telemetry_summary` on the observe channel gives you clean tool-call aggregation.
Human decisions on the oversight channel are isolated from agent coordination.

### Creating the layout

```
create_channel("case-abc/work",      "Worker coordination",  "APPEND")
create_channel("case-abc/observe",   "Telemetry",            "APPEND", allowed_types="EVENT")
create_channel("case-abc/oversight", "Human governance",     "APPEND", allowed_types="QUERY,COMMAND")
```

---

## Part 4 — Agent Lifecycle

Every agent in the normative mesh follows the same lifecycle: register, announce, work, complete.
The mesh is designed so that each step is a typed message the infrastructure can track — not just
a convention in documentation.

### Step 1 — Register

Before posting any messages, an agent registers itself in the instance registry:

```
register(
    instance_id:  "researcher-001",
    description:  "Security analyst — code review specialist",
    capabilities: ["security", "code-analysis"]
)
```

Registration returns the list of active channels and online instances — the agent's immediate
context when it joins. The instance registry enables capability-based addressing: other agents
can COMMAND `capability:security` instead of a specific instance ID, and the routing is
resolved from the registry.

### Step 2 — Announce

The first message on the work channel is always a STATUS, announcing what this agent is starting:

```
send_message("case-abc/work",
    sender: "researcher-001",
    type:   "STATUS",
    content: "Starting security analysis of AuthService.java")
```

STATUS here does not close any obligation — there is none open yet. It tells peers and humans
watching the dashboard that this agent is active and what it intends to do.

### Step 3 — Work loop

During work, an agent does two things repeatedly:

**Post telemetry to observe:**
```
send_message("case-abc/observe",
    sender: "researcher-001",
    type:   "EVENT",
    content: '{"tool":"read_file","path":"AuthService.java","lines":312}')

send_message("case-abc/observe",
    sender: "researcher-001",
    type:   "EVENT",
    content: '{"tool":"web_search","query":"SQL injection patterns 2026"}')
```

EVENT content is free-form. JSON with `tool`, `duration_ms`, and `token_count` fields is
recommended because `get_telemetry_summary` extracts those fields automatically for aggregation.

**Pass `correlationId` on EVENT messages to link them to an obligation.** When you do,
`get_obligation_activity` returns those EVENTs alongside the COMMAND they relate to —
giving a single cross-channel view of everything that happened during an obligation:

```
send_message("case-abc/observe",
    sender:         "researcher-001",
    type:           "EVENT",
    content:        '{"tool":"read_file","path":"AuthService.java","lines":312}',
    correlation_id: "corr-research-001")   # ← links this tool call to the obligation
```

**Check messages on work:**
```
check_messages("case-abc/work", after_id=0, limit=20)
```

Check after each major milestone, not after every tool call. A good heuristic: check at
startup (to see what prior agents left), after completing each logical step, and before posting
DONE. If a QUERY arrives addressed to you, respond with RESPONSE and a matching `correlation_id`.

**Post STATUS at major milestones:**
```
send_message("case-abc/work",
    sender: "researcher-001",
    type:   "STATUS",
    content: "Completed file scan — 3 issues found, writing analysis")
```

STATUS tells peers and humans that the agent is making progress. If a COMMAND was issued to
this agent and time is passing, posting STATUS extends the deadline window on that commitment —
preventing a false EXPIRED state in the CommitmentStore.

### Step 4 — Share artefacts before signalling completion

Large outputs (reports, schemas, datasets) go in shared data, not in message bodies. Message
bodies are for communication; shared data is for transfer.

```
# Single-shot artefact:
share_artefact("auth-analysis-v1",
    description: "Security analysis — AuthService.java",
    created_by:  "researcher-001",
    content:     "## Security Analysis\n...")

# Chunked upload for large artefacts:
begin_artefact("large-report", description="...", created_by="researcher-001",
               content="first chunk...")
append_chunk("large-report", content="second chunk...")
finalize_artefact("large-report", content="final chunk")
```

Use versioned, descriptive keys: `auth-analysis-v1`, not `temp`, `data`, or `result`. The key
is referenced in the completion message so downstream agents can retrieve it.

### Step 5 — Signal completion

The final message closes the agent's contribution to the case:

```
# Successful completion:
send_message("case-abc/work",
    sender: "researcher-001",
    type:   "DONE",
    content: "Analysis complete. 3 findings. Report: shared-data:auth-analysis-v1")

# Handing to the next agent:
send_message("case-abc/work",
    sender: "researcher-001",
    type:   "HANDOFF",
    content: "Passing analysis to reviewer. Artefact: shared-data:auth-analysis-v1",
    target:  "instance:reviewer-001")

# Cannot complete:
send_message("case-abc/work",
    sender: "researcher-001",
    type:   "DECLINE",
    content: "Cannot proceed — no read access to TokenRefreshService.java. Needs DB credentials.")

# Tried and failed:
send_message("case-abc/work",
    sender: "researcher-001",
    type:   "FAILURE",
    content: "SQL injection scan failed — AST parser threw NullPointerException on line 312. Stack trace in shared-data:error-log")
```

Use DONE only when the task is fully complete. Use HANDOFF to pass work to a specific agent.
Use DECLINE when you cannot or will not attempt the task. Use FAILURE when you tried and
could not complete. Each type has a different effect on the CommitmentStore state machine.

---

## Part 5 — The Commitment Store

The CommitmentStore is the obligation ledger that runs underneath every COMMAND. When you send
a COMMAND, you do not just post a message — you create a commitment: a record that says this
agent issued a directive, that agent is the obligor, and it must resolve it.

### The 7-state lifecycle

```
                       ┌─────────────────────────────────────────────────┐
                       │                CommitmentStore                  │
                       │                                                 │
    COMMAND sent ─────►│  OPEN                                           │
                       │   │                                             │
                       │   ├── obligor acknowledges ──────► ACKNOWLEDGED │
                       │   │        │                           │        │
                       │   │        │                           │        │
                       │   └────────┴────────────────────────► │        │
                       │                                        │        │
                       │   ┌─────────────────────────┬──────────┘        │
                       │   │                         │                   │
                       │   ▼                         ▼                   │
                       │  FULFILLED ◄── DONE        DELEGATED ◄─ HANDOFF │
                       │  DECLINED  ◄── DECLINE                          │
                       │  FAILED    ◄── FAILURE                          │
                       │  EXPIRED   ◄── deadline passed (no response)    │
                       └─────────────────────────────────────────────────┘
```

**OPEN** — COMMAND sent, waiting for resolution. The obligor has seen (or will see) the message.

**ACKNOWLEDGED** — Obligor has explicitly signalled receipt (e.g. a STATUS message early on).
Not all workflows use this — it is optional. Its absence is not stalled; it is just unacknowledged.

**FULFILLED** — DONE received. The task completed successfully. The commitment is closed.

**DECLINED** — DECLINE received. The obligor formally refused. Reason in the message content.

**FAILED** — FAILURE received. The obligor tried and could not complete. Reason in content.

**DELEGATED** — HANDOFF received. The obligation is formally transferred. A new OPEN commitment
opens for the named recipient, causally linked by `causedByEntryId`.

**EXPIRED** — Deadline passed with no terminal resolution. The obligor did not DONE, DECLINE,
FAILURE, or HANDOFF within the time window. This surfaces immediately in `list_stalled_obligations`.

### Querying commitments

```
list_pending_commitments()
# → all non-terminal commitments across all channels

list_my_commitments("case-abc/work", sender="researcher-001", role="obligor")
# → obligations this agent owes

get_commitment(correlation_id="corr-abc-123")
# → full lifecycle of one specific commitment

list_stalled_obligations("case-abc/work", older_than_seconds=60)
# → commitments that have been OPEN with no resolution for over a minute
```

`list_stalled_obligations` is the SLA enforcement tool. It does not require custom monitoring
code — any COMMAND that was sent and not resolved is queryable by age.

---

## Part 6 — The Normative Ledger

Every message sent through Qhorus — all nine types — is recorded as an immutable `MessageLedgerEntry`.
The ledger is the complete, tamper-evident history of every interaction in every channel. It is
built as the system runs, not assembled from logs after the fact.

### What gets recorded

Every `send_message` call writes a ledger entry with:

```
entry_id         — UUID, unique per entry
sequence_number  — monotonically increasing
channel          — the channel name
actor_id         — who sent the message
message_type     — one of the 9 types
correlation_id   — links the entry to an obligation chain
caused_by_entry  — UUID of the parent entry; spans channels — may reference an entry in a different channel
content          — the message body
hash             — SHA-256 of this entry's content + previous hash (tamper evidence)
occurred_at      — wall-clock timestamp
attestation      — optional: SOUND, ENDORSED, FLAGGED, CHALLENGED (with confidence score)
```

For EVENT entries, `tool_name`, `duration_ms`, and `token_count` are extracted from JSON content
if present — these feed the telemetry aggregation tools.

### The eight ledger query tools

```
get_obligation_activity(correlation_id, limit?)
# → returns the complete cross-channel causal narrative for an obligation
# → includes ALL entries sharing this correlationId across ALL channels
# → follows causedByEntryId links across channel boundaries, capturing entries
#   that carry a different correlationId (e.g. an oversight escalation triggered
#   by the original work COMMAND — different correlationId, but causally linked)
# → each entry includes a 'channel' field showing which channel it belongs to
# → this is the primary audit and debugging tool — one call returns the full story

list_ledger_entries(channel_name, ...)
# → all entries in a channel; filter by type, sender, since, correlation_id
# → use type_filter="COMMAND,DONE,FAILURE" to extract the obligation lifecycle

get_obligation_chain(channel_name, correlation_id)
# → complete history of one obligation: initiator, participants, elapsed time,
#   handoff count, resolution, live CommitmentStore state

get_causal_chain(ledger_entry_id)
# → walk causedByEntryId links upward to the root, crossing channel boundaries
# → use this to trace: "what chain of events led to this FAILURE?"
# → returns entries from any channel in causal order

list_stalled_obligations(channel_name, older_than_seconds)
# → COMMAND entries with no terminal sibling, older than threshold
# → the SLA breach detector

get_obligation_stats(channel_name)
# → total commands, fulfilled, failed, declined, delegated, open, stalled, fulfillment rate

get_telemetry_summary(channel_name, since?)
# → EVENT aggregation by tool_name: count, avg_duration_ms, total_tokens
# → answers: "how much did the fraud model cost across this case?"

get_channel_timeline(channel_name, after_id?, limit?)
# → all messages in chronological order, interleaving regular messages and EVENT telemetry
# → use for full case reconstruction
```

### The causal chain — tracking obligation lineage

When Agent A HANDOFFs to Agent B who HANDOFFs to Agent C who posts DONE, the ledger captures
the full lineage:

```
entry e1: COMMAND  (coordinator → agent-A)   causedByEntryId: null
entry e2: HANDOFF  (agent-A    → agent-B)    causedByEntryId: e1
entry e3: HANDOFF  (agent-B    → agent-C)    causedByEntryId: e2
entry e4: DONE     (agent-C    → coordinator) causedByEntryId: e3

get_causal_chain(e4) → [e1, e2, e3, e4]  ← complete ancestry
```

Six months later: `get_causal_chain` on the DONE entry returns the complete ancestry.
The FCA asks "who was responsible at each step?" — the chain is in the ledger.

### Cross-channel causal links — the unified narrative

`causedByEntryId` is a UUID reference that resolves across all channels. An entry in the
work channel can causally link to an entry in the oversight channel; an entry in the
oversight channel can causally link back to work. Each channel maintains its own
tamper-evident hash chain — integrity is per-chain — but causal provenance is navigable
across chains.

This means `get_obligation_activity` is not a correlationId join. It walks the causal
DAG. Consider a case where a work COMMAND triggers an oversight escalation that must
resolve before the work DONE can be posted:

```
work/COMMAND    (correlationId: corr-001)       causedByEntryId: null
  observe/EVENT  (correlationId: corr-001)       causedByEntryId: work/COMMAND
  observe/EVENT  (correlationId: corr-001)       causedByEntryId: work/COMMAND
  oversight/QUERY (correlationId: corr-human-001) causedByEntryId: work/COMMAND
    oversight/COMMAND (correlationId: corr-human-001) causedByEntryId: oversight/QUERY
work/DONE       (correlationId: corr-001)       causedByEntryId: oversight/COMMAND
```

The oversight entries carry a different `correlationId` — they belong to a separate
human escalation thread. A correlationId-only query would miss them entirely.
`get_obligation_activity` follows the causal DAG:

```
get_obligation_activity("corr-001")

→ [
    { channel: "case-abc/work",      type: "COMMAND",  actor: "coordinator",   ... },
    { channel: "case-abc/observe",   type: "EVENT",    actor: "reviewer-001",  tool: "read_file", ... },
    { channel: "case-abc/observe",   type: "EVENT",    actor: "reviewer-001",  tool: "web_search", ... },
    { channel: "case-abc/oversight", type: "QUERY",    actor: "reviewer-001",  content: "Include finding #2?", ... },
    { channel: "case-abc/oversight", type: "COMMAND",  actor: "human",         content: "Yes — flag as low confidence.", ... },
    { channel: "case-abc/work",      type: "DONE",     actor: "reviewer-001",  ... }
  ]
```

One call. The complete causal story — machine obligations, telemetry, and human governance —
in causal order. Not three separate timelines.

This is also what makes `get_causal_chain` on a DONE entry fully meaningful. Previously,
walking `causedByEntryId` stopped at the channel boundary. Now the chain traces back through
every channel that contributed to the outcome.

---

## Part 7 — Human-in-the-Loop

The normative mesh makes human participation a first-class act, not an afterthought. Three
mechanisms support human-in-the-loop: the oversight channel, the approval gate, and watchdogs.

### The oversight channel pattern

An agent that needs human input posts a QUERY to oversight and waits:

```
send_message("case-abc/oversight",
    sender:  "reviewer-001",
    type:    "QUERY",
    content: "Finding #2 may be a false positive — include in report?",
    target:  "instance:human",
    correlation_id: "corr-human-001")

# While waiting, post STATUS to work so peers know you are paused:
send_message("case-abc/work",
    sender:  "reviewer-001",
    type:    "STATUS",
    content: "Paused at finding #2 — awaiting human decision via oversight")
```

The human posts a COMMAND back:

```
# Human (via dashboard interjection panel or direct tool call):
send_message("case-abc/oversight",
    sender:  "human",
    type:    "COMMAND",
    content: "Include it — flag as low confidence. Continue.")
```

This is a first-class speech act in the ledger. The human decision is recorded alongside agent
decisions with the same causal chain, the same tamper-evident seal.

### The approval gate — `request_approval` + `wait_for_reply`

For agent-initiated blocking approval requests, use the approval gate:

```
# Agent posts QUERY and blocks until human responds (or timeout):
result = request_approval("case-abc/oversight",
    content:         "Deploy to production with finding #2 flagged?",
    timeout_seconds: 300)

if result.success:
    # human approved — result.message contains the response
    proceed_with_deployment()
else:
    # timeout or decline
    handle_no_approval()
```

`request_approval` generates a unique correlation ID, posts the QUERY, and long-polls using
`wait_for_reply`. Use `list_pending_commitments` to see all outstanding approval requests. Use
`cancel_wait` to unblock a stalled approval gate.

For tests: `requestApprovalWithCorrelationId(channel, content, correlationId, timeout)` accepts
a pre-supplied correlation ID so tests can pre-seed the RESPONSE before calling.

### Watchdogs — automated alerting

Watchdogs fire when observable conditions are met. They do not require polling loops or custom
monitoring code. Requires `casehub.qhorus.watchdog.enabled=true`.

```
register_watchdog(
    condition_type:       "BARRIER_STUCK",
    target_name:          "research/convergence",
    threshold_seconds:    120,
    notification_channel: "case-abc/oversight",
    created_by:           "supervisor")

# → if the BARRIER channel has not been released within 120 seconds,
#   an EVENT is posted to the oversight channel automatically
```

Condition types:

| Type | Fires when |
|---|---|
| `BARRIER_STUCK` | A BARRIER channel has not released within `threshold_seconds` |
| `APPROVAL_PENDING` | A QUERY on the oversight channel has no RESPONSE after `threshold_seconds` |
| `AGENT_STALE` | An agent's `lastSeen` is older than `threshold_seconds` |
| `CHANNEL_IDLE` | A channel has received no messages for `threshold_seconds` |
| `QUEUE_DEPTH` | A channel's message count exceeds `threshold_count` |

---

## Part 8 — Layer 1 Example: Secure Code Review

Two agents coordinate on a security analysis. No LLM, no Claudony, no CaseHub. Pure Qhorus.
This is the canonical Layer 1 reference — the same scenario is extended at Layer 2 (+ casehub-ledger),
Layer 3 (+ Claudony, real Claude sessions), and Layer 4 (+ CaseHub, full orchestration).

**The agents:** `researcher-001` (security analyst) and `reviewer-001` (code reviewer)
**The task:** Analyse `AuthService.java`, then review the findings

### Setup — creating the normative layout

```java
// Part 3 — NormativeChannelLayout applied
channelService.create("case-abc/work",      "Worker coordination",  APPEND, null, null, null, null, null, null);
channelService.create("case-abc/observe",   "Telemetry",            APPEND, null, null, null, null, null, "EVENT");
channelService.create("case-abc/oversight", "Human governance",     APPEND, null, null, null, null, null, "QUERY,COMMAND");
```

The `observe` channel rejects any non-EVENT message (enforced at the MCP and service layers).
The `oversight` channel rejects STATUS, DONE, FAILURE — only QUERY and COMMAND get through.

### Phase 1 — Researcher works

```
// Part 4 — Agent Lifecycle: Steps 1 and 2
register("researcher-001", "Security analyst", ["security", "code-analysis"])

send_message("case-abc/work", "researcher-001", "STATUS",
    "Starting security analysis of AuthService.java")
//  ↑ Part 1: STATUS — assertive, no obligation created; announces intent
```

Researcher scans the files and posts telemetry:

```
// Part 4 — Step 3: work loop — post EVENT for each tool call
send_message("case-abc/observe", "researcher-001", "EVENT",
    '{"tool":"read_file","path":"AuthService.java","lines":312}')

send_message("case-abc/observe", "researcher-001", "EVENT",
    '{"tool":"read_file","path":"TokenRefreshService.java","lines":89}')
// ↑ Part 1: EVENT — no obligation; pure telemetry; only channel that accepts it
```

Researcher shares findings and signals completion:

```
// Part 4 — Step 4: share artefact before DONE
share_artefact("auth-analysis-v1",
    description: "Security analysis",
    created_by:  "researcher-001",
    content:     "## Analysis\nFinding 1: SQL injection — HIGH\nFinding 2: Stale token — MEDIUM")

// Part 4 — Step 5: signal completion
// Part 1 — DONE: closes the obligation created when the case coordinator issued a COMMAND
//                (or is self-contained if the researcher is the first agent)
send_message("case-abc/work", "researcher-001", "DONE",
    "Analysis complete. 3 findings. Report: shared-data:auth-analysis-v1",
    correlation_id="corr-research-001")
```

At this point, the ledger holds 5 entries: STATUS, EVENT×2, (share_artefact does not write a
ledger entry — it is data, not a speech act), DONE.

The CommitmentStore for `corr-research-001` moves to FULFILLED.

```
list_ledger_entries("case-abc/work", type_filter="DONE,FAILURE,DECLINE")
// → [{ type: "DONE", actor: "researcher-001", correlationId: "corr-research-001", ... }]

get_obligation_stats("case-abc/work")
// → { total: 1, fulfilled: 1, failed: 0, declined: 0, open: 0, fulfillmentRate: 1.0 }
```

### Phase 2 — Reviewer asks a clarifying question

Reviewer registers, checks messages, sees the DONE, and starts its review:

```
// Part 4 — Step 1: register
register("reviewer-001", "Security reviewer", ["review", "security"])

// Part 3 — check work channel to see what the researcher left
check_messages("case-abc/work", after_id=0)
// → [STATUS, DONE from researcher-001]

// Reviewer raises a clarifying question — a QUERY creates an obligation on researcher-001
// Part 1 — QUERY vs COMMAND: asking for information, not demanding action
send_message("case-abc/work", "reviewer-001", "QUERY",
    "Finding #3: does TokenRefreshService.java:142 share the same root cause?",
    correlation_id: "corr-query-001",
    target:         "instance:researcher-001")
//                  ↑ targeted QUERY — obligation lands on a specific agent, not "anyone"
```

Researcher responds:

```
// Part 1 — RESPONSE: directly answers the QUERY; correlationId links them
send_message("case-abc/work", "researcher-001", "RESPONSE",
    "Yes — same interpolated SQL pattern. One root cause, two surfaces.",
    correlation_id: "corr-query-001")
```

The QUERY/RESPONSE pair is in the ledger. The CommitmentStore closes the `corr-query-001`
expectation.

### Phase 3 — Escalation to human

Reviewer finds an ambiguous finding and escalates:

```
// Part 7 — Human-in-the-loop: oversight channel
// Part 1 — QUERY on oversight: expectation created for the human
send_message("case-abc/oversight", "reviewer-001", "QUERY",
    "Finding #2 (stale token) — is this in scope for this review cycle?",
    correlation_id: "corr-human-001",
    target:         "instance:human")

// Part 4 — Step 3: while waiting, post STATUS to work
send_message("case-abc/work", "reviewer-001", "STATUS",
    "Paused at finding #2 — awaiting human decision on scope")
//  ↑ STATUS: truthful, extends deadline, tells peers this agent is not stalled
```

Human reviews the oversight channel and responds:

```
// Human decision — a COMMAND in the normative ledger, not just a chat message
send_message("case-abc/oversight", "human", "COMMAND",
    "Include it — flag as low confidence. Continue.")
//  ↑ COMMAND from human: first-class speech act; recorded in ledger with SHA-256 seal
```

### Phase 4 — Reviewer completes

```
share_artefact("review-report-v1",
    description: "Code review report",
    created_by:  "reviewer-001",
    content:     "## Code Review Report\nRoot cause A: SQL injection (CRITICAL)\nRoot cause B: Stale token (HIGH, low confidence)")

send_message("case-abc/work", "reviewer-001", "DONE",
    "Review complete. Final report: shared-data:review-report-v1",
    correlation_id: "corr-review-001")
```

### What the ledger holds at the end

```
get_channel_timeline("case-abc/work")
→ [ STATUS  researcher-001  "Starting..."
    DONE    researcher-001  "Analysis complete. 3 findings..."
    QUERY   reviewer-001    "Finding #3: does TokenRefresh... share..."
    RESPONSE researcher-001 "Yes — same interpolated SQL pattern..."
    STATUS  reviewer-001    "Paused at finding #2..."
    DONE    reviewer-001    "Review complete..." ]

get_channel_timeline("case-abc/observe")
→ [ EVENT  researcher-001  {"tool":"read_file","path":"AuthService.java",...}
    EVENT  researcher-001  {"tool":"read_file","path":"TokenRefreshService.java",...} ]

get_channel_timeline("case-abc/oversight")
→ [ QUERY   reviewer-001  "Finding #2 (stale token) — in scope?"
    COMMAND human          "Include it — flag as low confidence. Continue." ]
```

Every message is tamper-evidently sealed. The complete case history — including the human
decision — is queryable in seconds, six months later, with no log scraping.

### The canonical Java test

`SecureCodeReviewScenario.java` (`examples/normative-layout/`) is the importable Layer 1
reference. Five test classes exercise it:

| Test | What it verifies |
|---|---|
| `NormativeLayoutHappyPathTest` | Full scenario end-to-end; all messages visible; artefacts retrievable |
| `NormativeLayoutObligationTest` | CommitmentStore state at each step; OPEN → FULFILLED correctly |
| `NormativeLayoutCorrectnessTest` | Message types respected; ledger entries match expected types |
| `NormativeLayoutRobustnessTest` | Re-registration; out-of-order messages; empty channel |
| `NormativeLayoutTypeEnforcementTest` | `allowedTypes` rejects violations; observe channel rejects QUERY |

---

## Part 9 — Anti-Patterns

These patterns look reasonable until the CommitmentStore fills with noise or the ledger becomes
unqueryable.

### 1. EVENT on the work channel

```
// ❌ Mixes telemetry with obligations
send_message("case-abc/work", "researcher-001", "EVENT", '{"tool":"read_file",...}')
// → CommitmentStore is clean only if EVENTs flow exclusively to observe
// → get_obligation_stats now includes EVENT noise in the entry count
// → list_stalled_obligations may surface EVENTs as unresolved

// ✅ Use the observe channel
send_message("case-abc/observe", "researcher-001", "EVENT", '{"tool":"read_file",...}')
```

### 2. QUERY on the observe channel

```
// ❌ Rejected by allowedTypes enforcement
send_message("case-abc/observe", "researcher-001", "QUERY", "Is this in scope?")
// → MessageTypeViolationException — the enforce channel does not accept QUERY
// → Even without enforcement, this creates a commitment on an EVENT-only channel

// ✅ Use the work channel for agent-to-agent queries
send_message("case-abc/work", "researcher-001", "QUERY", "Is this in scope?",
             target="instance:reviewer-001")
```

### 3. Silent failure

```
// ❌ Agent discovers it cannot proceed and posts nothing
// → CommitmentStore: OPEN forever → eventually EXPIRED
// → Peers and humans see nothing until list_stalled_obligations fires
// → The system has no way to distinguish "working slowly" from "dead"

// ✅ Always post DECLINE (reasoned refusal) or FAILURE (attempted, could not complete)
send_message("case-abc/work", "researcher-001", "DECLINE",
    "Cannot proceed — no read access to AuthService.java. Needs filesystem credentials.")
```

### 4. Obligation flooding

```
// ❌ Agent sends COMMAND to every peer at startup
for agent in all_registered_agents:
    send_message(work, sender, "COMMAND", "Tell me what you know")
// → Each COMMAND creates an OPEN commitment
// → Most peers won't respond — CommitmentStore fills with stale OPEN entries
// → list_stalled_obligations becomes noisy permanently

// ✅ Use targeted QUERY (not COMMAND) for information requests
//    Use COMMAND only when you are actually directing someone to act
send_message(work, sender, "QUERY", "Who has filesystem access?", target="capability:filesystem")
```

### 5. Using message bodies for large payloads

```
// ❌ Analysis report embedded directly in DONE message
send_message(work, sender, "DONE",
    "Complete. Report:\n" + five_thousand_line_report)
// → Message bodies are not designed for large payloads
// → Next agents receiving this message will struggle to parse useful signal from noise

// ✅ Share the artefact; reference the key in the message
share_artefact("analysis-v1", description="...", created_by=sender, content=report)
send_message(work, sender, "DONE", "Complete. Report: shared-data:analysis-v1")
```

### 6. Posting status updates to oversight

```
// ❌ Using oversight for progress reporting
send_message("case-abc/oversight", sender, "STATUS", "Working on finding #2 now...")
// → Rejected by allowedTypes (oversight accepts only QUERY and COMMAND)
// → Even if allowed: oversight is a governance space, not a progress log

// ✅ Status goes to work; escalations go to oversight
send_message("case-abc/work",     sender, "STATUS", "Working on finding #2 now...")
send_message("case-abc/oversight", sender, "QUERY",  "Should I include finding #2?")
```

---

## Part 10 — Quick-Start Template

Copy and adapt for any new case. Replace `{caseId}`, `{workerId}`, and `{capabilities}`.

### Setup (once per case)

```
# 1. Create the 3-channel normative layout
create_channel("case-{caseId}/work",      "Worker coordination", "APPEND")
create_channel("case-{caseId}/observe",   "Telemetry",           "APPEND", allowed_types="EVENT")
create_channel("case-{caseId}/oversight", "Human governance",    "APPEND", allowed_types="QUERY,COMMAND")

# 2. (Optional) Register a watchdog to alert on stalled work
register_watchdog(
    condition_type:       "BARRIER_STUCK",
    target_name:          "case-{caseId}/work",
    threshold_seconds:    120,
    notification_channel: "case-{caseId}/oversight",
    created_by:           "supervisor")
```

### Agent startup (each agent, on start)

```
# 3. Register in the instance registry
register("{workerId}", "{description}", ["{capabilities}"])

# 4. Announce intent on work channel
send_message("case-{caseId}/work", "{workerId}", "STATUS", "Starting: {goal}")
```

### During work (repeat as needed)

```
# 5. Post telemetry for every tool call, file read, or decision point
send_message("case-{caseId}/observe", "{workerId}", "EVENT",
    '{"tool":"{toolName}","duration_ms":{ms},"token_count":{n}}')

# 6. Post status at major milestones
send_message("case-{caseId}/work", "{workerId}", "STATUS", "Milestone: {description}")

# 7. Check messages after each major step
check_messages("case-{caseId}/work", after_id={lastSeen})
# → respond to any QUERY addressed to you with RESPONSE (same correlationId)

# 8. If human input needed — escalate to oversight
send_message("case-{caseId}/oversight", "{workerId}", "QUERY",
    "{question}", target="instance:human", correlation_id="{corrId}")
send_message("case-{caseId}/work", "{workerId}", "STATUS",
    "Paused — awaiting human decision via oversight")
```

### Completion

```
# 9. Share artefact before signalling done
share_artefact("{key}", description="{desc}", created_by="{workerId}", content={output})

# 10. Signal completion (pick one)
send_message("case-{caseId}/work", "{workerId}", "DONE",
    "Done. Output: shared-data:{key}")

send_message("case-{caseId}/work", "{workerId}", "HANDOFF",
    "Passing to next worker. Artefact: shared-data:{key}", target="instance:{nextAgent}")

send_message("case-{caseId}/work", "{workerId}", "DECLINE",
    "Cannot proceed — {reason}. {what would unblock this}")

send_message("case-{caseId}/work", "{workerId}", "FAILURE",
    "Failed — {what happened}. Evidence at shared-data:{errorLog}")
```

---

## What Comes Next

**Layer 2 — + casehub-ledger**

The Qhorus runtime already writes `MessageLedgerEntry` records for every message. Adding
casehub-ledger as a direct dependency (rather than transitively) enables the full attestation
model: SOUND/ENDORSED/FLAGGED/CHALLENGED verdicts, Bayesian Beta trust scoring per actor, and
EigenTrust propagation through the mesh. See `normative-layer.md` — *Trust derived from behaviour*.

**Layer 3 — + Claudony**

Claudony manages real Claude sessions as the agents. Every worker is a Claude instance in a tmux
session, with the channel names, startup checklist, and prior worker lineage injected into its
system prompt by `ClaudonyWorkerContextProvider`. The case graph panel and oversight interjection
panel live in the Claudony dashboard. The SPIs (`CaseChannelLayout`, `MeshParticipationStrategy`)
customise channel topology and participation mode per case type.

**Layer 4 — + CaseHub**

CaseHub orchestrates the full case lifecycle: cases start when work items arrive, workers are
provisioned automatically when their entry criteria are met, lineage from prior workers is
injected into each new agent's context, and case completion closes the work item chain.

The Secure Code Review scenario is the reference example at all four layers. At each layer, the
same three channels, the same 9 message types, and the same ledger queries carry through
unchanged. The only thing that changes is what provides the agents.

---

## Reference

**Layered reading:**
- [normative-layer.md](normative-layer.md) — the theory, the worked enterprise examples, the academic lineage
- [normative-channel-layout.md](normative-channel-layout.md) — the 3-channel pattern and `MessageTypePolicy` SPI reference
- [normative-framework.md](normative-framework.md) — body of works navigation
- [examples/normative-layout/](../examples/normative-layout/) — canonical Layer 1 test suite

**Key MCP tools for agents:**
- `register(instanceId, description, capabilities)`
- `send_message(channelName, sender, type, content, correlationId?, inReplyTo?, artefactRefs?, target?, deadline?)`
- `check_messages(channelName, afterId?, limit?, sender?, readerInstanceId?, includeEvents?)`
- `wait_for_reply(channelName, correlationId, timeoutSeconds?)`
- `share_artefact(key, description?, createdBy, content, append?, lastChunk?)`
- `get_artefact(key?, id?)`
- `request_approval(channelName, content, timeoutSeconds?)`
- `list_pending_commitments()`
- `list_stalled_obligations(channelName, olderThanSeconds?)`
- `get_obligation_chain(channelName, correlationId)`
- `get_causal_chain(ledgerEntryId)` — walks causedByEntryId to root, crossing channel boundaries
- `get_obligation_activity(correlationId, limit?)` — complete cross-channel causal narrative: correlationId match + causedByEntryId DAG traversal across channels
- `list_ledger_entries(channelName, typeFilter?, sender?, since?, afterId?, correlationId?, sort?, limit?)`
- `get_telemetry_summary(channelName, since?)`
- `get_channel_timeline(channelName, afterId?, limit?)`
- `register_watchdog(conditionType, targetName, thresholdSeconds?, thresholdCount?, notificationChannel, createdBy)`

**Message type quick reference:**

| Type | Group | Creates obligation? | Terminal? | `allowedTypes` token |
|---|---|---|---|---|
| QUERY | Information | Weak expectation | No | `QUERY` |
| RESPONSE | Information | Discharges QUERY | No | `RESPONSE` |
| STATUS | Information | Extends deadline | No | `STATUS` |
| COMMAND | Obligation | Yes — OPEN | No | `COMMAND` |
| DONE | Obligation | Closes COMMAND | **Yes** | `DONE` |
| FAILURE | Obligation | Closes COMMAND | **Yes** | `FAILURE` |
| DECLINE | Obligation | Closes COMMAND | **Yes** | `DECLINE` |
| HANDOFF | Obligation | Transfers COMMAND | **Yes** | `HANDOFF` |
| EVENT | Telemetry | None | N/A | `EVENT` |

**CommitmentStore states:** `OPEN` → `ACKNOWLEDGED` → `FULFILLED` | `DECLINED` | `FAILED` | `DELEGATED` | `EXPIRED`

**Channel semantics:** `APPEND` | `COLLECT` | `BARRIER` | `EPHEMERAL` | `LAST_WRITE`
