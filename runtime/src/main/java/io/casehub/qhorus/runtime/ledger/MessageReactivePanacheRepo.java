package io.casehub.qhorus.runtime.ledger;

import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;

/**
 * Minimal reactive Panache repository for {@link MessageLedgerEntry}.
 *
 * <p>
 * Marked {@code @Alternative} — inactive by default. Activate alongside
 * {@link ReactiveMessageLedgerEntryRepository} via {@code quarkus.arc.selected-alternatives}
 * when configuring a reactive datasource. This prevents Hibernate Reactive from booting in
 * applications that only use the blocking {@link MessageLedgerEntryRepository}.
 *
 * <p>
 * Refs #105, Epic #99.
 */
@Alternative
@ApplicationScoped
class MessageReactivePanacheRepo implements PanacheRepositoryBase<MessageLedgerEntry, UUID> {
}
