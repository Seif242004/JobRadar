-- Search preferences the hard filters read. All editable from the dashboard.

-- Only entry-level roles (internship, trainee, junior, graduate) pass when true.
ALTER TABLE profile ADD COLUMN entry_level_only BOOLEAN NOT NULL DEFAULT TRUE;

-- A posting asking for more years of experience than this is rejected while entry_level_only is set.
ALTER TABLE profile ADD COLUMN max_years_experience INT NOT NULL DEFAULT 2;

-- A posting title must contain one of these (empty list = no title requirement).
ALTER TABLE profile ADD COLUMN title_keywords TEXT[] NOT NULL DEFAULT '{}';

-- A posting whose title contains one of these is rejected.
ALTER TABLE profile ADD COLUMN exclude_keywords TEXT[] NOT NULL DEFAULT '{}';

-- False until the user has confirmed the preferences once (the dashboard shows onboarding).
ALTER TABLE profile ADD COLUMN onboarded BOOLEAN NOT NULL DEFAULT FALSE;

-- Existing profiles keep the old rule: entry level until two years after graduation.
UPDATE profile SET entry_level_only = (graduation_date IS NULL OR graduation_date + INTERVAL '2 years' > now());
