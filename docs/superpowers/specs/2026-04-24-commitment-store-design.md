# CommitmentStore Design Specification
**Date:** 2026-04-24
**Status:** Draft — awaiting user approval
**Replaces:** `PendingReply` / `PendingReplyStore` entirely (no migration needed — no external consumers)
**Theoretical foundation:** ADR-0005 §2 (Singh social commitment semantics, Layer 2)

---

## 1. Problem Statement

`PendingReply` tracks only one obligation pattern: QUERY→RESPONSE (previously REQUEST→RESPONSE). It has no concept of obligation state, debtor/creditor identity, or the full lifecycle (DECLINED, FAILED, HANDOFF delegation). With the 9-type taxonomy, agents send COMMAND, DECLINE, FAILURE, HANDOFF — none of these update any persistence layer. The obligation exists only in the LLM's context, which is unreliable.

CommitmentStore generalises `PendingReply` into a full obligation lifecycle tracker. `wait_for_reply` is migrated to use it. `PendingReply` and all its variants are deleted.

---

## 2. State Machine

```
OPEN ──► ACKNOWLEDGED ──► FULFILLED
  │                    └──► DECLINED
  │                    └──► FAILED
  │                    └──► DELEGATED
  └──────────────────────► EXPIRED
```

| State | Triggered by | Terminal? |
|---|---|---|
| `OPEN` | QUERY or COMMAND sent | No |
| `ACKNOWLEDGED` | STATUS received from obligor | No |
| `FULFILLED` | RESPONSE (QUERY) or DONE (COMMAND) received | Yes |
| `DECLINED` | DECLINE received | Yes |
| `FAILED` | FAILURE received | Yes |
| `DELEGATED` | HANDOFF received | Yes |
| `EXPIRED` | Deadline exceeded, infrastructure-generated | Yes |

`CommitmentState.isTerminal()`: true for FULFILLED, DECLINED, FAILED, DELEGATED, EXPIRED.

All state transitions are idempotent and silent-no-op if the Commitment is not found or already terminal.

---

## 3. `Commitment` Entity

Replaces `PendingReply`. The entity `id` is the same UUID as `Message.commitmentId` — they are the same value, not a join.

```java
@Entity
@Table(name = "commitment",
       uniqueConstraints = @UniqueConstraint(name = "uq_commitment_corr_id",
                                             columnNames = "correlation_id"))
public class Commitment extends PanacheEntityBase {

    @Id
    public UUID id;                        // = Message.commitmentId

    @Column(name = "correlation_id", nullable = false)
    public String correlationId;           // business key, unique per active obligation

    @Column(name = "channel_id", nullable = false)
    public UUID channelId;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", nullable = false)
    public MessageType messageType;        // QUERY or COMMAND only

    @Column(name = "requester", nullable = false)
    public String requester;               // sender of QUERY/COMMAND (Singh: creditor)

    @Column(name = "obligor")
    public String obligor;                 // target of QUERY/COMMAND (Singh: debtor); null = broadcast

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    public CommitmentState state = CommitmentState.OPEN;

    @Column(name = "expires_at")
    public Instant expiresAt;             // deadline (from Message.deadline)

    @Column(name = "acknowledged_at")
    public Instant acknowledgedAt;        // when first STATUS received

    @Column(name = "resolved_at")
    public Instant resolvedAt;            // when terminal state reached

    @Column(name = "delegated_to")
    public String delegatedTo;            // populated on HANDOFF — identity of new obligor

    @Column(name = "parent_commitment_id")
    public UUID parentCommitmentId;       // links delegated child to its parent

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }
}
```

**HANDOFF delegation chain:** HANDOFF transitions the original Commitment to DELEGATED and creates a new child Commitment with the same `correlationId`, new `id`, new `obligor` = HANDOFF target, and `parentCommitmentId` pointing to the original. The requester's `wait_for_reply` polling by `correlationId` continues to work through the delegation.

---

## 4. `CommitmentStore` Interface

Thin CRUD + query layer. No domain logic.

```java
public interface CommitmentStore {

    Commitment save(Commitment commitment);

    Optional<Commitment> findById(UUID commitmentId);

    Optional<Commitment> findByCorrelationId(String correlationId);

    /** All non-terminal commitments where this agent is the obligor (what do I owe?). */
    List<Commitment> findOpenByObligor(String obligor, UUID channelId);

    /** All non-terminal commitments where this agent is the requester (what's owed to me?). */
    List<Commitment> findOpenByRequester(String requester, UUID channelId);

    /** All commitments in a given state on a channel. */
    List<Commitment> findByState(CommitmentState state, UUID channelId);

    /** All OPEN or ACKNOWLEDGED commitments whose expiresAt is before the cutoff. */
    List<Commitment> findExpiredBefore(Instant cutoff);

    void deleteById(UUID commitmentId);

    long deleteExpiredBefore(Instant cutoff);
}
```

`ReactiveCommitmentStore` mirrors this interface with `Uni<T>` return types.

---

## 5. `CommitmentService` — State Transitions

Owns all state machine logic. Injected by MessageService. CommitmentStore is the SPI seam.

```java
@ApplicationScoped
public class CommitmentService {

    @Inject CommitmentStore store;

    @Transactional
    public Commitment open(UUID commitmentId, String correlationId, UUID channelId,
                           MessageType type, String requester, String obligor,
                           Instant expiresAt) { ... }

    @Transactional
    public Optional<Commitment> acknowledge(String correlationId) {
        return transition(correlationId, CommitmentState.ACKNOWLEDGED, c -> {
            if (c.acknowledgedAt == null) c.acknowledgedAt = Instant.now();
        });
    }

    @Transactional
    public Optional<Commitment> fulfill(String correlationId) {
        return transition(correlationId, CommitmentState.FULFILLED,
                c -> c.resolvedAt = Instant.now());
    }

    @Transactional
    public Optional<Commitment> decline(String correlationId) {
        return transition(correlationId, CommitmentState.DECLINED,
                c -> c.resolvedAt = Instant.now());
    }

    @Transactional
    public Optional<Commitment> fail(String correlationId) {
        return transition(correlationId, CommitmentState.FAILED,
                c -> c.resolvedAt = Instant.now());
    }

    /** HANDOFF: transitions original to DELEGATED, creates child commitment for target. */
    @Transactional
    public Optional<Commitment> delegate(String correlationId, String delegatedTo) {
        return store.findByCorrelationId(correlationId)
                .filter(c -> !c.state.isTerminal())
                .map(c -> {
                    c.state = CommitmentState.DELEGATED;
                    c.delegatedTo = delegatedTo;
                    c.resolvedAt = Instant.now();
                    store.save(c);
                    // Child commitment inherits same correlationId for wait_for_reply continuity
                    open(UUID.randomUUID(), correlationId, c.channelId,
                         c.messageType, c.requester, delegatedTo, c.expiresAt);
                    return c;
                });
    }

    /** Called by scheduler. Transitions overdue OPEN/ACKNOWLEDGED commitments to EXPIRED. */
    @Transactional
    public int expireOverdue() {
        List<Commitment> overdue = store.findExpiredBefore(Instant.now());
        overdue.forEach(c -> {
            c.state = CommitmentState.EXPIRED;
            c.resolvedAt = Instant.now();
            store.save(c);
        });
        return overdue.size();
    }

    private Optional<Commitment> transition(String correlationId,
                                             CommitmentState target,
                                             Consumer<Commitment> update) {
        return store.findByCorrelationId(correlationId)
                .filter(c -> !c.state.isTerminal())
                .map(c -> { update.accept(c); c.state = target; return store.save(c); });
    }
}
```

---

## 6. Integration with `MessageService`

After persisting the message in `send()`, trigger the commitment transition:

```java
switch (msg.messageType) {
    case QUERY, COMMAND ->
        commitmentService.open(msg.commitmentId, msg.correlationId, msg.channelId,
                               msg.messageType, msg.sender, msg.target, msg.deadline);
    case STATUS ->
        commitmentService.acknowledge(msg.correlationId);
    case RESPONSE, DONE ->
        commitmentService.fulfill(msg.correlationId);
    case DECLINE ->
        commitmentService.decline(msg.correlationId);
    case FAILURE ->
        commitmentService.fail(msg.correlationId);
    case HANDOFF ->
        commitmentService.delegate(msg.correlationId, msg.target);
    case EVENT -> { /* no commitment effect */ }
}
```

All transitions are silent no-ops when `correlationId` is null (STATUS/DECLINE/etc. sent without a prior QUERY/COMMAND) or when the Commitment is already terminal.

---

## 7. Integration with `wait_for_reply`

The polling loop replaces PendingReply operations with Commitment state checks:

| Old (PendingReply) | New (CommitmentStore) |
|---|---|
| `registerPendingReply(correlationId, ...)` | Already created by `commitmentService.open()` in `send()` — no separate registration needed |
| `pendingReplyExists(correlationId)` → false = cancelled | `findByCorrelationId` returns empty = cancelled (deleted by `cancel_wait`) |
| Poll until `findResponseByCorrelationId` returns message | Poll until Commitment state is not OPEN/ACKNOWLEDGED |
| `deletePendingReply(correlationId)` on success/timeout | Commitment record is **kept** — not deleted; audit trail preserved |

**Commitment state → `wait_for_reply` behaviour:**
- `OPEN` / `ACKNOWLEDGED` → keep polling
- `FULFILLED` → fetch the response/done message by `correlationId` and return it
- `DECLINED` → return declined result with content from the DECLINE message
- `FAILED` → return failed result with content from the FAILURE message
- `DELEGATED` → the child Commitment (same `correlationId`) becomes the new active obligation; polling continues transparently
- `EXPIRED` → return timeout result
- Not found → return cancelled result

**`cancel_wait`** — deletes the Commitment (`deleteById`) instead of deleting PendingReply. The polling loop exits via "not found" path.

**`list_pending_waits`** — queries `findOpenByRequester` instead of `PendingReply.listAll()`.

---

## 8. New MCP Tools

**`list_my_commitments`**

```
Parameters:
  channel_name : String                                    required
  sender       : String                                    required
  role         : "obligor" | "requester" | "both"         default: "both"
  state        : CommitmentState filter                    optional

Returns: commitments with id, correlationId, messageType, counterparty,
         state, deadline, createdAt, resolvedAt
```

**`get_commitment`**

```
Parameters:
  correlation_id : String   (use this or commitment_id)
  commitment_id  : UUID

Returns: single Commitment with all fields including parentCommitmentId chain
```

Both tools are read-only. State transitions happen automatically via `send_message`.

---

## 9. What Is Deleted

| File | Replaced by |
|---|---|
| `PendingReply.java` | `Commitment.java` |
| `PendingReplyStore.java` | `CommitmentStore.java` |
| `JpaPendingReplyStore.java` | `JpaCommitmentStore.java` |
| `ReactivePendingReplyStore.java` | `ReactiveCommitmentStore.java` |
| `ReactiveJpaPendingReplyStore.java` | `ReactiveJpaCommitmentStore.java` |
| `InMemoryPendingReplyStore.java` (testing/) | `InMemoryCommitmentStore.java` |
| `InMemoryReactivePendingReplyStore.java` (testing/) | `InMemoryReactiveCommitmentStore.java` |
| `PendingReplyCleanupScheduler.java` | `CommitmentService.expireOverdue()` + scheduler |

---

## 10. `InMemoryCommitmentStore` (testing/)

```java
@Alternative @Priority(1) @ApplicationScoped
public class InMemoryCommitmentStore implements CommitmentStore {

    private final Map<UUID, Commitment> byId = new LinkedHashMap<>();
    private final Map<String, UUID> byCorrelationId = new LinkedHashMap<>();

    @Override
    public Commitment save(Commitment c) {
        if (c.id == null) c.id = UUID.randomUUID();
        if (c.createdAt == null) c.createdAt = Instant.now();
        byId.put(c.id, c);
        byCorrelationId.put(c.correlationId, c.id);
        return c;
    }

    // findById, findByCorrelationId, findOpenByObligor, findOpenByRequester,
    // findByState, findExpiredBefore — all stream over byId.values()
    // deleteById removes from both maps
    // deleteExpiredBefore removes OPEN/ACKNOWLEDGED entries before cutoff

    public void clear() { byId.clear(); byCorrelationId.clear(); }
}
```

`InMemoryReactiveCommitmentStore` delegates to `InMemoryCommitmentStore` via `Uni.createFrom().item(...)` wrappers, same pattern as all other reactive InMemory stores.

---

## 11. Testing Strategy

- **Contract tests** — `CommitmentStoreContractTest` (abstract) with `InMemoryCommitmentStoreTest` and `JpaCommitmentStoreTest` runners, following the `*StoreContractTest` pattern already established
- **CommitmentService tests** — unit tests using `InMemoryCommitmentStore` directly; verify state machine transitions and idempotency
- **wait_for_reply integration** — existing `WaitForReplyTest` updated to verify DECLINED/FAILED/DELEGATED paths, not just FULFILLED
- **`list_my_commitments` tool test** — new `@QuarkusTest` verifying QUERY/COMMAND/DONE lifecycle is reflected in tool output

---

*Spec ready for review. Once approved, proceed to implementation plan.*
