package io.casehub.qhorus.runtime.store;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.store.query.MessageQuery;

public interface MessageStore {
    Message put(Message message);

    Optional<Message> find(Long id);

    List<Message> scan(MessageQuery query);

    void deleteAll(UUID channelId);

    void delete(Long id);

    int countByChannel(UUID channelId);

    Map<UUID, Long> countAllByChannel();

    List<String> distinctSendersByChannel(UUID channelId, MessageType excludedType);
}
