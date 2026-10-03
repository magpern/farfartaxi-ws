-- M1 core ride model. Expand/contract: new columns are nullable or defaulted so v1.5.0 code still reads/inserts.
-- Rolling back to v1.5.0 requires mapping statuses back first:
--   UPDATE rides SET status='REJECTED' WHERE status='NO_DRIVER';
--   UPDATE rides SET status='PENDING_OPEN' WHERE status='REQUESTED';
--   UPDATE rides SET status='IN_PROGRESS' WHERE status IN ('EN_ROUTE','ARRIVED','PICKED_UP');

-- Status data migration (kept as plain single-line UPDATE statements; a test replays them on H2).
UPDATE rides SET status = 'REQUESTED' WHERE status = 'PENDING_OPEN';
UPDATE rides SET status = 'EN_ROUTE' WHERE status = 'IN_PROGRESS';
UPDATE rides SET status = 'NO_DRIVER' WHERE status = 'REJECTED';

ALTER TABLE rides ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE rides ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'SCHEDULED';
ALTER TABLE rides ADD COLUMN pickup_note VARCHAR(512);
ALTER TABLE rides ADD COLUMN urgent BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE rides ADD COLUMN client_request_id VARCHAR(64);
ALTER TABLE rides ADD COLUMN requested_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE rides ADD COLUMN arrived_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE rides ADD COLUMN picked_up_at TIMESTAMP WITH TIME ZONE;
UPDATE rides SET requested_at = created_at;
CREATE UNIQUE INDEX ux_rides_passenger_client_request ON rides(passenger_id, client_request_id);
CREATE INDEX idx_rides_status_kind ON rides(status, kind);

ALTER TABLE users ADD COLUMN driver_available_now BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE users ADD COLUMN driver_away_from DATE;
ALTER TABLE users ADD COLUMN driver_away_until DATE;

CREATE TABLE ride_offers (
    id BIGSERIAL PRIMARY KEY,
    ride_id BIGINT NOT NULL REFERENCES rides(id) ON DELETE CASCADE,
    driver_id BIGINT NOT NULL REFERENCES users(id),
    status VARCHAR(16) NOT NULL,
    priority BOOLEAN NOT NULL DEFAULT FALSE,
    offered_at TIMESTAMP WITH TIME ZONE NOT NULL,
    viewed_at TIMESTAMP WITH TIME ZONE,
    responded_at TIMESTAMP WITH TIME ZONE,
    comment VARCHAR(512),
    UNIQUE (ride_id, driver_id)
);
CREATE INDEX idx_ride_offers_driver_status ON ride_offers(driver_id, status);

CREATE TABLE ride_messages (
    id BIGSERIAL PRIMARY KEY,
    ride_id BIGINT NOT NULL REFERENCES rides(id) ON DELETE CASCADE,
    sender_id BIGINT NOT NULL REFERENCES users(id),
    code VARCHAR(32) NOT NULL,
    text VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    read_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_ride_messages_ride ON ride_messages(ride_id);

CREATE TABLE ride_notifications_sent (
    id BIGSERIAL PRIMARY KEY,
    ride_id BIGINT NOT NULL REFERENCES rides(id) ON DELETE CASCADE,
    kind VARCHAR(32) NOT NULL,
    sent_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (ride_id, kind)
);

-- Legacy open rides (old PENDING_OPEN, now REQUESTED) were visible to every driver; the new model lists rides through
-- offers, so give each of them an OFFERED offer for every same-world DRIVER/ADMIN (never the passenger).
INSERT INTO ride_offers (ride_id, driver_id, status, priority, offered_at)
SELECT r.id, u.id, 'OFFERED', FALSE, CURRENT_TIMESTAMP
FROM rides r JOIN users u ON u.is_test = r.is_test
WHERE r.status = 'REQUESTED' AND u.role IN ('DRIVER', 'ADMIN') AND u.enabled = TRUE AND u.approved = TRUE
  AND u.id <> r.passenger_id;

-- Rides already taken by a driver get that driver's ACCEPTED offer, so the new offer-based driver views keep showing them.
INSERT INTO ride_offers (ride_id, driver_id, status, priority, offered_at, responded_at)
SELECT r.id, r.accepted_by_driver_id, 'ACCEPTED', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM rides r
WHERE r.status IN ('ACCEPTED', 'EN_ROUTE', 'ARRIVED', 'PICKED_UP') AND r.accepted_by_driver_id IS NOT NULL;
