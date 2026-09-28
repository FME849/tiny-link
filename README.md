# TinyLink

TinyLink is a URL shortener backend built with Spring Boot and Java 25. The project is a hands-on project in test-driven development (TDD), layered architecture, and containerized testing.

## Features

- Shortens long URLs into compact alphanumeric codes.
- Redirects short links to the original destination via HTTP 302.
- Stores link records in MySQL using Spring Data JPA.
- Runs integration tests against a real MySQL container with Testcontainers.
- Automates test execution on push using GitHub Actions.

## Development workflow (TDD)

The codebase was developed test-first using the Red-Green-Refactor cycle:

1. **Red**: Define the API contract in a test before writing production code. For example, tests assert `201 Created` with a `Location` header on valid input, `302 Found` for redirects, and `400 Bad Request` on empty payloads.
2. **Green**: Write the minimal controller, service, or repository code needed to satisfy the test.
3. **Refactor**: Clean up implementation details and extract utilities while keeping the test suite green.

### Testing strategy

Integration tests in [`UrlShortenControllerTest`](/src/test/java/com/fme/tinylink/UrlShortenControllerTest.java) use `@SpringBootTest` and Testcontainers (`testcontainers-mysql`). Running against a real MySQL container avoids dialect, indexing, and constraint quirks that often slip through when using in-memory databases like H2.

Key test cases include:
- `createAndRetrieveShortenUrlTest`: Confirms URL creation returns HTTP 201, verifies the `Location` header, and checks that a GET request to the short link redirects with HTTP 302.
- `missingOrIncorrectFormatLongUrlTest`: Confirms that invalid requests return HTTP 400.

## Engineering choices

- **Layered structure**: Controllers manage HTTP mapping and status codes, services coordinate domain logic, and repositories handle database operations through Spring Data JPA.
- **Immutable DTOs**: Request and response bodies use Java 25 records (`ShortenUrlRequest` and `ShortenUrlResponse`) to keep data transfer objects simple and immutable.
- **ID generation**: A custom Snowflake ID generator produces unique 64-bit numbers, which `Base62Encoder` converts into alphanumeric short codes.
- **CI pipeline**: A GitHub Actions workflow (`deploy.yml`) spins up a MySQL service container and executes `./mvnw clean test` on every push.

## Architecture

```
[ Client / Browser ]
         |
         v  (HTTP / REST)
+---------------------------------------------------------+
|            Spring Boot Application (Java 25)            |
|                                                         |
|  [ UrlShortenController ]                               |
|         |                                               |
|         v                                               |
|  [ UrlShortenService ]                                  |
|     |-- [ SnowflakeIdGenerator ]                        |
|     `-- [ Base62Encoder ]                               |
|                                                         |
|  [ UrlRepository (Spring Data JPA) ]                    |
+---------------------------------------------------------+
         |
         v  (JDBC)
+------------------+
|  MySQL Database  |
| (url_data table) |
+------------------+
```

### Tech stack

- **Runtime**: Java 25
- **Framework**: Spring Boot 4 (Spring MVC, Spring Data JPA, Validation)
- **Database**: MySQL 8.x
- **Testing**: JUnit 5, AssertJ, Spring Boot Test, Testcontainers
- **Local environment**: Docker & Docker Compose
- **CI/CD**: GitHub Actions

## Project structure

```text
tinylink/
|-- .github/
|   `-- workflows/
|       `-- deploy.yml                 # CI/CD workflow running tests on JDK 25 & Docker
|-- src/
|   |-- main/
|   |   |-- java/com/fme/tinylink/
|   |   |   |-- base62/
|   |   |   |   `-- Base62Encoder.java         # Base62 encoding utility
|   |   |   |-- controllers/
|   |   |   |   `-- UrlShortenController.java  # REST endpoints & redirect controller
|   |   |   |-- dto/
|   |   |   |   |-- ShortenUrlRequest.java     # Request record DTO
|   |   |   |   `-- ShortenUrlResponse.java    # Response record DTO
|   |   |   |-- exception/
|   |   |   |   `-- ResourceNotFoundException.java
|   |   |   |-- models/
|   |   |   |   `-- UrlData.java               # JPA Entity for URL records
|   |   |   |-- repository/
|   |   |   |   `-- UrlRepository.java         # Spring Data JPA repository
|   |   |   |-- services/
|   |   |   |   `-- UrlShortenService.java     # Core business logic
|   |   |   |-- snowflake/
|   |   |   |   `-- SnowflakeIdGenerator.java  # ID generator
|   |   |   `-- TinyLinkApplication.java       # Spring Boot main entry point
|   |   `-- resources/
|   |       `-- application.yaml               # Datasource & JPA configuration
|   `-- test/
|       `-- java/com/fme/tinylink/
|           `-- UrlShortenControllerTest.java  # Controller integration tests
|-- compose.yml                                # Docker Compose for local MySQL
|-- pom.xml                                    # Maven project configuration
`-- README.md
```

## Services and ports

| Service | Address / Port | Description |
| :--- | :--- | :--- |
| Backend API | `http://localhost:8080` | Spring Boot REST API |
| MySQL Database | `localhost:3306` | MySQL container (`tinylinkdb`) |

## Quick start

### Prerequisites
- Java 25 (e.g., Eclipse Temurin JDK 25)
- Docker and Docker Compose
- Git

### 1. Start MySQL
```bash
docker compose up -d
```

### 2. Run the application
```bash
./mvnw spring-boot:run
```
The server runs on port `8080`.

### 3. Run the tests
```bash
./mvnw clean test
```

## API endpoints

### 1. Shorten URL

Creates a short URL from an original destination link.

```http
POST /api/v1/url
Content-Type: application/json

{
  "longUrl": "https://example.com/very/long/url"
}
```

**Response (`201 Created`):**
```json
{
  "id": 18273918237192,
  "shortUrl": "http://localhost:8080/b9XcK1",
  "longUrl": "https://example.com/very/long/url"
}
```
*Header:* `Location: http://localhost:8080/b9XcK1`

### 2. Redirect to original URL

Redirects to the target URL via HTTP 302.

```http
GET /{shortCode}
```

**Response (`302 Found`):**
*Header:* `Location: https://example.com/very/long/url`

## Request flow

### URL shortening
1. A client sends `POST /api/v1/url` with `longUrl`.
2. `UrlShortenService` calls `SnowflakeIdGenerator.nextId()` for a unique numeric ID.
3. `Base62Encoder.encode(id)` turns the number into an alphanumeric string.
4. `UrlRepository` saves the record to MySQL.
5. The controller returns `201 Created` with the short URL in both the response body and `Location` header.

### Redirection
1. A client visits `GET /{shortCode}`.
2. `UrlShortenController` queries `UrlRepository` for the matching short code.
3. If found, the controller returns `302 Found` with the original URL in the `Location` header.
4. If not found, it returns `404 Not Found`.

## Roadmap

- [ ] Redis caching for short code lookups
- [ ] Distributed rate limiting
- [ ] Click analytics and redirection metrics
- [ ] Web frontend
- [ ] Multi-stage Dockerfile and full-stack Docker Compose

---

## Deployment

For step-by-step instructions on deploying TinyLink to Render with Aiven MySQL and automated CI/CD via GitHub Actions and GHCR, refer to the [Production Deployment Guide](file:///Users/fme849/Personal/Project/tinylink/docs/DEPLOYMENT.md).

