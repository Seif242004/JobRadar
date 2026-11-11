-- Application tracking (M3). The application table from V1 gets the constraints that make an
-- approval idempotent and give its status a closed set of values.

-- One application per user and posting: a repeated Approve tap, or a redelivered message, can
-- never send a second email.
ALTER TABLE application ADD CONSTRAINT application_user_posting_key UNIQUE (user_id, posting_id);

-- 'sending' marks an email that is being sent right now. It is written before the send and
-- replaced by 'applied' afterwards, so a crash in between leaves a visible row and never a
-- second email.
ALTER TABLE application ADD CONSTRAINT application_status_check
    CHECK (status IN ('sending', 'applied', 'replied', 'interview', 'offer', 'rejected'));

-- 'email' (sent by the system after an approval) or 'manual' (the user applied elsewhere and
-- told the bot).
ALTER TABLE application ADD CONSTRAINT application_channel_check CHECK (channel IN ('email', 'manual'));

-- Set when the follow-up reminder was sent, so it is sent once.
ALTER TABLE application ADD COLUMN follow_up_notified_at TIMESTAMPTZ;
