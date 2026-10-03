-- M8: first-party product telemetry (allowlisted names and props only; no addresses or coordinates). Retained 180 days.
CREATE TABLE app_events (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT REFERENCES users (id) ON DELETE SET NULL,
    is_test    BOOLEAN NOT NULL DEFAULT FALSE,
    session_id VARCHAR(64),
    name       VARCHAR(40) NOT NULL,
    props      TEXT,
    client_ts  TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_app_events_name_created ON app_events (name, created_at);
CREATE INDEX idx_app_events_created ON app_events (created_at);
