-- Accepted turns bind a client retry ID and request fingerprint to one assistant message.
CREATE TABLE IF NOT EXISTS conversation_turn (
    conversation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    client_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash BINARY(32) NOT NULL,
    assistant_sequence BIGINT UNSIGNED NOT NULL,
    journey_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    response_snapshot JSON NOT NULL,

    PRIMARY KEY (conversation_id, client_request_id),
    UNIQUE KEY uk_turn_assistant_message (conversation_id, assistant_sequence),
    UNIQUE KEY uk_turn_journey (journey_request_id),
    CONSTRAINT fk_turn_assistant_message FOREIGN KEY (conversation_id, assistant_sequence)
        REFERENCES conversation_message (conversation_id, sequence_no),
    CONSTRAINT fk_turn_journey FOREIGN KEY (journey_request_id) REFERENCES journey_query (request_id),
    CONSTRAINT chk_turn_response_object CHECK (JSON_TYPE(response_snapshot) = 'OBJECT')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_as_cs;
