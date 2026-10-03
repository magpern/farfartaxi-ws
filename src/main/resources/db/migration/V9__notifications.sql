-- M6: notification language and per-category notification preferences.
ALTER TABLE users ADD COLUMN locale VARCHAR(8) NOT NULL DEFAULT 'sv';

-- Missing row = everything on.
CREATE TABLE notification_prefs (
    user_id BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    ride_requests BOOLEAN NOT NULL DEFAULT TRUE,
    ride_updates BOOLEAN NOT NULL DEFAULT TRUE,
    reminders BOOLEAN NOT NULL DEFAULT TRUE
);
