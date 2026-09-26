-- ============================================================
-- IsarAlert — V2 Add Optimistic Locking Version Column
-- ============================================================
-- Adds a @Version column to listings for JPA optimistic locking.
-- Existing rows default to 0.
-- ============================================================

ALTER TABLE listings ADD COLUMN IF NOT EXISTS version BIGINT DEFAULT 0 NOT NULL;
