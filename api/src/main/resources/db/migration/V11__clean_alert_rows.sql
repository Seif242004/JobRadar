-- Older alert-mail rows were stored before the field cleaning fixes: HTML entities and LinkedIn
-- footer text in names and locations, and UTF-8 dashes read as Latin-1. Clean them, then queue the
-- recent ones that were rejected as "Outside Egypt" so they are scored again (the ingestion worker
-- picks up rows flagged intake_pending).

-- Company names: drop everything after "&middot;" (the location tail) and decode "&amp;".
UPDATE company c
SET name = x.cleaned
FROM (
    SELECT DISTINCT ON (cleaned) id, cleaned
    FROM (
        SELECT id, btrim(replace(split_part(name, '&amp;middot;', 1), '&amp;', '&')) AS cleaned
        FROM company
        WHERE name LIKE '%&amp;%'
    ) raw
    WHERE cleaned <> ''
    ORDER BY cleaned, id
) x
WHERE c.id = x.id
  AND NOT EXISTS (SELECT 1 FROM company o WHERE o.name = x.cleaned AND o.id <> c.id);

-- Titles: decode "&amp;" and repair dashes that were read as Latin-1 (a-circumflex plus control characters).
UPDATE posting
SET title = replace(regexp_replace(title, E'â[\u0080-\u009f]+', E'–', 'g'), '&amp;', '&')
WHERE title LIKE '%&amp;%' OR title ~ E'â[\u0080-\u009f]';

-- Locations that are really LinkedIn footer text.
UPDATE posting
SET location = NULL
WHERE location ~* '(connection|alumni|premium|follower|applicant)';

-- Score again the recent alert postings that were rejected as outside Egypt.
UPDATE posting p
SET intake_pending = true
WHERE p.source = 'gmail'
  AND p.first_seen_at > now() - interval '8 days'
  AND EXISTS (
      SELECT 1 FROM match m
      WHERE m.posting_id = p.id AND NOT m.eligible AND m.reasons ILIKE '%outside egypt%');
