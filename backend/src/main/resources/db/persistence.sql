-- Apply once to shipping_db BEFORE deploying this version. Additive: preserves source tables.
CREATE TABLE IF NOT EXISTS email_extractions (
  email_id VARCHAR(64) PRIMARY KEY,
  revision VARCHAR(36) NOT NULL,
  model_name VARCHAR(200) NOT NULL,
  category VARCHAR(40) NOT NULL,
  saved_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
CREATE TABLE IF NOT EXISTS shipment_fields (
  email_id VARCHAR(64) NOT NULL,
  field_key VARCHAR(40) NOT NULL,
  si_value TEXT,
  bl_value TEXT,
  si_evidence TEXT,
  bl_evidence TEXT,
  PRIMARY KEY (email_id, field_key)
);
CREATE TABLE IF NOT EXISTS email_reviews (
  review_id VARCHAR(36) PRIMARY KEY,
  email_id VARCHAR(64) NOT NULL,
  extraction_revision VARCHAR(36) NOT NULL,
  decision VARCHAR(20) NOT NULL,
  reviewer VARCHAR(200) NOT NULL,
  note TEXT NOT NULL,
  saved_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
CREATE TABLE IF NOT EXISTS attachment_contents (
  email_id VARCHAR(64) NOT NULL,
  file_name VARCHAR(255) NOT NULL,
  content LONGBLOB NOT NULL,
  PRIMARY KEY (email_id, file_name)
);
