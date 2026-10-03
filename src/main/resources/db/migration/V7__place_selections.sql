-- Learned place ranking (M3). Expand-only: a new table, readable/ignorable by the previous image.
CREATE TABLE place_selections (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    normalized_query VARCHAR(100) NOT NULL,
    provider VARCHAR(16) NOT NULL,
    provider_place_id VARCHAR(512) NOT NULL,
    name VARCHAR(256) NOT NULL,
    lat DOUBLE PRECISION NOT NULL,
    lon DOUBLE PRECISION NOT NULL,
    score DOUBLE PRECISION NOT NULL,
    last_selected_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_place_selections UNIQUE (user_id, normalized_query, provider, provider_place_id)
);
-- text_pattern_ops makes LIKE 'prefix%' lookups indexable regardless of the database collation (PostgreSQL).
CREATE INDEX idx_place_selections_query ON place_selections(normalized_query text_pattern_ops);
