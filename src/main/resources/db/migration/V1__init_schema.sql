-- ============================================================
-- IsarAlert — V1 Initial Schema
-- ============================================================
-- Flyway migration: creates all core tables for the
-- Munich Apartment Notifier system.
-- ============================================================

-- ==================== Users ====================
-- Telegram users who have registered with the bot.
CREATE TABLE users (
    id               BIGSERIAL PRIMARY KEY,
    telegram_chat_id BIGINT UNIQUE NOT NULL,
    username         VARCHAR(255),
    first_name       VARCHAR(255),
    active           BOOLEAN DEFAULT TRUE,
    created_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ==================== Search Criteria ====================
-- Each user can have multiple search criteria (e.g. different price ranges
-- for different neighborhoods).
CREATE TABLE search_criteria (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    city         VARCHAR(100) DEFAULT 'München',
    max_rent     DECIMAL(10,2),
    min_rooms    DECIMAL(3,1),
    max_rooms    DECIMAL(3,1),
    min_size_sqm INTEGER,
    max_size_sqm INTEGER,
    districts    TEXT[],       -- PostgreSQL array: {"Maxvorstadt", "Schwabing"}
    ubahn_lines  TEXT[],       -- PostgreSQL array: {"U3", "U6"}
    active       BOOLEAN DEFAULT TRUE,
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ==================== Listings ====================
-- Scraped apartment listings from various platforms.
-- The (external_id, source) pair uniquely identifies a listing,
-- preventing duplicates from the same platform.
CREATE TABLE listings (
    id             BIGSERIAL PRIMARY KEY,
    external_id    VARCHAR(255) NOT NULL,
    source         VARCHAR(50) NOT NULL,       -- WG_GESUCHT, IMMOSCOUT24, KLEINANZEIGEN
    title          VARCHAR(500) NOT NULL,
    description    TEXT,
    price          DECIMAL(10,2),
    rooms          DECIMAL(3,1),
    size_sqm       INTEGER,
    address        VARCHAR(500),
    district       VARCHAR(255),
    url            VARCHAR(1000) NOT NULL,
    image_url      VARCHAR(1000),
    available_from DATE,
    scraped_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(external_id, source)
);

-- ==================== Notifications ====================
-- Log of all notifications sent to users.
-- The (user_id, listing_id) constraint prevents sending
-- the same listing to the same user twice.
CREATE TABLE notifications (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT NOT NULL REFERENCES users(id),
    listing_id    BIGINT NOT NULL REFERENCES listings(id),
    status        VARCHAR(20) DEFAULT 'PENDING',   -- PENDING, SENT, FAILED
    sent_at       TIMESTAMP,
    error_message TEXT,
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, listing_id)
);

-- ==================== Indexes ====================
CREATE INDEX idx_listings_source      ON listings(source);
CREATE INDEX idx_listings_scraped_at  ON listings(scraped_at);
CREATE INDEX idx_listings_price       ON listings(price);
CREATE INDEX idx_notifications_status ON notifications(status);
CREATE INDEX idx_search_criteria_user ON search_criteria(user_id);
CREATE INDEX idx_users_chat_id        ON users(telegram_chat_id);
