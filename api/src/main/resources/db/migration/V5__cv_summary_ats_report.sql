-- Role-specific CV summaries and the ATS check report of each tailored CV.

-- A short summary line variant per target role. Written from claims that are already in the
-- user's CV; verify stays true until the user confirms the wording, and only confirmed
-- variants ever reach a CV.
CREATE TABLE cv_summary (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT  NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    label      TEXT    NOT NULL,
    focus      TEXT[]  NOT NULL DEFAULT '{}',       -- words in a posting that select this variant
    text       TEXT    NOT NULL,
    verify     BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INT     NOT NULL DEFAULT 0,
    UNIQUE (user_id, label)
);

-- The wording used on this CV, and the ATS checks run on its PDF (score, keywords, pass/fail checks).
ALTER TABLE cv_version ADD COLUMN summary TEXT;
ALTER TABLE cv_version ADD COLUMN ats_report JSONB;
