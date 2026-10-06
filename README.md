# TinyLink

TinyLink is a URL shortener backend built with Spring Boot and Java 25. The project focuses on test-driven development (TDD), caching patterns, and high-concurrency design using MySQL, Redis, and Testcontainers.

Live demo: [https://tinylink-9lxg.onrender.com](https://tinylink-9lxg.onrender.com)

## What it does

- Shortens long URLs into compact alphanumeric codes.
- Redirects short links to destination URLs with an HTTP 302 status.
- Caches short code lookups in Redis using a cache-aside pattern with TTL.
- Caches negative lookups to prevent cache penetration on missing links.
- Tracks link clicks in Redis atomically and flushes counters to MySQL in scheduled batches.
- Exposes interactive OpenAPI documentation via Swagger UI.
- Runs integration tests against isolated MySQL and Redis containers using Testcontainers.
- Builds and packages container images automatically on release via GitHub Actions.

## Architecture

```
[ Client / Browser ]
         |
         v
[ Spring Boot Application (Java 25) ]
   |-- RootController          -> Redirects / to /swagger-ui.html
   |-- UrlShortenController    -> Handles /api/v1/url and /{shortCode}
   |-- UrlShortenService       -> Core shortening and lookup logic
   |     |-- SnowflakeIdGenerator -> Generates 64-bit numeric IDs
   |     `-- Base62Encoder        -> Encodes numbers into alphanumeric slugs
   |-- ClickCountSyncScheduler -> Periodic task flushing click counts
   `-- GlobalExceptionHandler  -> Formats errors as RFC 7807 ProblemDetail
         |                     |
         v                     v
   [ Redis 7 ]           [ MySQL 8 ]
   - url:{code} (cache)  - url_mapping table
   - clicks:{code}
```

### Request flow

1. **Shorten URL (`POST /api/v1/url`)**:
   - The controller validates the incoming URL format (`@NotBlank`, `@Pattern`, `@URL`).
   - `SnowflakeIdGenerator` generates a unique 64-bit ID, which `Base62Encoder` converts into a short string.
   - The mapping is saved to MySQL (`url_mapping` table).
   - The long URL is written to Redis (`url:<shortCode>`) with a 7-day TTL.
   - The API returns `201 Created` with a `Location` header and payload.

2. **Redirect (`GET /{shortCode}`)**:
   - The service checks Redis for `url:<shortCode>`.
   - **Cache hit**: If the value is the `$$NOT_FOUND$$` sentinel, it returns a 404 immediately. Otherwise, it increments `clicks:<shortCode>` in Redis and issues an HTTP 302 redirect.
   - **Cache miss**: The service queries MySQL. If found, it populates Redis with a 7-day TTL, increments the Redis click counter, and redirects. If not found in MySQL, it writes `$$NOT_FOUND$$` to Redis with a 2-minute TTL to shield the database from repeated misses, then returns 404.

3. **Click sync (`ClickCountSyncScheduler`)**:
   - Every 30 seconds, a background job scans Redis for `clicks:*` keys.
   - It reads and clears each count atomically using `GETDEL`, then applies the delta to MySQL in a single transaction. This keeps write locks off the redirect path.

## Tech stack

- **Runtime**: Java 25
- **Framework**: Spring Boot 4 (Spring MVC, Spring Data JPA, Spring Validation)
- **Database**: MySQL 8.x
- **Cache**: Redis 7
- **API Docs**: SpringDoc OpenAPI / Swagger UI
- **Testing**: JUnit 5, AssertJ, Spring Boot Test, Testcontainers (MySQL + Redis)
- **Containerization**: Docker, Docker Compose, GitHub Container Registry (GHCR)
- **CI/CD**: GitHub Actions

## Project structure

```text
tinylink/
|-- .github/workflows/
|   `-- deploy.yml                 # CI/CD: test, build JAR, push GHCR image
|-- perf/                          # Standalone load-testing environment
|   |-- docker/                    # Isolated MySQL and Redis setup for perf
|   |-- jmeter/                    # Test plans (.jmx) and output dashboards
|   `-- scripts/                   # Seeder and test runner scripts
|-- src/
|   |-- main/java/com/fme/tinylink/
|   |   |-- base62/                # Base62 conversion
|   |   |-- config/                # Redis and web configuration
|   |   |-- controllers/           # REST endpoints and root redirect
|   |   |-- dto/                   # Request/response records
|   |   |-- exception/             # ProblemDetail exception advice
|   |   |-- models/                # UrlMapping JPA entity
|   |   |-- repository/            # Spring Data JPA repository
|   |   |-- scheduler/             # Click count background synchronizer
|   |   |-- services/              # URL shortening and caching logic
|   |   `-- snowflake/             # 64-bit Snowflake ID generator
|   `-- test/                      # Controller and service integration tests
|-- compose.yml                    # Local MySQL and Redis containers
|-- Dockerfile                     # Multi-stage container build (Temurin 25)
`-- pom.xml                        # Maven dependencies
```

## Services and ports

| Service | Address / Port | Notes |
| :--- | :--- | :--- |
| Application API | `http://localhost:8080` | Local REST API |
| Swagger UI | `http://localhost:8080/swagger-ui.html` | Root `/` redirects here |
| Live Demo | [tinylink-9lxg.onrender.com](https://tinylink-9lxg.onrender.com) | Hosted on Render |
| MySQL | `localhost:3306` | Database (`tinylinkdb`) |
| Redis | `localhost:6379` | Cache and click counters |

## Quick start

### Prerequisites
- Java 25
- Docker and Docker Compose
- Git

### 1. Start local dependencies
Start MySQL and Redis in the background:

```bash
docker compose up -d
```

### 2. Run the application
```bash
./mvnw spring-boot:run
```

The application starts on port `8080`. Open `http://localhost:8080` in your browser to view the Swagger UI.

### 3. Run the tests
Tests spin up ephemeral MySQL and Redis containers via Testcontainers:

```bash
./mvnw clean test
```

## API endpoints

### 1. Shorten URL

```http
POST /api/v1/url
Content-Type: application/json

{
  "longUrl": "https://example.com/my-long-url"
}
```

**Response (`201 Created`):**
```json
{
  "shortUrl": "http://localhost:8080/b9XcK1",
  "longUrl": "https://example.com/my-long-url"
}
```
*Header:* `Location: http://localhost:8080/b9XcK1`

If the input is blank, lacks `http://` or `https://`, or is not a valid URL, the API returns `400 Bad Request` with an RFC 7807 error payload:

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Validation failed for one or more fields",
  "invalid_params": [
    {
      "field": "longUrl",
      "message": "URL must start with http:// or https://"
    }
  ],
  "timestamp": "2026-10-05T10:00:00Z"
}
```

### 2. Redirect to original URL

```http
GET /{shortCode}
```

**Response (`302 Found`):**
*Header:* `Location: https://example.com/my-long-url`

If the link does not exist, it returns `404 Not Found` with a ProblemDetail body:

```json
{
  "type": "about:blank",
  "title": "Resource Not Found",
  "status": 404,
  "detail": "Short code not found",
  "timestamp": "2026-10-05T10:00:00Z"
}
```

### 3. API documentation
 
Visiting `GET /` redirects directly to `/swagger-ui.html`, where all endpoints can be inspected and called interactively. In the live deployment, visit [https://tinylink-9lxg.onrender.com](https://tinylink-9lxg.onrender.com).

## Performance testing

The repository includes a dedicated performance suite in the `perf/` directory, designed to test write throughput and cache-aside reads under memory pressure.

It features:
- An isolated Docker setup running MySQL on port 3307 and Redis on 6380 (`perf/docker/compose.perf.yml`).
- A high-speed dataset seeder generating authentic Snowflake Base62 entries.
- JMeter plans for a 20,000-write load test and an 80/20 hot/cold mixed read test with latency injection.

To run the complete automated performance suite:

```bash
./perf/scripts/run-perf-suite.sh
```

See [perf/README.md](perf/README.md) and [docs/PERFORMANCE_TEST_PLAN.md](docs/PERFORMANCE_TEST_PLAN.md) for full setup instructions and metric collection.

## Roadmap

- [ ] Web frontend (SPA)
- [ ] Distributed rate limiting
- [ ] Custom alias support
- [ ] User accounts and link management dashboard
