-- Places (M4). Expand-only: nullable/defaulted columns, readable/ignorable by the previous image.
ALTER TABLE saved_places ADD COLUMN provider VARCHAR(16);
ALTER TABLE saved_places ADD COLUMN provider_place_id VARCHAR(512);
ALTER TABLE saved_places ADD COLUMN formatted_address VARCHAR(512);
ALTER TABLE saved_places ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'OTHER';
ALTER TABLE saved_places ADD COLUMN icon VARCHAR(32);

-- Backfill: a place labelled "hem"/"home" becomes HOME; at most one per user (the oldest wins).
UPDATE saved_places SET kind = 'HOME'
WHERE id IN (
    SELECT MIN(id) FROM saved_places WHERE LOWER(TRIM(label)) IN ('hem', 'home') GROUP BY user_id
);
