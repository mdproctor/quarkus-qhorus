# Agent Communication Examples

Demonstrates Qhorus's 9-type message taxonomy with real LLM agents. Each example
shows a concrete enterprise failure mode and how typed messages prevent it.

---

## Quick Start

```bash
# From the quarkus-qhorus root directory
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn test -pl examples/agent-communication -Dno-format
```

**First run:** downloads ~700MB (`Llama-3.2-1B-Instruct` model) from HuggingFace
and caches in `~/.jlama/`. Subsequent runs use the cache — no network required.

**Requirements:** Java 21+, Maven. No Docker, no API key, no external process.

The inference engine is [Jlama](https://github.com/tjake/Jlama) — pure Java, uses
the Vector API for SIMD acceleration. No native code.

> **Known issue (Quarkus 3.32.2):** The tests currently fail at bootstrap with
> `Unsupported value type: [ALL-UNNAMED]` — a bug in Quarkus 3.32.2's bootstrap
> JSON serializer when handling JVM module opens args added by the Jlama extension.
> Tracked in garden entry GE-20260423-878486. To run examples now, use the
> [Ollama provider](#switching-providers) instead.

---

## What Each Example Demonstrates

### Example 1: Code Review Pipeline (`CodeReviewPipelineTest`)

**Real failure mode prevented:** Orchestrator delegates ambiguously; worker
executes when it should have asked first, or asks when it should have acted.

**Flow:**
```
Orchestrator  →  COMMAND   →  Worker    (delegate the review)
Worker        →  STATUS    →  (working...)
Worker        →  QUERY     →  (needs clarification before completing)
Orchestrator  →  RESPONSE  →  Worker    (answers the question)
Worker        →  DONE      →  (review complete with findings)
```

**What it validates:** The LLM correctly chooses COMMAND (not QUERY) for delegation,
and QUERY (not another COMMAND) when it needs information before proceeding.

---

### Example 2: Refund Authorisation (`RefundAuthorisationTest`)

**Real failure mode prevented:** Agent assumes refund authority and issues a
£50,000 refund on a £500 order because "handle the refund" was ambiguous.

**Flow:**
```
Orchestrator  →  COMMAND   →  Worker    ("process refund for order #4521")
Worker        →  QUERY     →             (asks for approved amount before acting)
Orchestrator  →  RESPONSE  →  Worker    ("10% goodwill, max £50")
Worker        →  DONE      →             (refund processed within bounds)
```

**What it validates:** A competent agent asks (QUERY) before acting (COMMAND) when
the scope of authority is unclear. The QUERY→RESPONSE cycle is the natural pattern
for clarification before execution.

---

### Example 3: Out-of-Scope Decline (`OutOfScopeDeclineTest`)

**Real failure mode prevented:** Agent attempts a task it cannot complete and fails
silently, or worse — attempts it and produces incorrect output with no indication it
was outside scope.

**Two sub-cases:**
1. Task outside capabilities → DECLINE (will not attempt, reason required)
2. Task attempted but blocked → FAILURE (tried, could not complete)

**What it validates:** DECLINE and FAILURE are categorically distinct:
- `DECLINE`: "I will not attempt this" — no side effects occurred
- `FAILURE`: "I tried but could not complete" — partial execution may have occurred

Both require non-empty content explaining why.

---

### Classification Accuracy Baseline (`ClassificationAccuracyTest`)

Measures whether `Llama-3.2-1B-Instruct` can correctly classify message types
from natural language context. Target: ≥ 80% per category.

This validates the taxonomy's granularity — if LLMs consistently confuse two types,
they should be merged; if they split one type multiple ways, it should be split.

Results are printed to stdout. If accuracy falls below 80%, switch to a larger model
(see [Switching Providers](#switching-providers) below).

---

## Switching Providers

The default is Jlama with `Llama-3.2-1B-Instruct-Jlama-Q4`. To use a different backend:

### Ollama (faster, requires `ollama serve`)

```bash
# Install and start
brew install ollama
ollama pull gemma3:1b   # or llama3.2:3b for better accuracy
ollama serve
```

In `pom.xml`: comment out `quarkus-langchain4j-jlama`, uncomment `quarkus-langchain4j-ollama`.

In `application.properties`: uncomment the `# Ollama` section.

### Anthropic Claude Haiku (best accuracy, requires API key)

```bash
export ANTHROPIC_API_KEY=sk-ant-...
```

In `pom.xml`: comment out `quarkus-langchain4j-jlama`, uncomment `quarkus-langchain4j-anthropic`.

In `application.properties`: uncomment the `# Anthropic` section.

### Larger Jlama model (better accuracy, same setup)

In `application.properties`, change the model name:

```properties
# More accurate, ~2.3GB
quarkus.langchain4j.jlama.chat-model.model-name=tjake/Phi-3.5-mini-instruct-Jlama-Q4
```

Available Jlama models (all pre-quantized Q4, from the `tjake/` HuggingFace org):

| Model | Size | Notes |
|---|---|---|
| `tjake/Llama-3.2-1B-Instruct-Jlama-Q4` | ~700MB | Default — good balance |
| `tjake/Phi-3.5-mini-instruct-Jlama-Q4` | ~2.3GB | Better accuracy |
| `tjake/Mistral-7B-Instruct-v0.3-Jlama-Q4` | ~4GB | Best accuracy |

---

## Theoretical Foundation

The 9-type taxonomy is derived from a four-layer normative framework:

| Layer | Framework | What it defines |
|---|---|---|
| Normative | Speech act theory + deontic logic | What each message *means* — what obligation it creates or discharges |
| Social commitment | Singh's `C(debtor, creditor, antecedent, consequent)` | Who owes what to whom |
| Temporal | Deadline, ordering, duration | When obligations must be fulfilled |
| Enforcement | Defeasible rules | What happens when obligations are not met |

See `docs/superpowers/specs/2026-04-23-message-type-redesign-design.md` for the
full design specification including the four-layer semantics table.
