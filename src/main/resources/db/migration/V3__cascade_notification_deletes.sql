-- ============================================================
-- IsarAlert — V3 Cascade notification deletes
-- ============================================================
-- Deleting a listing (DELETE /api/listings/{id}) or a user used to fail
-- with a foreign-key violation when notifications referenced them.
-- Notifications are only a delivery log, so they go with their parent.
-- ============================================================

ALTER TABLE notifications DROP CONSTRAINT notifications_listing_id_fkey;
ALTER TABLE notifications
    ADD CONSTRAINT notifications_listing_id_fkey
    FOREIGN KEY (listing_id) REFERENCES listings(id) ON DELETE CASCADE;

ALTER TABLE notifications DROP CONSTRAINT notifications_user_id_fkey;
ALTER TABLE notifications
    ADD CONSTRAINT notifications_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
