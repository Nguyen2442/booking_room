-- Bumped whenever a user's credentials or privileges change (password change/reset, role change, deactivation).
-- Every JWT carries the version it was issued under, so old sessions stop working immediately.
ALTER TABLE users ADD COLUMN token_version INTEGER NOT NULL DEFAULT 0;
