# TinyLink Performance Testing Plan

This document outlines the end-to-end performance and load testing strategy for **TinyLink**. It details environment isolation, test data generation, realistic workload modeling (Pareto 80/20), and JMeter execution workflows.

---

## 1. Objectives & Scope

1. **Write Capacity (`POST /api/v1/url`)**: Measure write throughput (TPS), latency percentiles (p50, p95, p99), and verify Snowflake ID generation under high thread concurrency without key collisions.
2. **Read / Redirect Latency (`GET /{shortCode}`)**: Benchmark index lookup performance (`short_code` B-Tree index) and HTTP 302 redirect latency against an established database containing **100,000+ records**.
3. **Blended Real-World Workload**: Simulate production traffic distribution (**80% Reads / 20% Writes** or 90/10) to validate database connection pool sizing, cache behavior, and CPU/memory stability over time.

---

## 2. Environment Isolation & Lifecycle Strategy

> [!IMPORTANT]
> **Never run performance tests against the local development database.**
> Performance testing requires a pristine, isolated, and reproducible state. Running tests against a dev database introduces noise, skew from lingering locks, and pollutes manual development data.

### Isolated Docker Setup (`compose.perf.yml`)
Run the performance testing database and cache on isolated ports (`3307` and `6380`) using a dedicated Docker Compose file:

```yaml
# compose.perf.yml
services:
  mysql-perf:
    image: mysql:latest
    container_name: tinylink_mysql_perf
    environment:
      - MYSQL_DATABASE=tinylink_perf
      - MYSQL_ROOT_PASSWORD=perfsecret
      - MYSQL_USER=admin
      - MYSQL_PASSWORD=secret
    ports:
      - "3307:3306"
    command: >
      --innodb-buffer-pool-size=${INNODB_BUFFER_POOL_SIZE:-32M}
      --innodb-buffer-pool-load-at-startup=OFF
      --max-connections=300
      --local-infile=1
    volumes:
      - ./init-schema.sql:/docker-entrypoint-initdb.d/init-schema.sql

  redis-perf:
    image: redis:7-alpine
    container_name: tinylink_redis_perf
    ports:
      - "6380:6379"
    command: ["redis-server", "--appendonly", "no", "--maxmemory", "256mb", "--maxmemory-policy", "allkeys-lru"]
```

### Create-Drop Lifecycle
The environment is ephemeral and self-seeding:

```bash
# 1. Spin up a fresh database and auto-seed 100k rows (takes ~3s)
docker compose -f compose.perf.yml up -d

# 2. Run the load test...

# 3. Completely wipe and drop the database volume
docker compose -f compose.perf.yml down -v
```

---

## 3. Test Data Strategy: The 80/20 Split Architecture

Instead of blindly inserting 100,000 rows via HTTP (too slow) or 100% via SQL (misses write load testing), we employ an **80/20 Hybrid Data Strategy**:

* **80% Base Data (80,000 rows)**: Pre-seeded via pure SQL in ~1 second. This creates an authentic B-Tree index structure and depth before any test begins.
* **20% Live Ingestion (20,000 rows)**: Generated dynamically by the **JMeter Write Load Test** (`POST /api/v1/url`). This benchmarks insert throughput, Snowflake ID concurrency, and populates the remaining records to reach the target **100,000 rows**.

```
[Clean DB] 
   └── 1. Pre-seed 80,000 rows via SQL (~1s)       --> DB has 80,000 rows
   └── 2. JMeter Write Test (20,000 HTTP POSTs)     --> DB reaches 100,000 rows
   └── 3. JMeter Read Test (80% hot / 20% cold)     --> Benchmark GET /{shortCode}
```

### 3.1 Step 1: Pre-Seeding 80,000 Historical Snowflake Rows

> [!IMPORTANT]
> **Why Dummy SQL Strings Corrupt B-Tree Testing:**
> In MySQL (InnoDB), `url_mapping.short_code` is the **Primary Key (Clustered Index)**. Real links use **Snowflake IDs encoded in Base62** (which are monotonically increasing and time-ordered).
> If you seed with dummy values like `'base0001'`, MySQL creates a disjoint B-Tree branch that live writes will never touch.
> 
> **The Solution**: Seed 80,000 rows with simulated **historical Snowflake IDs** (timestamps from $T - 1 \text{ hour}$ up to $T$) using the real `Base62Encoder`. When the JMeter live write test begins at time $T$, the new writes append smoothly to the right edge of the exact same B-Tree index.

#### High-Speed Java Seeder (`@Profile("perf-seed")`)
Using Spring's `JdbcTemplate.batchUpdate` with `rewriteBatchedStatements=true`, 80,000 authentic Snowflake records load in **~1.2 seconds**:

```java
@Component
@Profile("perf-seed")
public class PerfDataSeeder implements CommandLineRunner {
    private final JdbcTemplate jdbcTemplate;

    public PerfDataSeeder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(String... args) {
        int totalRows = 80_000;
        int batchSize = 2_000;
        long epoch = 1767225600000L; // Snowflake CUSTOM_EPOCH
        long baseTimestamp = System.currentTimeMillis() - 3600_000L; // 1 hour ago

        String sql = "INSERT INTO url_mapping (short_code, long_url, click_count) VALUES (?, ?, ?)";
        List<Object[]> batch = new ArrayList<>(batchSize);

        for (int i = 0; i < totalRows; i++) {
            long simulatedTime = baseTimestamp + (i * 30); // 30ms increments
            long snowflakeId = ((simulatedTime - epoch) << 22) | (1L << 17) | (1L << 12) | (i & 4095);
            String shortCode = Base62Encoder.encode(snowflakeId);
            String longUrl = "https://example.com/archive/" + i;

            batch.add(new Object[]{shortCode, longUrl, (int)(Math.random() * 50)});

            if (batch.size() == batchSize) {
                jdbcTemplate.batchUpdate(sql, batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) jdbcTemplate.batchUpdate(sql, batch);
    }
}
```

*Alternatively, if preferred purely in SQL, a MySQL stored function replicating the bitshift and Base62 character map can be executed on container startup.*

### 3.2 Step 2: Live Ingestion via Write Load Test (20,000 Rows)
* **Target**: 20,000 requests to `POST /api/v1/url`.
* **Execution**: 50 threads $\times$ 400 loops (or 100 threads $\times$ 200 loops).
* **Payload**:
  ```json
  {
    "longUrl": "https://example.org/live/${__UUID}"
  }
  ```
* **Benefit**: Measures write throughput against an **already-populated 80k-row B-Tree index** (detects page split overhead and genuine DB lock contention).
* **Result**: Total table count reaches **100,000 rows**.

### 3.3 Step 3: Realistic Read Workload (Hot vs. Cold)
The 20,000 freshly created links are physically "hot" in MySQL's `innodb_buffer_pool`. We partition the 100,000 rows into:

* **Hot Data (20% of data, 80% of read traffic)**: The 20,000 short codes created during the write test.
* **Cold Data (80% of data, 20% of read traffic)**: The 80,000 historical pre-seeded codes.
* **Negative Queries (5% of traffic)**: Non-existent codes to test 404 handling.

#### Exporting the Datasets:
```bash
# Export the 20,000 newly created codes (Hot Data)
docker exec -i tinylink_mysql_perf mysql -uadmin -psecret tinylink_perf \
  -e "SELECT short_code FROM url_mapping WHERE short_code NOT LIKE 'base%';" --batch --skip-column-names > jmeter/hot_codes.csv

### 3.4 Production Realism Modeling

To move beyond synthetic local conditions, we simulate four production characteristics:

1. **Memory Pressure (Forced Disk I/O)**:
   - In production, data volume exceeds server RAM.
   - We constrain `--innodb-buffer-pool-size=32M`. Since 100k rows occupy ~25MB, MySQL cannot keep all index and data pages in memory, forcing genuine LRU page evictions and disk reads.
   - To benchmark pure in-memory baseline instead, override with `INNODB_BUFFER_POOL_SIZE=512M`.
2. **Cold Cache Testing**:
   - MySQL 8.0 auto-dumps and reloads the buffer pool by default. With `--innodb-buffer-pool-load-at-startup=OFF`, running `docker restart tinylink_mysql_perf` produces a guaranteed 100% cold cache to benchmark cache warm-up curves.
3. **Simulated Network Latency & Jitter**:
   - Localhost loopback has $<0.05\text{ ms}$ latency. Passing `-Jlatency=15 -Jjitter=5` injects a $15 \pm 5\text{ ms}$ Gaussian delay to simulate real client-to-server networks across VPCs.
4. **JMeter JVM Heap for Large Datasets**:
   - Because high-throughput runs produce millions of rows (e.g. 2.9M requests = 360MB `.jtl`), set `export JVM_ARGS="-Xms1g -Xmx6g"` before running JMeter report generation to avoid out-of-memory errors.

---

## 4. Spring Boot Configuration for Testing

Create `src/main/resources/application-perf.yaml`:

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3307/tinylink_perf?rewriteBatchedStatements=true&useSSL=false
    username: admin
    password: secret
    hikari:
      maximum-pool-size: 30
      minimum-idle: 10
      idle-timeout: 30000
      connection-timeout: 5000
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
```

Start the application with the `perf` profile:
```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=perf
```

---

## 5. JMeter Test Scenarios

### Scenario 1: Isolated Write Test (Capacity & Concurrency)
* **Goal**: Measure max write TPS and identify database lock contention or thread synchronization bottlenecks in `SnowflakeIdGenerator`.
* **Endpoint**: `POST /api/v1/url`
* **Payload**:
  ```json
  {
    "longUrl": "https://example.org/item/${__UUID}"
  }
  ```
* **Thread Profile**:
  - Ramp-up: 60 seconds (from 1 to 50 threads).
  - Steady state: 3–5 minutes.
  - Ramp-down: 30 seconds.

### Scenario 2: Isolated Read Test (Index & Redirect)
* **Goal**: Measure read latency under an existing 100k-row table.
* **Endpoint**: `GET /${shortCode}`
* **JMeter Config**:
  - **Do NOT** follow redirects (uncheck "Follow Redirects" to benchmark TinyLink's `302 Found` response time, not the external site).
  - **Throughput Controllers**:
    - 75% traffic: `CSV Data Set Config` pointing to `hot_codes.csv`.
    - 20% traffic: `CSV Data Set Config` pointing to `cold_codes.csv`.
    - 5% traffic: Random non-existent strings (tests 404 handling).

### Scenario 3: Production Mixed Load (80% Read / 20% Write)
* **Goal**: End-to-end operational benchmark testing concurrent read and write connection usage.
* **Structure**: A single Thread Group with a **Weighted Switch Controller** or **Throughput Controllers** splitting requests:
  - 80% `GET /{shortCode}`
  - 20% `POST /api/v1/url`
* **Duration**: 10 minutes steady state.

---

## 6. Target SLAs & Evaluation Metrics

| Metric | Target SLA (Local Baseline) | Critical Threshold |
| :--- | :--- | :--- |
| **Read Latency (p95)** | $< 15 \text{ ms}$ | $> 50 \text{ ms}$ |
| **Read Latency (p99)** | $< 35 \text{ ms}$ | $> 100 \text{ ms}$ |
| **Write Latency (p95)** | $< 40 \text{ ms}$ | $> 120 \text{ ms}$ |
| **Write Latency (p99)** | $< 80 \text{ ms}$ | $> 250 \text{ ms}$ |
| **Error Rate (5xx)** | $0.00\%$ | $> 0.1\%$ |
| **HikariCP Pending Connections** | $0$ | $> 5$ (indicates pool starvation) |

---

## 7. Execution Runbook

```bash
# 1. Start clean perf database on port 3307 (32M buffer pool for memory pressure)
docker compose -f perf/docker/compose.perf.yml up -d

# 2. Seed 80,000 historical Snowflake IDs (~1.2s)
python3 perf/scripts/seed-data.py 80000

# 3. Start TinyLink service for load testing
./mvnw spring-boot:run -Dspring-boot.run.profiles=perf

# 4. Phase 1: Run JMeter Write Load Test (20k records -> reaches 100k total)
#    Generates Write Dashboard automatically
jmeter -n -t perf/jmeter/write_test_20k.jmx \
       -l perf/jmeter/results/write_run.jtl \
       -e -o perf/jmeter/results/write_dashboard

# 5. Extract Datasets for Read Test (Hot vs. Cold)
./perf/scripts/export-datasets.sh 20000 10000

# 6. (Optional) Force Cold Cache: Restart MySQL container (cache reload disabled)
docker restart tinylink_mysql_perf

# 7. Phase 2: Run JMeter Read / Mixed Load Test (80% hot / 20% cold)
#    Use ample heap (4-6G) for processing millions of samples into HTML reports
JVM_ARGS="-Xms1g -Xmx6g" jmeter -n -t perf/jmeter/read_mixed_test.jmx \
       -l perf/jmeter/results/read_run.jtl \
       -e -o perf/jmeter/results/read_dashboard

# 8. View Dashboards in Browser
open perf/jmeter/results/write_dashboard/index.html
open perf/jmeter/results/read_dashboard/index.html

# 9. Clean up and drop the test database
./perf/scripts/teardown-env.sh
```
