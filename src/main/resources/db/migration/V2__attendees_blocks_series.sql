-- Expected head-count, validated against room capacity by the service (NULL for blocks / legacy rows).
ALTER TABLE bookings ADD COLUMN attendees INTEGER;
ALTER TABLE bookings ADD CONSTRAINT ck_bookings_attendees CHECK (attendees IS NULL OR attendees > 0);

-- A "block" is a booking-shaped hold on a room (maintenance, cleaning, event). Reusing the table means the
-- same exclusion constraint protects blocks and bookings from each other, atomically, with no extra code path.
ALTER TABLE bookings ADD COLUMN kind VARCHAR(20) NOT NULL DEFAULT 'BOOKING';
ALTER TABLE bookings ADD CONSTRAINT ck_bookings_kind CHECK (kind IN ('BOOKING', 'BLOCK'));

-- Occurrences of one recurring booking share a series_id.
ALTER TABLE bookings ADD COLUMN series_id UUID;
CREATE INDEX ix_bookings_series ON bookings (series_id, start_time) WHERE series_id IS NOT NULL;
CREATE INDEX ix_bookings_kind_start ON bookings (kind, start_time) WHERE kind = 'BLOCK';
