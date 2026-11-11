-- Postings older than this many days are hidden and never pushed (0 = no limit).
ALTER TABLE profile ADD COLUMN max_age_days INT NOT NULL DEFAULT 7;

-- Notification thresholds on the fit score (0-100). A new posting at or above push_min_fit is pushed at once;
-- one at or above digest_min_fit (or without a fit score) goes into the daily digest; below that it stays on the dashboard.
ALTER TABLE profile ADD COLUMN push_min_fit INT NOT NULL DEFAULT 75;
ALTER TABLE profile ADD COLUMN digest_min_fit INT NOT NULL DEFAULT 40;
