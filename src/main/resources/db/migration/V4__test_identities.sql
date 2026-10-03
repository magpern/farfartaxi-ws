-- Production test identities with two-way isolation (expand-only; v1.3.0 with ddl-auto=validate still runs).
ALTER TABLE users ADD COLUMN is_test BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE rides ADD COLUMN is_test BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX idx_rides_is_test ON rides(is_test);
