CREATE TABLE IF NOT EXISTS resend_acceptances (
    message_id CHAR(36) NOT NULL PRIMARY KEY,
    accepted_at TIMESTAMP(6) NOT NULL
);

CREATE TABLE IF NOT EXISTS resend_delivery_events (
    event_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    message_id CHAR(36) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    event_at TIMESTAMP(6) NOT NULL,
    received_at TIMESTAMP(6) NOT NULL,
    INDEX ix_resend_message_event (message_id, event_at)
);
