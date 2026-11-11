-- Alert mail glued the location onto the company name ("Siemens · Cairo (On-site)"), stored as a raw
-- "·", "&middot;" or "&amp;middot;". Earlier cleanups matched only one spelling. Create the clean company
-- when it is missing, point postings at it, then drop the dirty rows nothing uses.
CREATE TEMPORARY TABLE dirty_company ON COMMIT DROP AS
SELECT id,
       btrim(replace(regexp_replace(name, '\s*(&amp;|&)?(middot;|#183;)?\s*(·|middot;|#183;).*$', ''), '&amp;', '&')) AS clean
FROM company
WHERE name ~ '(·|middot;|#183;)';

INSERT INTO company (name)
SELECT DISTINCT clean FROM dirty_company WHERE clean <> ''
ON CONFLICT (name) DO NOTHING;

UPDATE posting p
SET company_id = c.id
FROM dirty_company d
JOIN company c ON c.name = d.clean
WHERE p.company_id = d.id AND c.id <> d.id AND d.clean <> '';

DELETE FROM company d
WHERE d.id IN (SELECT id FROM dirty_company)
  AND NOT EXISTS (SELECT 1 FROM posting p WHERE p.company_id = d.id)
  AND NOT EXISTS (SELECT 1 FROM watchlist w WHERE w.company_id = d.id);

-- Titles may carry the same entities.
UPDATE posting SET title = replace(title, '&amp;', '&') WHERE title LIKE '%&amp;%';
