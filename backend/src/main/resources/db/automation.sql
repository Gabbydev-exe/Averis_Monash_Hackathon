-- Apply after persistence.sql. Stores durable machine assessments and queue leases.
CREATE TABLE IF NOT EXISTS email_assessments (
  email_id VARCHAR(64) NOT NULL,
  revision VARCHAR(36) NOT NULL,
  evaluation_status VARCHAR(20) NOT NULL,
  review_reason VARCHAR(50),
  defect_fields TEXT NOT NULL,
  match_percentage DECIMAL(5,2),
  explanation TEXT,
  PRIMARY KEY (email_id, revision)
);

CREATE TABLE IF NOT EXISTS verification_jobs (
  email_id VARCHAR(64) PRIMARY KEY,
  source_revision VARCHAR(36) NOT NULL,
  lease_token VARCHAR(36),
  lease_until TIMESTAMP(6),
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMP(6),
  last_error TEXT
);
