package io.quarkiverse.qhorus.runtime.store;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.quarkiverse.qhorus.runtime.message.Message;
import io.quarkiverse.qhorus.runtime.message.MessageType;
import io.quarkiverse.qhorus.runtime.store.query.MessageQuery;
import io.smallrye.mutiny.Uni;

public interface ReactiveMessageStore {
    Uni<Message> put(Message message);

    Uni<Optional<Message>> find(Long id);

    Uni<List<Message>> scan(MessageQuery query);

    Uni<Void> deleteAll(UUID channelId);

    Uni<Void> delete(Long id);

    Uni<Integer> countByChannel(UUID channelId);

    Uni<Map<UUID, Long>> countAllByChannel();

    Uni<List<String>> distinctSendersByChannel(UUID channelId, MessageType excludedType);
}
