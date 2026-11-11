-- Run on POSTGRES only (febaalert-pg-db). Never run on the Oracle production DB.
CREATE TABLE IF NOT EXISTS email_jobs (
                                          id              BIGSERIAL PRIMARY KEY,
                                          source_alert_id      VARCHAR(64) NOT NULL UNIQUE,   -- SHA-256 of alert content (Oracle has no unique ID)
    alert_name      VARCHAR(50),
    address         VARCHAR(320),
    subject         VARCHAR(500),
    message         TEXT,
    status          VARCHAR(12) NOT NULL DEFAULT 'PENDING',  -- PENDING | PROCESSING | RETRY | SENT | FAILED
    retry_count     INT         NOT NULL DEFAULT 0,
    next_retry_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ
    );

CREATE INDEX IF NOT EXISTS idx_email_jobs_due ON email_jobs (status, next_retry_at);

