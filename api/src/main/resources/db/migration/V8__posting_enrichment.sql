-- Full posting text fetched from the public apply page, for postings that arrived with a thin description.
ALTER TABLE posting ADD COLUMN enriched_at TIMESTAMPTZ;
ALTER TABLE posting ADD COLUMN enrich_status TEXT;   -- full, thin, blocked, failed
