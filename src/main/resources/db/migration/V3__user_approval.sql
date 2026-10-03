-- Expand-only: existing users stay approved; new self-registrations are created with approved = false by the application.
ALTER TABLE users ADD COLUMN approved BOOLEAN NOT NULL DEFAULT TRUE;
-- Tokens issued before this instant are rejected (set on password set/change/clear).
ALTER TABLE users ADD COLUMN credentials_changed_at TIMESTAMP WITH TIME ZONE;
-- Optimistic locking so stale whole-row saves cannot overwrite concurrent credential changes.
ALTER TABLE users ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
