package io.quarkiverse.qhorus.runtime.store.query;

import java.util.List;
import java.util.UUID;

import io.quarkiverse.qhorus.api.message.MessageType;
import io.quarkiverse.qhorus.runtime.message.Message;

public final class MessageQuery {

    private final UUID channelId;
    private final Long afterId;
    private final Integer limit;
    private final List<MessageType> excludeTypes;
    private final String sender;
    private final String target;
    private final String contentPattern;
    private final Long inReplyTo;

    private MessageQuery(Builder b) {
        this.channelId = b.channelId;
        this.afterId = b.afterId;
        this.limit = b.limit;
        this.excludeTypes = b.excludeTypes;
        this.sender = b.sender;
        this.target = b.target;
        this.contentPattern = b.contentPattern;
        this.inReplyTo = b.inReplyTo;
    }

    public static MessageQuery forChannel(UUID channelId) {
        return new Builder().channelId(channelId).build();
    }

    public static MessageQuery poll(UUID channelId, Long afterId, int limit) {
        return new Builder().channelId(channelId).afterId(afterId).limit(limit).build();
    }

    public static MessageQuery replies(UUID channelId, Long inReplyTo) {
        return new Builder().channelId(channelId).inReplyTo(inReplyTo).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public UUID channelId() {
        return channelId;
    }

    public Long afterId() {
        return afterId;
    }

    public Integer limit() {
        return limit;
    }

    public List<MessageType> excludeTypes() {
        return excludeTypes;
    }

    public String sender() {
        return sender;
    }

    public String target() {
        return target;
    }

    public String contentPattern() {
        return contentPattern;
    }

    public Long inReplyTo() {
        return inReplyTo;
    }

    public boolean matches(Message m) {
        if (channelId != null && !channelId.equals(m.channelId)) {
            return false;
        }
        if (afterId != null && m.id != null && m.id <= afterId) {
            return false;
        }
        if (sender != null && !sender.equals(m.sender)) {
            return false;
        }
        if (target != null && !target.equals(m.target)) {
            return false;
        }
        if (inReplyTo != null && !inReplyTo.equals(m.inReplyTo)) {
            return false;
        }
        if (excludeTypes != null && excludeTypes.contains(m.messageType)) {
            return false;
        }
        if (contentPattern != null && (m.content == null
                || !m.content.toLowerCase().contains(contentPattern.toLowerCase()))) {
            return false;
        }
        return true;
    }

    public Builder toBuilder() {
        return new Builder().channelId(channelId).afterId(afterId).limit(limit)
                .excludeTypes(excludeTypes).sender(sender).target(target)
                .contentPattern(contentPattern).inReplyTo(inReplyTo);
    }

    public static final class Builder {
        private UUID channelId;
        private Long afterId;
        private Integer limit;
        private List<MessageType> excludeTypes;
        private String sender;
        private String target;
        private String contentPattern;
        private Long inReplyTo;

        public Builder channelId(UUID v) {
            this.channelId = v;
            return this;
        }

        public Builder afterId(Long v) {
            this.afterId = v;
            return this;
        }

        public Builder limit(Integer v) {
            this.limit = v;
            return this;
        }

        public Builder excludeTypes(List<MessageType> v) {
            this.excludeTypes = v;
            return this;
        }

        public Builder sender(String v) {
            this.sender = v;
            return this;
        }

        public Builder target(String v) {
            this.target = v;
            return this;
        }

        public Builder contentPattern(String v) {
            this.contentPattern = v;
            return this;
        }

        public Builder inReplyTo(Long v) {
            this.inReplyTo = v;
            return this;
        }

        public MessageQuery build() {
            return new MessageQuery(this);
        }
    }
}
