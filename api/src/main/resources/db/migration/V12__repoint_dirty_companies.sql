-- V11 skipped dirty company names whose clean name already exists. Point their postings at the clean company,
-- and remove the dirty rows nothing else uses.
UPDATE posting p
SET company_id = clean.id
FROM company dirty
JOIN company clean
  ON clean.name = btrim(replace(split_part(dirty.name, '&amp;middot;', 1), '&amp;', '&')) AND clean.id <> dirty.id
WHERE p.company_id = dirty.id
  AND dirty.name LIKE '%&amp;%';

DELETE FROM company d
WHERE d.name LIKE '%&amp;%'
  AND NOT EXISTS (SELECT 1 FROM posting p WHERE p.company_id = d.id)
  AND NOT EXISTS (SELECT 1 FROM watchlist w WHERE w.company_id = d.id);

-- Thndr publishes on Ashby (16 open roles in Egypt); the seeder does not change a company that already exists.
UPDATE company
SET careers_url = 'https://jobs.ashbyhq.com/thndr', ats_type = 'ashby', board_token = 'thndr'
WHERE name = 'Thndr';
