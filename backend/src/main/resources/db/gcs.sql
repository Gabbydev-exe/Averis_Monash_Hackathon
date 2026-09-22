-- Apply after persistence.sql and automation.sql. Original BLOBs are retained for migration.
CREATE TABLE IF NOT EXISTS attachment_objects (
 email_id VARCHAR(64) NOT NULL,
 file_name VARCHAR(255) NOT NULL,
 object_name VARCHAR(512) NOT NULL,
 PRIMARY KEY (email_id, file_name)
);
