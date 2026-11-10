-- JobRadar core schema. Tables and fields follow docs/PLAN.md, "Core tables (PostgreSQL)".
-- Scope rule: postings, companies and source runs are shared; every per-user table carries
-- user_id with ON DELETE CASCADE, so deleting an app_user row removes all of that user's data
-- (guardrail 6, one-click delete).

CREATE EXTENSION IF NOT EXISTS vector;

-- ---------------------------------------------------------------- per user: identity
CREATE TABLE app_user (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email            TEXT        NOT NULL UNIQUE,
    telegram_chat_id BIGINT,
    gmail_token_ref  TEXT,                          -- Secrets Manager reference, never the token
    daily_apply_cap  INTEGER     NOT NULL CHECK (daily_apply_cap >= 0),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------- per user: preferences
CREATE TABLE profile (
    user_id         BIGINT PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    roles           TEXT[] NOT NULL DEFAULT '{}',
    cities          TEXT[] NOT NULL DEFAULT '{}',
    remote          BOOLEAN NOT NULL DEFAULT FALSE,
    job_types       TEXT[] NOT NULL DEFAULT '{}',
    graduation_date DATE
);

-- ---------------------------------------------------------------- per user: master profile
-- One row per CV bullet. Tailoring may only select and reorder these IDs.
CREATE TABLE bullet (
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id   BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    section   TEXT   NOT NULL CHECK (section IN ('experience', 'project', 'skill')),
    text      TEXT   NOT NULL,
    tags      TEXT[] NOT NULL DEFAULT '{}',
    embedding vector(1024)                          -- SPEC.md D3: baai/bge-m3
);
CREATE INDEX bullet_user_idx ON bullet (user_id);

-- ---------------------------------------------------------------- shared: companies
CREATE TABLE company (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name             TEXT NOT NULL UNIQUE,
    careers_url      TEXT,
    ats_type         TEXT,                          -- set by the careers resolver (M1)
    board_token      TEXT,
    last_resolved_at TIMESTAMPTZ
);

-- ---------------------------------------------------------------- per user: watchlist
CREATE TABLE watchlist (
    user_id    BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    company_id BIGINT NOT NULL REFERENCES company (id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, company_id)
);

-- ---------------------------------------------------------------- shared: postings
CREATE TABLE posting (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id    BIGINT REFERENCES company (id),
    source        TEXT        NOT NULL,
    source_id     TEXT        NOT NULL,
    title         TEXT        NOT NULL,
    location      TEXT,
    remote_policy TEXT,
    language      TEXT,
    description   TEXT,
    apply_channel TEXT,
    apply_target  TEXT,
    deadline      TIMESTAMPTZ,
    posted_at     TIMESTAMPTZ,                      -- only when the source gives it
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    content_hash  TEXT        NOT NULL,             -- hash of company + title + location
    embedding     vector(1024),
    UNIQUE (source, source_id)                      -- idempotent re-ingestion per source
);
-- Cross-source dedupe looks postings up by apply URL and by content hash (Flow 1).
CREATE INDEX posting_content_hash_idx ON posting (content_hash);
CREATE INDEX posting_apply_target_idx ON posting (apply_target);
CREATE INDEX posting_first_seen_idx   ON posting (first_seen_at);

-- ---------------------------------------------------------------- per user: matches
CREATE TABLE match (
    user_id    BIGINT  NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    posting_id BIGINT  NOT NULL REFERENCES posting (id) ON DELETE CASCADE,
    eligible   BOOLEAN NOT NULL,
    fit_score  NUMERIC(5, 2) CHECK (fit_score BETWEEN 0 AND 100),
    priority   DOUBLE PRECISION,                    -- fit x 0.5^(age_hours / 6)
    band       TEXT CHECK (band IN ('hot', 'warm', 'backlog', 'deadline')),
    reasons    TEXT,
    PRIMARY KEY (user_id, posting_id)
);

-- ---------------------------------------------------------------- per user: tailored CVs
CREATE TABLE cv_version (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT   NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    posting_id BIGINT   NOT NULL REFERENCES posting (id),
    bullet_ids BIGINT[] NOT NULL,                   -- ordered; every ID must exist in bullet
    pdf_s3_key TEXT,
    ats_score  NUMERIC(5, 2)
);
CREATE INDEX cv_version_user_idx ON cv_version (user_id);

-- ---------------------------------------------------------------- per user: applications
CREATE TABLE application (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id       BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    posting_id    BIGINT NOT NULL REFERENCES posting (id),
    cv_version_id BIGINT REFERENCES cv_version (id),
    channel       TEXT   NOT NULL,
    status        TEXT   NOT NULL,
    sent_at       TIMESTAMPTZ,
    follow_up_at  TIMESTAMPTZ
);
CREATE INDEX application_user_idx      ON application (user_id);
CREATE INDEX application_follow_up_idx ON application (follow_up_at);

-- ---------------------------------------------------------------- per user: notifications
CREATE TABLE notification (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    type       TEXT   NOT NULL,
    posting_id BIGINT REFERENCES posting (id),
    sent_at    TIMESTAMPTZ,
    acted_at   TIMESTAMPTZ,
    action     TEXT
);
CREATE INDEX notification_user_idx ON notification (user_id);

-- ---------------------------------------------------------------- shared: connector runs
CREATE TABLE source_run (
    source      TEXT        NOT NULL,
    started_at  TIMESTAMPTZ NOT NULL,
    items_found INTEGER     NOT NULL DEFAULT 0,
    new_items   INTEGER     NOT NULL DEFAULT 0,
    errors      TEXT,
    PRIMARY KEY (source, started_at)
);
