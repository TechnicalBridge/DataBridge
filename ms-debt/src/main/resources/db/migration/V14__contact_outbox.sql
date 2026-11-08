CREATE TABLE contact_jobs (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 job_key VARCHAR(160) NOT NULL UNIQUE,
 debt_id BIGINT NOT NULL,
 kind VARCHAR(12) NOT NULL,
 campaign_id BIGINT NULL,
 touch_number SMALLINT NULL,
 installment_id BIGINT NULL,
 state VARCHAR(12) NOT NULL,
 destination_hash CHAR(64) NOT NULL,
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_job_debt FOREIGN KEY (debt_id) REFERENCES debts(id) ON DELETE CASCADE,
 CONSTRAINT fk_job_campaign FOREIGN KEY (campaign_id) REFERENCES campaigns(id),
 CONSTRAINT fk_job_installment FOREIGN KEY (installment_id) REFERENCES installments(id) ON DELETE CASCADE,
 INDEX ix_job_state (state, updated_at, id)
);
