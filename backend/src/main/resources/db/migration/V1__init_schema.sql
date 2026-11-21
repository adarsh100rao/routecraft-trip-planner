CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE spatial_tiles (
    tile_key        VARCHAR(64) PRIMARY KEY,
    geom            GEOMETRY(Polygon, 4326) NOT NULL,
    last_fetched_at TIMESTAMPTZ NOT NULL,
    poi_count       INT NOT NULL DEFAULT 0
);
CREATE INDEX idx_spatial_tiles_geom ON spatial_tiles USING GIST (geom);

CREATE TABLE pois (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    external_id        VARCHAR(128) NOT NULL UNIQUE,
    tile_key           VARCHAR(64) NOT NULL REFERENCES spatial_tiles(tile_key),
    name               VARCHAR(255) NOT NULL,
    category           VARCHAR(32) NOT NULL,
    price_tier         SMALLINT NOT NULL DEFAULT 2,
    estimated_cost     INT NOT NULL DEFAULT 0,
    rating             DOUBLE PRECISION NOT NULL DEFAULT 4.0,
    avg_duration_mins  INT NOT NULL DEFAULT 60,
    tags               JSONB NOT NULL DEFAULT '{}'::jsonb,
    opening_hours      JSONB NOT NULL DEFAULT '{}'::jsonb,
    hours_estimated    BOOLEAN NOT NULL DEFAULT TRUE,
    location           GEOGRAPHY(Point, 4326) NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_pois_location ON pois USING GIST (location);
CREATE INDEX idx_pois_tile_category ON pois (tile_key, category);
CREATE INDEX idx_pois_tags_gin ON pois USING GIN (tags);

CREATE TABLE trips (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    destination          VARCHAR(255) NOT NULL,
    center_lat           DOUBLE PRECISION NOT NULL,
    center_lng           DOUBLE PRECISION NOT NULL,
    start_date           DATE NOT NULL,
    days                 SMALLINT NOT NULL CHECK (days BETWEEN 1 AND 7),
    people_count         SMALLINT NOT NULL CHECK (people_count >= 1),
    total_budget         INT NOT NULL CHECK (total_budget > 0),
    currency             VARCHAR(8) NOT NULL DEFAULT 'INR',
    pace                 VARCHAR(16) NOT NULL,
    preferences          JSONB NOT NULL DEFAULT '[]'::jsonb,
    estimated_total_cost INT NOT NULL DEFAULT 0,
    routing_mode         VARCHAR(16) NOT NULL DEFAULT 'OSRM',
    route_polylines      JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE itinerary_stops (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trip_id               UUID NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
    poi_id                UUID NOT NULL REFERENCES pois(id),
    day_number            SMALLINT NOT NULL,
    order_index           SMALLINT NOT NULL,
    start_time            TIME NOT NULL,
    end_time              TIME NOT NULL,
    travel_mins_from_prev INT NOT NULL DEFAULT 0,
    estimated_cost        INT NOT NULL DEFAULT 0,
    is_locked             BOOLEAN NOT NULL DEFAULT FALSE,
    slot_type             VARCHAR(16) NOT NULL,
    UNIQUE (trip_id, day_number, order_index)
);
CREATE INDEX idx_stops_trip_day ON itinerary_stops (trip_id, day_number);
