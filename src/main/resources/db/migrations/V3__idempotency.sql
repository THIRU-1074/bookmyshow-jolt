-- Stored responses for Idempotency-Key (SPEC 5.7), scoped per user. A row with a NULL status is a
-- request still running. TODO(jolt#54): drop once Jolt's Idempotency.keys() ships with its own store.
CREATE TABLE idempotency_key (user_id INTEGER NOT NULL, key TEXT NOT NULL, fingerprint TEXT NOT NULL,
  status INTEGER, content_type TEXT, body BLOB, created_at INTEGER NOT NULL,
  PRIMARY KEY (user_id, key)) STRICT;
CREATE INDEX idempotency_key_created ON idempotency_key (created_at);
