-- The user's own finished CVs. Stored byte for byte and never edited; every posting is scored against them.
CREATE TABLE base_cv (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    label      TEXT        NOT NULL,
    filename   TEXT        NOT NULL,
    pdf        BYTEA       NOT NULL,
    text       TEXT        NOT NULL,      -- text extracted from the PDF, used for scoring only
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, filename)
);
