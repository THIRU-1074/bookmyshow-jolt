-- Idempotency-Key responses now back Jolt's Idempotency middleware through its IdempotencyStore SPI
-- (SPEC 5.7): the key is Jolt's client scope ("u:<user id>") plus the client's key, and a stored
-- response keeps its replayable headers as a JSON array of [name, value] pairs. A row with a NULL
-- status is a request still running. Stored keys live 24 hours, so the old rows are dropped.
DROP TABLE idempotency_key;
CREATE TABLE idempotency_key (scope TEXT NOT NULL, key TEXT NOT NULL, fingerprint TEXT NOT NULL,
  status INTEGER, headers TEXT, body BLOB, created_at INTEGER NOT NULL,
  PRIMARY KEY (scope, key)) STRICT;
CREATE INDEX idempotency_key_created ON idempotency_key (created_at);
