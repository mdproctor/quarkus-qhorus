-- AgentMessageLedgerEntry subclass table (JPA JOINED inheritance from ledger_entry)
-- Stores Qhorus-specific telemetry fields for EVENT-type messages.
-- Compatible with H2 (dev/test) and PostgreSQL (production).

CREATE TABLE agent_message_ledger_entry (
    id            UUID         NOT NULL,
    channel_id    UUID         NOT NULL,
    message_id    BIGINT       NOT NULL,
    tool_name     VARCHAR(255) NOT NULL,
    duration_ms   BIGINT       NOT NULL,
    token_count   BIGINT,
    context_refs  TEXT,
    source_entity TEXT,
    CONSTRAINT pk_agent_message_ledger_entry PRIMARY KEY (id),
    CONSTRAINT fk_agent_message_ledger_entry FOREIGN KEY (id) REFERENCES ledger_entry (id)
);

CREATE INDEX idx_amle_channel ON agent_message_ledger_entry (channel_id);
CREATE INDEX idx_amle_tool    ON agent_message_ledger_entry (tool_name);
