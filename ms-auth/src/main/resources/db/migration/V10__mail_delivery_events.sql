CREATE TABLE mail_acceptances (
 message_id VARCHAR(255) NOT NULL PRIMARY KEY,
 accepted_at TIMESTAMP(6) NOT NULL
);
CREATE TABLE mail_delivery_events (
 event_key CHAR(64) NOT NULL PRIMARY KEY,
 message_id VARCHAR(255) NOT NULL,
 event_type VARCHAR(20) NOT NULL,
 event_at TIMESTAMP(6) NOT NULL,
 received_at TIMESTAMP(6) NOT NULL,
 INDEX ix_mail_event_message (message_id, event_at),
 INDEX ix_mail_event_type (event_type, event_at)
);
