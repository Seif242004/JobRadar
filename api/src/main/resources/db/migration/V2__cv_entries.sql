-- What the CV renderer needs beyond the bullets themselves (SPEC.md, "Open for M2").
-- The V1 bullet table has no place for an entry's heading and dates, or for the fixed parts of
-- a CV (name, contacts, education, languages). Nothing here is free text written by the system:
-- every value comes from the user's own profile seed.

-- Fixed CV parts as one JSON document per user:
--   { "name", "headline", "contacts": [{"text", "url"}],
--     "education": [{"title", "dates", "context"}], "languages" }
ALTER TABLE profile ADD COLUMN cv_static JSONB;

-- One job, one project or one skill row. Bullets of the same entry are rendered together.
CREATE TABLE cv_entry (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT  NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    section    TEXT    NOT NULL CHECK (section IN ('experience', 'project', 'skill')),
    slug       TEXT    NOT NULL,                    -- stable key from title + dates
    title      TEXT    NOT NULL,
    dates      TEXT,
    context    TEXT,                                -- sub-heading line under the title
    sort_order INTEGER NOT NULL,                    -- order of the master CV
    UNIQUE (user_id, slug)
);

-- SET NULL: deleting an entry must never delete the user's bullets.
ALTER TABLE bullet ADD COLUMN entry_id BIGINT REFERENCES cv_entry (id) ON DELETE SET NULL;
CREATE INDEX bullet_entry_idx ON bullet (entry_id);
