-- M7: live tracking (expand-only; readable by the previous image).
ALTER TABLE rides ADD COLUMN last_location_accuracy_m DOUBLE PRECISION;
ALTER TABLE rides ADD COLUMN eta_target VARCHAR(16);
ALTER TABLE rides ADD COLUMN eta_computed_at TIMESTAMPTZ;
ALTER TABLE rides ADD COLUMN eta_lat DOUBLE PRECISION;
ALTER TABLE rides ADD COLUMN eta_lon DOUBLE PRECISION;
-- Moment the passenger cancelled; with completed_at it defines "ride ended" for position retention and share expiry.
ALTER TABLE rides ADD COLUMN cancelled_at TIMESTAMPTZ;
UPDATE rides SET cancelled_at = updated_at WHERE status = 'CANCELLED' AND cancelled_at IS NULL;
-- Revoked links keep their token so the public endpoint can answer 410 (not 404).
ALTER TABLE rides ADD COLUMN share_revoked_at TIMESTAMPTZ;
