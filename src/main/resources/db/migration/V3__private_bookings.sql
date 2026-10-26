-- A private booking shows other employees only that the room is busy (no title, description, booker, head-count).
-- The owner and administrators still see everything. Masking is applied by the API, never by the client.
ALTER TABLE bookings ADD COLUMN private_booking BOOLEAN NOT NULL DEFAULT FALSE;
