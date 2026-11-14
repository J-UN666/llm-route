-- MySQL 8.4. Run against the llmroute database with the migration account.
-- DATETIME columns ending in _utc store UTC. Business time is Asia/Shanghai.
-- Each statement is retryable after a partially completed deployment.

CREATE TABLE IF NOT EXISTS conversation (
    conversation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_token_hash BINARY(32) NOT NULL COMMENT 'SHA-256 of an anonymous session token',
    origin_place_id VARCHAR(96) NULL COMMENT 'Stable ID in the campus JSON data',
    destination_place_id VARCHAR(96) NULL COMMENT 'Stable destination ID',
    departure_at_utc DATETIME(3) NULL,
    preference ENUM('RECOMMENDED', 'LESS_WALKING', 'FEWER_TRANSFERS')
        NOT NULL DEFAULT 'RECOMMENDED',
    state ENUM('COLLECTING', 'AWAITING_CONFIRMATION', 'READY', 'CLOSED')
        NOT NULL DEFAULT 'COLLECTING',
    pending_confirmation JSON NULL COMMENT 'Field being confirmed and candidate choices',
    state_version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    next_message_seq BIGINT UNSIGNED NOT NULL DEFAULT 1,
    created_at_utc DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    updated_at_utc DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),

    PRIMARY KEY (conversation_id),
    UNIQUE KEY uk_conversation_owner (conversation_id, owner_token_hash),
    KEY idx_conversation_owner_updated (owner_token_hash, updated_at_utc),
    CONSTRAINT chk_conversation_message_seq CHECK (next_message_seq > 0),
    CONSTRAINT chk_conversation_pending_object CHECK (
        pending_confirmation IS NULL OR JSON_TYPE(pending_confirmation) = 'OBJECT'
    ),
    CONSTRAINT chk_conversation_pending_state CHECK (
        (state = 'AWAITING_CONFIRMATION' AND pending_confirmation IS NOT NULL)
        OR (state <> 'AWAITING_CONFIRMATION' AND pending_confirmation IS NULL)
    ),
    CONSTRAINT chk_conversation_ready CHECK (
        state <> 'READY'
        OR (origin_place_id IS NOT NULL AND destination_place_id IS NOT NULL
            AND departure_at_utc IS NOT NULL)
    ),
    CONSTRAINT chk_conversation_time CHECK (updated_at_utc >= created_at_utc)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs
    COMMENT='Current conditions and confirmation state of an anonymous conversation';

CREATE TABLE IF NOT EXISTS conversation_message (
    message_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    conversation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sequence_no BIGINT UNSIGNED NOT NULL,
    role ENUM('USER', 'ASSISTANT', 'TOOL') NOT NULL,
    content MEDIUMTEXT NULL,
    metadata JSON NULL COMMENT 'Sanitized model/tool call IDs, arguments and results',
    created_at_utc DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),

    PRIMARY KEY (message_id),
    UNIQUE KEY uk_message_conversation_sequence (conversation_id, sequence_no),
    CONSTRAINT fk_message_conversation FOREIGN KEY (conversation_id)
        REFERENCES conversation (conversation_id),
    CONSTRAINT chk_message_sequence CHECK (sequence_no > 0),
    CONSTRAINT chk_message_metadata CHECK (
        metadata IS NULL OR JSON_TYPE(metadata) = 'OBJECT'
    ),
    CONSTRAINT chk_message_payload CHECK (
        (content IS NOT NULL AND CHAR_LENGTH(TRIM(content)) > 0)
        OR metadata IS NOT NULL
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs
    COMMENT='Ordered user, assistant and tool messages within a conversation';

CREATE TABLE IF NOT EXISTS journey_query (
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    conversation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    owner_token_hash BINARY(32) NOT NULL,
    conversation_version BIGINT UNSIGNED NULL COMMENT 'Conversation version when this query started',
    entry_point ENUM('FORM', 'ASSISTANT') NOT NULL,
    origin_place_id VARCHAR(96) NOT NULL,
    destination_place_id VARCHAR(96) NOT NULL,
    departure_at_utc DATETIME(3) NOT NULL,
    preference ENUM('RECOMMENDED', 'LESS_WALKING', 'FEWER_TRANSFERS') NOT NULL,
    campus_data_version VARCHAR(64) NOT NULL,
    provider VARCHAR(32) NOT NULL COMMENT 'Actual city route provider, currently AMAP',
    request_snapshot JSON NOT NULL COMMENT 'Sanitized conditions and actual provider request evidence',
    status ENUM('RUNNING', 'SUCCEEDED', 'NO_ROUTE', 'FAILED') NOT NULL DEFAULT 'RUNNING',
    result_snapshot JSON NULL COMMENT 'JourneyPlan response including actual plans and sources',
    error_code VARCHAR(64) NULL,
    error_message VARCHAR(1000) NULL,
    started_at_utc DATETIME(3) NOT NULL DEFAULT (UTC_TIMESTAMP(3)),
    completed_at_utc DATETIME(3) NULL,

    PRIMARY KEY (request_id),
    KEY idx_query_conversation_started (conversation_id, started_at_utc),
    KEY idx_query_owner_started (owner_token_hash, started_at_utc),
    KEY idx_query_status_started (status, started_at_utc),
    KEY idx_query_conversation_owner (conversation_id, owner_token_hash),
    CONSTRAINT fk_query_conversation_owner FOREIGN KEY (conversation_id, owner_token_hash)
        REFERENCES conversation (conversation_id, owner_token_hash),
    CONSTRAINT chk_query_conversation_version CHECK (
        (conversation_id IS NULL AND conversation_version IS NULL)
        OR (conversation_id IS NOT NULL AND conversation_version IS NOT NULL)
    ),
    CONSTRAINT chk_query_assistant_conversation CHECK (
        entry_point <> 'ASSISTANT' OR conversation_id IS NOT NULL
    ),
    CONSTRAINT chk_query_request_object CHECK (JSON_TYPE(request_snapshot) = 'OBJECT'),
    CONSTRAINT chk_query_result_object CHECK (
        result_snapshot IS NULL OR JSON_TYPE(result_snapshot) = 'OBJECT'
    ),
    CONSTRAINT chk_query_completion CHECK (
        (status = 'RUNNING' AND completed_at_utc IS NULL AND result_snapshot IS NULL
            AND error_code IS NULL AND error_message IS NULL)
        OR (status IN ('SUCCEEDED', 'NO_ROUTE') AND completed_at_utc IS NOT NULL
            AND result_snapshot IS NOT NULL AND error_code IS NULL AND error_message IS NULL)
        OR (status = 'FAILED' AND completed_at_utc IS NOT NULL
            AND result_snapshot IS NULL AND error_code IS NOT NULL)
    ),
    CONSTRAINT chk_query_time CHECK (
        completed_at_utc IS NULL OR completed_at_utc >= started_at_utc
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs
    COMMENT='Immutable query inputs and the recorded outcome of a real journey query';
