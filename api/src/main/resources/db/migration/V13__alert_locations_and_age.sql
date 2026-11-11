-- 1. The user asked for a one-week window; a stored value of 1 day hid every posting older than a day.
UPDATE profile SET max_age_days = 7 WHERE max_age_days = 1;

-- 2. Alert postings come from Egypt searches. Rows that lost their place text (footer junk) are Egypt roles.
UPDATE posting SET location = 'Egypt (alert search)'
WHERE source = 'gmail' AND location IS NULL;

-- 3. Score the recent ones again.
UPDATE posting p SET intake_pending = true
WHERE p.source = 'gmail' AND p.first_seen_at > now() - interval '8 days'
  AND EXISTS (SELECT 1 FROM match m WHERE m.posting_id = p.id AND NOT m.eligible
              AND (m.reasons ILIKE '%outside egypt%' OR m.reasons ILIKE '%older than%'));
