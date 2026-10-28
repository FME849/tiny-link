# TinyLink Redis Integration Guide

This guide details the architecture, design rationale, and step-by-step implementation for integrating **Redis** into **TinyLink**. It focuses on achieving sub-millisecond redirect latency, eliminating database write-lock contention, and ensuring testability with Testcontainers.

---

## Architecture Overview & Problem Statement

### 1. The Bottlenecks in the Current Architecture
In the baseline MySQL-only implementation:
1. **Database Roundtrips on Read Path**: Every `GET /{shortCode}` queries MySQL InnoDB clustered B-Tree index. While B-Tree lookups are fast, database connections are limited and network I/O adds 3–15ms latency per request.
2. **Row Lock Contention on Click Tracking**: The service invokes `repository.increaseCountByShortCode(shortCode)` on every single redirect:
   ```sql
   UPDATE url_mapping SET count = count + 1 WHERE short_code = ?;
   ```
   Under high concurrency (e.g. a viral link with 1,000 req/sec), multiple transactions compete for the exact same row lock, leading to thread pool starvation, deadlocks, and latency spikes.

### 2. The Redis Solution
```
[ Client Request: GET /{shortCode} ]
               │
               ▼
      [ Spring Boot API ]
               │
      1. Check Redis Cache (`url:{shortCode}`)
         ├── Cache HIT ──► 2. Redis INCR (`clicks:{shortCode}`) ──► Return 302 Found
         │
         └── Cache MISS
               │
               ├─► 3. Query MySQL (`findByShortCode`)
               ├─► 4. Populate Redis with TTL (`url:{shortCode}`)
               ├─► 5. Redis INCR (`clicks:{shortCode}`)
               └─► 6. Return 302 Found

[ Background Worker (@Scheduled) ]
               │
               ▼ Every 30s
Reads Redis (`clicks:*`), atomically gets delta, and batch-updates MySQL
```

---

## Table of Contents

1. [Phase 1: Dependencies & Container Infrastructure](#phase-1-dependencies--container-infrastructure)
2. [Phase 2: Application Configuration](#phase-2-application-configuration)
3. [Phase 3: Redis Spring Configuration Bean](#phase-3-redis-spring-configuration-bean)
4. [Phase 4: Service Layer Refactoring (Cache-Aside & Atomic Counter)](#phase-4-service-layer-refactoring)
5. [Phase 5: Asynchronous Click Count Synchronizer](#phase-5-asynchronous-click-count-synchronizer)
6. [Phase 6: Integration Testing with Testcontainers](#phase-6-integration-testing-with-testcontainers)
7. [Phase 7: Performance Impact & Best Practices](#phase-7-performance-impact--best-practices)

---

## Phase 1: Dependencies & Container Infrastructure

### Step 1.1: Verify Maven Dependencies
Ensure `pom.xml` contains the Spring Boot Starter Data Redis dependencies:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>

<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis-test</artifactId>
    <scope>test</scope>
</dependency>
```

### Step 1.2: Add Redis to Local Docker Compose (`compose.yml`)
Add a Redis service alongside MySQL:

```yaml
services:
  mysql:
    image: mysql:latest
    container_name: tinylink_mysql
    environment:
      - MYSQL_DATABASE=tinylinkdb
      - MYSQL_PASSWORD=secret
      - MYSQL_ROOT_PASSWORD=verysecret
      - MYSQL_USER=admin
    ports:
      - "3306:3306"

  redis:
    image: redis:7-alpine
    container_name: tinylink_redis
    ports:
      - "6379:6379"
    command: ["redis-server", "--appendonly", "yes"]
    volumes:
      - redis_data:/data

volumes:
  redis_data:
```

---

## Phase 2: Application Configuration

Update `src/main/resources/application.yaml` with configurable Redis connection and Lettuce pool properties:

```yaml
spring:
  application:
    name: tinylink

  data:
    redis:
      host: ${SPRING_REDIS_HOST:localhost}
      port: ${SPRING_REDIS_PORT:6379}
      password: ${SPRING_REDIS_PASSWORD:}
      timeout: 2000ms
      client-type: lettuce
      lettuce:
        pool:
          max-active: 16
          max-idle: 8
          min-idle: 2
          max-wait: 1000ms

  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:mysql://localhost:3306/tinylinkdb}
    username: ${SPRING_DATASOURCE_USERNAME:admin}
    password: ${SPRING_DATASOURCE_PASSWORD:secret}
    driver-class-name: com.mysql.cj.jdbc.Driver

  jpa:
    hibernate:
      ddl-auto: ${SPRING_JPA_HIBERNATE_DDL_AUTO:update}
    show-sql: false
```

---

## Phase 3: Redis Spring Configuration Bean

For maximum throughput and low memory footprint, `StringRedisTemplate` is preferred over generic object serializers because short URLs map simply from string to string (`shortCode` $\to$ `longUrl`).

Create `src/main/java/com/fme/tinylink/config/RedisConfig.java`:

```java
package com.fme.tinylink.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
```

---

## Phase 4: Service Layer Refactoring

Refactor `src/main/java/com/fme/tinylink/services/UrlShortenService.java`:
- **Write Path**: Pre-warm the cache immediately when generating a new short link.
- **Read Path**: Check Redis first. On cache miss, read from MySQL and populate Redis with a TTL.
- **Click Tracking**: Increment in-memory counter with `opsForValue().increment(...)` instead of writing to MySQL.

```java
package com.fme.tinylink.services;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.fme.tinylink.base62.Base62Encoder;
import com.fme.tinylink.exception.ResourceNotFoundException;
import com.fme.tinylink.models.UrlMapping;
import com.fme.tinylink.repository.UrlRepository;
import com.fme.tinylink.snowflake.SnowflakeIdGenerator;

@Service
public class UrlShortenService {

    public static final String URL_CACHE_PREFIX = "url:";
    public static final String CLICK_COUNT_PREFIX = "clicks:";
    private static final Duration CACHE_TTL = Duration.ofDays(7);
    private static final String NOT_FOUND_SENTINEL = "$$NOT_FOUND$$";
    private static final Duration NULL_CACHE_TTL = Duration.ofMinutes(2); // Prevents cache penetration

    private final UrlRepository repository;
    private final SnowflakeIdGenerator idGenerator;
    private final StringRedisTemplate redisTemplate;

    public UrlShortenService(UrlRepository repository, SnowflakeIdGenerator idGenerator, StringRedisTemplate redisTemplate) {
        this.repository = repository;
        this.idGenerator = idGenerator;
        this.redisTemplate = redisTemplate;
    }

    public UrlMapping createShortUrl(String longUrl) {
        idGenerator.setDataCenterId(1);
        idGenerator.setWorkerId(1);
        Long nextId = idGenerator.nextId();
        String shortCode = Base62Encoder.encode(nextId);

        UrlMapping model = new UrlMapping(shortCode, longUrl);
        UrlMapping saved = repository.save(model);

        // Pre-warm cache on write
        redisTemplate.opsForValue().set(URL_CACHE_PREFIX + shortCode, longUrl, CACHE_TTL);

        return saved;
    }

    public String getLongUrlByShortCode(String shortCode) {
        String cacheKey = URL_CACHE_PREFIX + shortCode;

        // 1. Check Redis Cache
        String cachedLongUrl = redisTemplate.opsForValue().get(cacheKey);

        if (cachedLongUrl != null) {
            if (NOT_FOUND_SENTINEL.equals(cachedLongUrl)) {
                throw new ResourceNotFoundException("Short code not found");
            }
            // Atomic counter in Redis (no DB write lock!)
            redisTemplate.opsForValue().increment(CLICK_COUNT_PREFIX + shortCode);
            return cachedLongUrl;
        }

        // 2. Cache Miss: Fallback to MySQL
        UrlMapping mapping = repository.findByShortCode(shortCode).orElse(null);

        if (mapping == null) {
            // Cache empty sentinel with short TTL to prevent Cache Penetration attack
            redisTemplate.opsForValue().set(cacheKey, NOT_FOUND_SENTINEL, NULL_CACHE_TTL);
            throw new ResourceNotFoundException("Short code not found");
        }

        // 3. Write back to Redis
        redisTemplate.opsForValue().set(cacheKey, mapping.getLongURL(), CACHE_TTL);
        redisTemplate.opsForValue().increment(CLICK_COUNT_PREFIX + shortCode);

        return mapping.getLongURL();
    }
}
```

Update `UrlShortenController.java`'s `redirectToUrl` method:
```java
@GetMapping("/{shortCode}")
public ResponseEntity<Void> redirectToUrl(@PathVariable String shortCode) {
    String longUrl = services.getLongUrlByShortCode(shortCode);
    return ResponseEntity
            .status(HttpStatus.FOUND)
            .header("Location", longUrl)
            .build();
}
```

---

## Phase 5: Asynchronous Click Count Synchronizer

To update MySQL with the click counts accumulated in Redis without compromising redirect performance, use a scheduled batch synchronizer.

### Step 5.1: Enable Scheduling
In `src/main/java/com/fme/tinylink/TinyLinkApplication.java`:
```java
@SpringBootApplication
@EnableScheduling
public class TinyLinkApplication { ... }
```

### Step 5.2: Create the Synchronizer Component
Create `src/main/java/com/fme/tinylink/scheduler/ClickCountSyncScheduler.java`:

```java
package com.fme.tinylink.scheduler;

import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fme.tinylink.repository.UrlRepository;
import com.fme.tinylink.services.UrlShortenService;

@Component
public class ClickCountSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(ClickCountSyncScheduler.class);

    private final StringRedisTemplate redisTemplate;
    private final UrlRepository repository;

    public ClickCountSyncScheduler(StringRedisTemplate redisTemplate, UrlRepository repository) {
        this.redisTemplate = redisTemplate;
        this.repository = repository;
    }

    @Scheduled(fixedRate = 30000) // Flush every 30 seconds
    @Transactional
    public void syncClicksToDatabase() {
        Set<String> keys = redisTemplate.keys(UrlShortenService.CLICK_COUNT_PREFIX + "*");
        if (keys == null || keys.isEmpty()) return;

        for (String key : keys) {
            String shortCode = key.replace(UrlShortenService.CLICK_COUNT_PREFIX, "");
            // Atomically read and delete the counter key
            String countStr = redisTemplate.opsForValue().getAndDelete(key);
            if (countStr != null) {
                int delta = Integer.parseInt(countStr);
                if (delta > 0) {
                    repository.increaseCountByShortCode(shortCode);
                }
            }
        }
        log.info("Synced {} active click counters from Redis to MySQL", keys.size());
    }
}
```

---

## Phase 6: Integration Testing with Testcontainers

When testing with Testcontainers, spin up both MySQL and Redis containers in `src/test/java/com/fme/tinylink/AbstractIntegrationTest.java`:

```java
package com.fme.tinylink;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.HttpURLConnection;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    protected static final MySQLContainer<?> mysql;

    protected static final GenericContainer<?> redis;

    static {
        mysql = new MySQLContainer<>("mysql:8.0");
        mysql.start();

        redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379);
        redis.start();
    }

    @DynamicPropertySource
    static void configureRedisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate restTemplate;

    protected String BASE_URL;
    protected String BASE_API_URL;

    @BeforeEach
    void baseSetup() {
        this.BASE_URL = "http://localhost:" + port;
        this.BASE_API_URL = BASE_URL + "/api/v1";

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };

        restTemplate.getRestTemplate().setRequestFactory(requestFactory);
    }
}
```

---

## Phase 7: Performance Impact & Best Practices

| Metric / Scenario | Without Redis (MySQL Only) | With Redis Cache & INCR |
| :--- | :--- | :--- |
| **`GET /{shortCode}` Latency** | 5 – 15 ms | **< 1 ms (Sub-millisecond)** |
| **Lock Contention on Click Count** | Severe under concurrent hits | **Zero lock contention** |
| **Cache Penetration Protection** | Missing (repeated 404s hit MySQL) | Protected via sentinel value (`$$NOT_FOUND$$`) |
| **Cache Avalanche Protection** | N/A | Add jitter (e.g. `TTL ± random seconds`) |
| **Memory Footprint** | N/A | Low (raw string key-value mappings) |
