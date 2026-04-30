package io.casehub.qhorus.runtime.store.jpa;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import io.casehub.qhorus.runtime.message.Message;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;

/**
 * Minimal reactive Panache repository for {@link Message}.
 *
 * <p>
 * Marked {@code @Alternative} so it is not active by default — consumers must select it
 * explicitly via {@code quarkus.arc.selected-alternatives} when they configure a reactive
 * datasource. This prevents Hibernate Reactive from booting in applications that only use
 * the blocking {@link JpaMessageStore}.
 *
 * <p>
 * Kept package-private and injected into {@link ReactiveJpaMessageStore}.
 *
 * <p>
 * Note: {@link Message} uses {@code Long} as its primary key.
 *
 * <p>
 * Refs #74.
 */
@Alternative
@ApplicationScoped
class MessageReactivePanacheRepo implements PanacheRepositoryBase<Message, Long> {
}
