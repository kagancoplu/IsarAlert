# 🏔️ IsarAlert — Munich Apartment Notifier

> A Spring Boot backend that watches **WG-Gesucht** for new Munich apartment listings matching your criteria and alerts you instantly via **Telegram**.

Named after Munich's beloved **Isar river** — because finding an apartment in Munich feels like swimming against the current. 🌊

---

## ✨ Features

- 🔍 Scrapes WG-Gesucht Munich apartment listings on a schedule (default: every 5 minutes)
- 🧮 Filters by max rent, rooms, size, districts and U-Bahn lines
- 📲 Telegram bot with a step-by-step wizard to set up your search — no API calls needed
- 🔁 Deduplication, so you're only notified once per listing, plus automatic retry of failed messages
- 🌐 REST API with Swagger UI for managing users, criteria and listings
- 🐳 One-command setup with Docker Compose

## 🏗️ Architecture

```
┌──────────────────────────────────────────────────────┐
│                   IsarAlert Backend                  │
│                                                      │
│  ⏰ SchedulerService (cron, default every 5 min)     │
│       │                                              │
│       ├── 🔍 WgGesuchtScraper (Jsoup)                │
│       │       search pages → new listings' details   │
│       │       ▼                                      │
│       └── 📋 ListingService                          │
│               ├── Dedup (external_id + source)       │
│               ├── Match against SearchCriteria       │
│               ▼                                      │
│           📨 NotificationService (with retries)      │
│               ▼                                      │
│           📲 Telegram Bot → Your Phone               │
│                                                      │
│  🗄️ PostgreSQL (users, criteria, listings, notifs)   │
│  🌐 REST API (manage criteria, view listings)        │
└──────────────────────────────────────────────────────┘
```

## 🛠️ Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Java 25 |
| Framework | Spring Boot 3.4 |
| Database | PostgreSQL 16 |
| Migrations | Flyway |
| Scraping | Jsoup |
| Notifications | Telegram Bot API |
| Build | Maven (wrapper included) |
| Containers | Docker Compose |
| Tests | JUnit 5, Mockito, Testcontainers |

## 🚀 Getting Started

### Prerequisites

- **Docker & Docker Compose** — [Download](https://docs.docker.com/get-docker/)
- **A Telegram bot token** — see [Set up the Telegram bot](#-set-up-the-telegram-bot)
- **Java 25** — only if you want to run the app outside Docker ([Download](https://adoptium.net/)). Maven is not required; use the included `./mvnw`.

### 1. Configure

```bash
git clone https://github.com/<your-username>/IsarAlert.git
cd IsarAlert
cp .env.example .env
# Edit .env and set TELEGRAM_BOT_TOKEN and TELEGRAM_BOT_USERNAME
```

### 2a. Run everything with Docker (recommended)

```bash
docker compose up -d --build
docker compose logs -f app     # watch the logs
```

### 2b. Or run the app locally (for development)

```bash
docker compose up -d postgres
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

On startup the app connects to PostgreSQL, runs the Flyway migrations, starts the Telegram bot and begins scanning.
Without a `TELEGRAM_BOT_TOKEN` the app still starts, but the bot is disabled.

### 3. Start searching

Open your bot in Telegram, send `/start`, then `/setcriteria` and answer the questions. That's it — matches arrive as messages.

### (Optional) pgAdmin

```bash
docker compose --profile tools up -d
# Open http://localhost:5050 — login: admin@isaralert.dev / admin
```

---

## ⚙️ Configuration

All settings are read from `.env` (see [`.env.example`](.env.example)):

| Variable | Default | Description |
|----------|---------|-------------|
| `TELEGRAM_BOT_TOKEN` | — | Bot token from @BotFather. Bot is disabled if empty. |
| `TELEGRAM_BOT_USERNAME` | `IsarAlertBot` | Your bot's username, without `@` |
| `DB_HOST` / `DB_PORT` / `DB_NAME` | `localhost` / `5432` / `isaralert` | PostgreSQL connection |
| `DB_USERNAME` / `DB_PASSWORD` | `isaralert` / `isaralert_dev_password` | PostgreSQL credentials — change these for any real deployment |
| `SCRAPER_CRON` | `0 */5 * * * *` | Scan schedule (Spring cron: sec min hour day month weekday) |
| `SCRAPER_REQUEST_DELAY_MS` | `3000` | Delay between HTTP requests to WG-Gesucht (plus random jitter) |

---

## 📲 Set up the Telegram bot

1. Open Telegram and search for **@BotFather**
2. Send `/newbot` and follow the prompts
3. Copy the **HTTP API token** (looks like `123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11`)
4. Set `TELEGRAM_BOT_TOKEN` in your `.env` file
5. Set `TELEGRAM_BOT_USERNAME` to your bot's username (without @)

### Bot Commands

| Command | Description |
|---------|-------------|
| `/start` | Register and (re)activate notifications |
| `/setcriteria` | Step-by-step wizard: rent, rooms, size, districts |
| `/search` | Show your current search criteria |
| `/forcescan` | Trigger a scan right now (max once every 10 minutes) |
| `/cancel` | Cancel the setup wizard |
| `/stop` | Pause notifications (send `/start` to resume) |
| `/help` | Show available commands |

### How matching works

- Empty criteria fields mean "no filter".
- If a listing is missing a value (e.g. the size couldn't be parsed), that filter lets it through rather than risk hiding a good apartment.
- Districts match whole words, so WG-Gesucht's compound districts like *Au-Haidhausen* match both `Au` and `Haidhausen`.
- U-Bahn line filters use a built-in district → line map; districts not in the map are let through.

---

## 🌐 REST API

Interactive docs: **http://localhost:8080/swagger-ui.html**

> ⚠️ The API has no authentication. Keep port 8080 private (e.g. don't expose it on a public server).

### Search Criteria

```bash
# Create criteria (the user is created if it doesn't exist)
curl -X POST http://localhost:8080/api/criteria \
  -H "Content-Type: application/json" \
  -d '{
    "telegramChatId": 123456789,
    "maxRent": 1200.00,
    "minRooms": 2.0,
    "maxRooms": 3.0,
    "minSizeSqm": 40,
    "maxSizeSqm": 80,
    "districts": ["Maxvorstadt", "Schwabing"],
    "ubahnLines": ["U3", "U6"]
  }'

# Get all criteria of a user (by internal user id)
curl http://localhost:8080/api/criteria/user/1

# Update criteria — only the fields you send are changed
curl -X PUT http://localhost:8080/api/criteria/1 \
  -H "Content-Type: application/json" \
  -d '{ "telegramChatId": 123456789, "maxRent": 1400.00 }'

# Delete criteria
curl -X DELETE http://localhost:8080/api/criteria/1
```

### Users

```bash
curl http://localhost:8080/api/users                     # all active users
curl http://localhost:8080/api/users/by-chat/123456789   # look up your user id by Telegram chat id
curl -X POST http://localhost:8080/api/users/1/deactivate
curl -X POST http://localhost:8080/api/users/1/activate
```

### Listings

```bash
curl "http://localhost:8080/api/listings?page=0&size=20"   # newest first, max size 100
curl http://localhost:8080/api/listings/1
curl -X DELETE http://localhost:8080/api/listings/1
```

### Health Check

```bash
curl http://localhost:8080/api/health
curl http://localhost:8080/actuator/health
```

---

## 🧪 Tests

```bash
./mvnw test      # all tests (Docker must be running)
./mvnw verify    # tests + coverage report (target/site/jacoco/index.html) + coverage gate
```

Most tests run the **whole application** against a real PostgreSQL (Testcontainers), with the outside world replaced by two local fake servers:

| Fake | What it does |
|------|--------------|
| `FakeTelegramApi` | Records every message the bot sends. Like the real API, it **rejects invalid MarkdownV2 and messages over 4096 characters** with a 400, so formatting bugs fail the build instead of silently failing in the chat. Can simulate outages. |
| `FakeWgGesucht` | Serves search and listing pages in WG-Gesucht's real markup, so scans run the real scraper over HTTP. Can simulate errors. |

| Test class | Covers |
|------------|--------|
| `TelegramBotIntegrationTest` | Every bot command and wizard path as a user would type it: registration, invalid input, min > max, `/cancel`, group-chat commands, long `/search` lists, `/forcescan` cooldown, Telegram outages |
| `ScanPipelineIntegrationTest` | Full scan cycles: matching per filter, multiple users, paused users, no duplicate messages, pagination, WG-Gesucht errors, Telegram retries and giving up |
| `RestApiIntegrationTest` | All endpoints, validation errors, 400/404/405 responses, cascading deletes |
| `WgGesuchtScraperTest` | Parsing of real-markup fixtures in `src/test/resources/wg-gesucht/` and edge cases |

The build fails if coverage drops below 95% of lines or 90% of branches. If WG-Gesucht changes its markup, update the fixtures and `WgPages` together with the selectors in `WgGesuchtScraper`.

---

## 📁 Project Structure

```
src/main/java/com/isaralert/
├── IsarAlertApplication.java       # Entry point
├── config/                         # Spring configuration (properties, scheduler, bot, OpenAPI)
├── model/                          # JPA entities + enums
├── repository/                     # Spring Data JPA repositories
├── service/                        # Business logic (scheduler, matching, notifications, bot)
│   └── scraper/                    # WG-Gesucht scraper + shared scraper base class
├── controller/                     # REST API endpoints
├── dto/                            # Data Transfer Objects
├── event/                          # Application events (manual scan requests)
└── exception/                      # Global exception handling
src/main/resources/db/migration/    # Flyway migrations
```

---

## 🗺️ Roadmap

- More platforms (ImmoScout24, Kleinanzeigen)
- Edit/delete criteria and pick U-Bahn lines from the Telegram bot

---

## ⚠️ Legal Disclaimer

This project is for **educational and personal use only**. Web scraping may violate the Terms of Service of housing platforms. Always:

- Respect `robots.txt`
- Keep the request rate low (the defaults add a delay between every request)
- Do not use commercially or at scale
- Comply with GDPR when handling personal data

---

## 📄 License

MIT License — see [LICENSE](LICENSE) for details.
