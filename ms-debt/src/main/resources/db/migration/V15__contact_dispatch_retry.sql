-- Reintentos durables solo cuando no se pudo establecer conexion con ms-auth.
ALTER TABLE contact_jobs
    ADD COLUMN dispatch_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE contact_jobs
    ADD COLUMN next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6);
DROP INDEX ix_job_state ON contact_jobs;
CREATE INDEX ix_job_state ON contact_jobs (state, updated_at, id, next_attempt_at);
