package io.casehub.qhorus.runtime.store;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.casehub.qhorus.api.message.CommitmentState;
import io.casehub.qhorus.runtime.message.Commitment;
import io.smallrye.mutiny.Uni;

public interface ReactiveCommitmentStore {

    Uni<Commitment> save(Commitment commitment);

    Uni<Optional<Commitment>> findById(UUID commitmentId);

    Uni<Optional<Commitment>> findByCorrelationId(String correlationId);

    Uni<List<Commitment>> findOpenByObligor(String obligor, UUID channelId);

    Uni<List<Commitment>> findOpenByRequester(String requester, UUID channelId);

    Uni<List<Commitment>> findByState(CommitmentState state, UUID channelId);

    Uni<List<Commitment>> findExpiredBefore(Instant cutoff);

    Uni<List<Commitment>> findAllOpen();

    Uni<Void> deleteById(UUID commitmentId);

    Uni<Long> deleteExpiredBefore(Instant cutoff);
}
