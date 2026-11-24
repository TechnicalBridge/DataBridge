ALTER TABLE debtors ADD COLUMN email_updated_at TIMESTAMP(6) NULL,
                    ADD COLUMN phone_updated_at TIMESTAMP(6) NULL;

CREATE TABLE contact_updates (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    debtor_id BIGINT NOT NULL,
    field VARCHAR(10) NOT NULL,
    actor VARCHAR(10) NOT NULL,
    action VARCHAR(30) NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_contact_update_debtor FOREIGN KEY (debtor_id) REFERENCES debtors(id)
);

CREATE TABLE email_changes (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    debtor_id BIGINT NOT NULL,
    rut VARCHAR(12) NOT NULL,
    new_email VARCHAR(1024) NOT NULL,
    code_hash VARCHAR(64) NOT NULL,
    issued_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    status VARCHAR(12) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    INDEX idx_email_changes_debtor (debtor_id, status),
    CONSTRAINT fk_email_change_debtor FOREIGN KEY (debtor_id) REFERENCES debtors(id)
);
CREATE TABLE contact_rate_limits (
    bucket_key VARCHAR(64) NOT NULL,
    window_id BIGINT NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    PRIMARY KEY (bucket_key, window_id)
);
CREATE TABLE contact_notices (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    old_email VARCHAR(1024) NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) NOT NULL,
    delivered_at TIMESTAMP(6) NULL,
    INDEX idx_contact_notices_pending (delivered_at, next_attempt_at)
);

ALTER TABLE debtors ADD COLUMN contact_version BIGINT NOT NULL DEFAULT 0;
