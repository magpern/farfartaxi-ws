CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(255) NOT NULL,
    role VARCHAR(32) NOT NULL,
    phone VARCHAR(64),
    photo_url VARCHAR(1024),
    vehicle_note VARCHAR(255),
    must_change_password BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE rides (
    id BIGSERIAL PRIMARY KEY,
    passenger_id BIGINT NOT NULL REFERENCES users(id),
    accepted_by_driver_id BIGINT REFERENCES users(id),
    from_address VARCHAR(512) NOT NULL,
    from_lat DOUBLE PRECISION NOT NULL,
    from_lon DOUBLE PRECISION NOT NULL,
    to_address VARCHAR(512) NOT NULL,
    to_lat DOUBLE PRECISION NOT NULL,
    to_lon DOUBLE PRECISION NOT NULL,
    waypoints_json TEXT,
    scheduled_at TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(32) NOT NULL,
    cancel_reason VARCHAR(255),
    refusal_comment VARCHAR(255),
    refusal_driver_id BIGINT REFERENCES users(id),
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    last_driver_lat DOUBLE PRECISION,
    last_driver_lon DOUBLE PRECISION,
    last_location_at TIMESTAMP WITH TIME ZONE,
    eta_minutes INTEGER,
    share_token VARCHAR(128),
    share_expires_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_rides_status ON rides(status);
CREATE INDEX idx_rides_passenger ON rides(passenger_id);
CREATE INDEX idx_rides_driver ON rides(accepted_by_driver_id);
CREATE UNIQUE INDEX idx_rides_share_token ON rides(share_token) WHERE share_token IS NOT NULL;

CREATE TABLE ride_events (
    id BIGSERIAL PRIMARY KEY,
    ride_id BIGINT NOT NULL REFERENCES rides(id) ON DELETE CASCADE,
    actor_id BIGINT REFERENCES users(id),
    event_type VARCHAR(64) NOT NULL,
    comment VARCHAR(512),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE push_subscriptions (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    endpoint VARCHAR(2048) NOT NULL,
    p256dh VARCHAR(1024) NOT NULL,
    auth VARCHAR(1024) NOT NULL,
    user_agent VARCHAR(512),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, endpoint)
);

CREATE TABLE saved_places (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    label VARCHAR(128) NOT NULL,
    address VARCHAR(512) NOT NULL,
    lat DOUBLE PRECISION NOT NULL,
    lon DOUBLE PRECISION NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE ride_feedback (
    id BIGSERIAL PRIMARY KEY,
    ride_id BIGINT NOT NULL UNIQUE REFERENCES rides(id) ON DELETE CASCADE,
    passenger_id BIGINT NOT NULL REFERENCES users(id),
    stars INTEGER,
    comment VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
