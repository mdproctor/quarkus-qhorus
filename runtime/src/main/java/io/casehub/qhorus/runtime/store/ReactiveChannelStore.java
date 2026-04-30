package io.casehub.qhorus.runtime.store;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.store.query.ChannelQuery;
import io.smallrye.mutiny.Uni;

public interface ReactiveChannelStore {
    Uni<Channel> put(Channel channel);

    Uni<Optional<Channel>> find(UUID id);

    Uni<Optional<Channel>> findByName(String name);

    Uni<List<Channel>> scan(ChannelQuery query);

    Uni<Void> delete(UUID id);
}
