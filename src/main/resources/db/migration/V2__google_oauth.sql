ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;

ALTER TABLE users ADD COLUMN google_sub VARCHAR(128);

ALTER TABLE users ADD CONSTRAINT uq_users_google_sub UNIQUE (google_sub);
