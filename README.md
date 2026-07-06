# FinSight Backend

Spring Boot API server for news collection, filtering, persistence, user judgement records, and market data integration.

## Responsibilities

- Collect news candidates from RSS or a news API.
- Remove duplicated news URLs with Redis.
- Score news by economy and market keywords.
- Store selected news, user judgements, terms, and market links.
- Call the AI service for rewriting, term explanation, feedback, and chart docent text.

## Run

This skeleton uses Gradle project files. Add a Gradle Wrapper from a machine with Gradle installed:

```bash
gradle wrapper --gradle-version 8.14.2
./gradlew bootRun
```

The app expects PostgreSQL and Redis. You can start them from the root folder:

```bash
docker compose up -d postgres redis
```

