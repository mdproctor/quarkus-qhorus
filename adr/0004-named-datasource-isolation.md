# 0004 — Named Datasource Isolation: quarkus.datasource.qhorus

Date: 2026-04-22
Status: Accepted

## Context and Problem Statement

The Quarkus AI Agent Ecosystem standardises on named datasources and Hibernate
persistence units matching each library's artifact ID. The embedding
application's default datasource must remain free for its own use. Qhorus was
using the default datasource, which conflicts with Claudony's own database.

## Decision

Qhorus uses the named persistence unit and datasource "qhorus" for all
persistence:

- `quarkus.datasource.qhorus.*` — datasource connection config (provided by consumer)
- `quarkus.hibernate-orm.qhorus.*` — Hibernate ORM persistence unit
- `quarkus.flyway.qhorus.*` — schema migrations

## Ledger Coupling — Revisit Marker

`AgentMessageLedgerEntry extends LedgerEntry` via JPA JOINED inheritance. JPA
requires all entities in an inheritance hierarchy to share a single persistence
unit. `LedgerEntry` (from `quarkus-ledger`, package
`io.quarkiverse.ledger.runtime`) is included in the "qhorus" packages config:

```
quarkus.hibernate-orm.qhorus.datasource=qhorus
quarkus.hibernate-orm.qhorus.packages=\
  io.quarkiverse.qhorus.runtime,\
  io.quarkiverse.ledger.runtime
```

This means ledger base tables (`ledger_entry`, supplements) live in the Qhorus
datasource — not in a separate ledger datasource.

**Revisit trigger:** when `quarkus-ledger` adopts its own named persistence unit
("ledger"), the JPA inheritance must be replaced with a FK-only reference. At
that point `AgentMessageLedgerEntry` becomes a standalone entity in the "qhorus"
PU, and the `LedgerEntryRepository` methods that query the base `ledger_entry`
table will need a cross-datasource strategy.

## Consequences

**Positive:**
- Default datasource remains free for embedding applications.
- All Qhorus config is clearly namespaced; no collision risk.
- `InMemory*Store` alternatives in the testing module are unaffected — they
  bypass the persistence unit entirely.

**Negative:**
- Ledger base tables reside in the "qhorus" datasource, not a separate "ledger"
  datasource.
- Breaking change for consumers: `quarkus.datasource.*` config must be renamed
  to `quarkus.datasource.qhorus.*`. No external users exist at time of decision.

## Embedding App Migration

Replace `quarkus.datasource.*` with `quarkus.datasource.qhorus.*`:

```properties
# Before
quarkus.datasource.db-kind=postgresql
quarkus.datasource.jdbc.url=jdbc:postgresql://localhost/mydb

# After
quarkus.datasource.qhorus.db-kind=postgresql
quarkus.datasource.qhorus.jdbc.url=jdbc:postgresql://localhost/mydb
```
