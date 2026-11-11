-- Postings the user adds by hand or whose text the user pastes. The ingestion worker picks these up,
-- reads the apply method, drops the old embedding and queues them for scoring.
ALTER TABLE posting ADD COLUMN intake_pending BOOLEAN NOT NULL DEFAULT false;
CREATE INDEX posting_intake_pending_idx ON posting (id) WHERE intake_pending;
