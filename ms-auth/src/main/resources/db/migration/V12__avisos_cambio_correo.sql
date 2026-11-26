CREATE TABLE contact_notice_receipts (
    event_id VARCHAR(36) NOT NULL PRIMARY KEY,
    state VARCHAR(10) NOT NULL
);

ALTER TABLE access_codes ADD COLUMN contact_changed_at TIMESTAMP(6) NULL;
