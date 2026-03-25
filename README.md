# TradeJournal

TradeJournal is a Spring Boot + Thymeleaf trading journal application for logging trades, reviewing execution quality, tracking analytics, managing billing, and using AI-assisted screenshot import and trade review.

- Maven coordinates: `com.tradejournal:tradejournal`
- Root Java package: `com.tradejournal`

## Stack

- Java 21
- Spring Boot 4
- Spring MVC + Thymeleaf
- Spring Data JPA
- PostgreSQL for deployed environments
- SQLite for local profile
- OpenAI API for screenshot import and AI review

## Module Layout

```text
src/main/java/com/tradejournal
  TradeJournalApplication.java
  shared/config
  auth
  trade
  analytics
  setup
  mistake
  settings
  billing
  admin
  ai
```

The repository is organized as a modular monolith. Controllers stay thin, persistence remains in repositories, and feature logic is grouped by business domain instead of a global layer-first layout.

## Profiles

- `local`: SQLite at `data/trading_journal.db`, default port `8082`
- `prod`: PostgreSQL from environment variables, default port `8081`

Config files live under `src/main/resources`:

- `application.yml`
- `application-local.yml`
- `application-prod.yml`

## Local Run

Requirements:

- Java 21
- Maven wrapper included in the repo

Run locally:

```powershell
./mvnw.cmd spring-boot:run
```

The default profile is `local`, so the command above starts the app against SQLite by default.

Open `http://localhost:8082`.

## Environment Variables

- `SPRING_PROFILES_ACTIVE`
- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`
- `OPENAI_API_KEY`
- `OPENAI_BASE_URL`
- `OPENAI_TRADE_CHART_MODEL`
- `OPENAI_TRADE_REVIEW_MODEL`
- `BILLING_WEBHOOK_SECRET`
- `ADMIN_EMAIL`
- `ADMIN_USERNAME`
- `ADMIN_PASSWORD`
- `LOCAL_SERVER_PORT`

## AI Features

- Screenshot import requires `OPENAI_API_KEY`
- AI review requires `OPENAI_API_KEY`
- If AI is not configured, the rest of the app still works and AI actions should fail gracefully

## Billing / Webhooks

- Stripe-style webhook endpoint: `/webhooks/stripe`
- Protect production webhooks with `BILLING_WEBHOOK_SECRET`
- Review billing plan mapping and invoice semantics before enabling live billing

## Tests

Compile:

```powershell
./mvnw.cmd -DskipTests compile
```

Run tests:

```powershell
./mvnw.cmd test
```

## Notes

- Do not commit `.m2`, `target`, local database files, or secrets
- Local runtime data stays under `data/`
- The refactor keeps Thymeleaf templates and existing routes in place
