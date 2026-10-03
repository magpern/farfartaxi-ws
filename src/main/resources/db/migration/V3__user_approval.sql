-- Expand-only: existing users stay approved; new self-registrations are created with approved = false by the application.
ALTER TABLE users ADD COLUMN approved BOOLEAN NOT NULL DEFAULT TRUE;
