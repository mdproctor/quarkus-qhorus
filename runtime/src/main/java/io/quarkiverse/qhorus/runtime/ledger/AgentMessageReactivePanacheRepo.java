package io.quarkiverse.qhorus.runtime.ledger;

import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;

/**
 * Minimal reactive Panache repository for {@link AgentMessageLedgerEntry}.
 *
 * <p>
 * Marked {@code @Alternative} so it is not active by default — consumers must select it
 * explicitly via {@code quarkus.arc.selected-alternatives} when they configure a reactive
 * datasource. This prevents Hibernate Reactive from booting in applications that only use
 * the blocking {@link AgentMessageLedgerEntryRepository}.
 *
 * <p>
 * Kept package-private and injected into {@link ReactiveAgentMessageLedgerEntryRepository}.
 *
 * <p>
 * Refs #68.
 */
@Alternative
@ApplicationScoped
class AgentMessageReactivePanacheRepo implements PanacheRepositoryBase<AgentMessageLedgerEntry, UUID> {
}
