-- Qhorus initial schema — Phase 1: Core data model
-- Compatible with H2 (test) and PostgreSQL (production)
-- Table order respects FK dependencies.

-- -------------------------------------------------------------------------
-- Channel
-- -------------------------------------------------------------------------
CREATE TABLE channel (
    id               UUID         NOT NULL,
    name             VARCHAR(255) NOT NULL,
    description      VARCHAR(1000),
    semantic         VARCHAR(50)  NOT NULL,
    barrier_contributors TEXT,
    created_at       TIMESTAMP    NOT NULL,
    last_activity_at TIMESTAMP    NOT NULL,
    CONSTRAINT pk_channel PRIMARY KEY (id),
    CONSTRAINT uq_channel_name UNIQUE (name)
);

-- -------------------------------------------------------------------------
-- Instance (agent presence registry)
-- -------------------------------------------------------------------------
CREATE TABLE instance (
    id                  UUID         NOT NULL,
    instance_id         VARCHAR(255) NOT NULL,
    description         VARCHAR(1000),
    status              VARCHAR(50)  NOT NULL DEFAULT 'online',
    claudony_session_id VARCHAR(255),
    session_token       VARCHAR(255),
    last_seen           TIMESTAMP    NOT NULL,
    registered_at       TIMESTAMP    NOT NULL,
    CONSTRAINT pk_instance PRIMARY KEY (id),
    CONSTRAINT uq_instance_instance_id UNIQUE (instance_id)
);

-- -------------------------------------------------------------------------
-- Capability (capability tags per instance)
-- -------------------------------------------------------------------------
CREATE TABLE capability (
    id          UUID         NOT NULL,
    instance_id UUID         NOT NULL,
    tag         VARCHAR(255) NOT NULL,
    CONSTRAINT pk_capability PRIMARY KEY (id),
    CONSTRAINT fk_capability_instance FOREIGN KEY (instance_id) REFERENCES instance(id)
);

-- -------------------------------------------------------------------------
-- Message (sequence PK preserves ordering)
-- -------------------------------------------------------------------------
CREATE SEQUENCE message_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE message (
    id              BIGINT       NOT NULL,
    channel_id      UUID         NOT NULL,
    sender          VARCHAR(255) NOT NULL,
    message_type    VARCHAR(50)  NOT NULL,
    content         TEXT,
    correlation_id  VARCHAR(255),
    in_reply_to     BIGINT,
    reply_count     INT          NOT NULL DEFAULT 0,
    artefact_refs   TEXT,
    created_at      TIMESTAMP    NOT NULL,
    CONSTRAINT pk_message PRIMARY KEY (id),
    CONSTRAINT fk_message_channel FOREIGN KEY (channel_id) REFERENCES channel(id),
    CONSTRAINT fk_message_reply FOREIGN KEY (in_reply_to) REFERENCES message(id)
);

-- -------------------------------------------------------------------------
-- Pending Reply (correlation ID tracking for wait_for_reply — Phase 4)
-- -------------------------------------------------------------------------
CREATE TABLE pending_reply (
    id              UUID         NOT NULL,
    correlation_id  VARCHAR(255) NOT NULL,
    instance_id     UUID,
    channel_id      UUID,
    expires_at      TIMESTAMP,
    CONSTRAINT pk_pending_reply PRIMARY KEY (id),
    CONSTRAINT uq_pending_reply_corr_id UNIQUE (correlation_id),
    CONSTRAINT fk_pending_reply_instance FOREIGN KEY (instance_id) REFERENCES instance(id),
    CONSTRAINT fk_pending_reply_channel FOREIGN KEY (channel_id) REFERENCES channel(id)
);

-- -------------------------------------------------------------------------
-- Shared Data (artefact store)
-- -------------------------------------------------------------------------
CREATE TABLE shared_data (
    id          UUID         NOT NULL,
    data_key    VARCHAR(255) NOT NULL,
    content     TEXT,
    created_by  VARCHAR(255),
    description VARCHAR(1000),
    complete    BOOLEAN      NOT NULL DEFAULT TRUE,
    size_bytes  BIGINT       NOT NULL DEFAULT 0,
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL,
    CONSTRAINT pk_shared_data PRIMARY KEY (id),
    CONSTRAINT uq_shared_data_key UNIQUE (data_key)
);

-- -------------------------------------------------------------------------
-- Artefact Claim (claim/release lifecycle for GC)
-- -------------------------------------------------------------------------
CREATE TABLE artefact_claim (
    id          UUID      NOT NULL,
    artefact_id UUID      NOT NULL,
    instance_id UUID      NOT NULL,
    claimed_at  TIMESTAMP NOT NULL,
    CONSTRAINT pk_artefact_claim PRIMARY KEY (id),
    CONSTRAINT fk_artefact_claim_data FOREIGN KEY (artefact_id) REFERENCES shared_data(id),
    CONSTRAINT fk_artefact_claim_instance FOREIGN KEY (instance_id) REFERENCES instance(id)
);
