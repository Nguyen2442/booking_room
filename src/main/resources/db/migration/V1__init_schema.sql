-- Required for the exclusion constraint on (uuid =, tstzrange &&)
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE users (
    id            UUID         PRIMARY KEY,
    email         VARCHAR(255) NOT NULL,
    full_name     VARCHAR(150) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_users_role CHECK (role IN ('USER', 'ADMIN')),
    CONSTRAINT ux_users_email UNIQUE (email)
);

CREATE TABLE rooms (
    id          UUID          PRIMARY KEY,
    name        VARCHAR(100)  NOT NULL,
    location    VARCHAR(200),
    capacity    INTEGER       NOT NULL,
    description VARCHAR(1000),
    active      BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ   NOT NULL,
    updated_at  TIMESTAMPTZ   NOT NULL,
    version     BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_rooms_capacity CHECK (capacity > 0)
);
CREATE UNIQUE INDEX ux_rooms_name_lower ON rooms (lower(name));

CREATE TABLE room_equipment (
    room_id   UUID        NOT NULL REFERENCES rooms (id) ON DELETE CASCADE,
    equipment VARCHAR(50) NOT NULL,
    PRIMARY KEY (room_id, equipment)
);

CREATE TABLE bookings (
    id            UUID          PRIMARY KEY,
    room_id       UUID          NOT NULL REFERENCES rooms (id),
    user_id       UUID          NOT NULL REFERENCES users (id),
    title         VARCHAR(200)  NOT NULL,
    description   VARCHAR(2000),
    start_time    TIMESTAMPTZ   NOT NULL,
    end_time      TIMESTAMPTZ   NOT NULL,
    -- half-open [start, end): back-to-back bookings do not overlap
    time_range    TSTZRANGE GENERATED ALWAYS AS (tstzrange(start_time, end_time, '[)')) STORED,
    status        VARCHAR(20)   NOT NULL,
    cancelled_at  TIMESTAMPTZ,
    cancelled_by  UUID,
    cancel_reason VARCHAR(500),
    created_at    TIMESTAMPTZ   NOT NULL,
    updated_at    TIMESTAMPTZ   NOT NULL,
    version       BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_bookings_range  CHECK (end_time > start_time),
    CONSTRAINT ck_bookings_status CHECK (status IN ('CONFIRMED', 'CANCELLED')),
    -- Single source of truth against double booking, safe under concurrency.
    CONSTRAINT bookings_no_overlap EXCLUDE USING gist (room_id WITH =, time_range WITH &&)
        WHERE (status = 'CONFIRMED')
);

CREATE INDEX ix_bookings_room_time  ON bookings (room_id, start_time, end_time) WHERE status = 'CONFIRMED';
CREATE INDEX ix_bookings_start      ON bookings (start_time) WHERE status = 'CONFIRMED';
CREATE INDEX ix_bookings_user_start ON bookings (user_id, start_time DESC);
CREATE INDEX ix_bookings_room_start ON bookings (room_id, start_time DESC);
