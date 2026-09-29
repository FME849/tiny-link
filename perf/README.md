# TinyLink Performance Testing Suite

This directory contains all performance testing assets, Docker environments, dataset seeders, and JMeter test plans—completely isolated from development code and standard unit/integration tests.

---

## Directory Structure

```
perf/
├── docker/
│   ├── compose.perf.yml        # Isolated MySQL on port 3307 (configurable buffer pool, cold-cache restart enabled)
│   └── init-schema.sql         # Base DDL for url_mapping
├── scripts/
│   ├── setup-env.sh            # Starts perf container and waits for MySQL healthcheck
│   ├── seed-data.py            # High-speed Python seeder (~1.2s for 80k authentic Snowflake Base62 IDs)
│   ├── seed-data.sh            # Shell wrapper for seeder (supports Python or Spring profile)
│   ├── export-datasets.sh      # Exports hot_codes.csv (20k newest) & cold_codes.csv (10k historical)
│   ├── teardown-env.sh         # Wipes containers and volumes (docker compose down -v)
│   └── run-perf-suite.sh       # Master pipeline orchestrator (generates Write & Read dashboards)
├── jmeter/
│   ├── write_test_20k.jmx      # 20k POST /api/v1/url requests (50 threads x 400 loops)
│   ├── read_mixed_test.jmx     # 80% Hot Reads, 20% Cold Reads, 5% 404s, latency injection & concurrent writes
│   ├── data/                   # Target directory for exported CSV files
│   └── results/                # Output directory for JTL files and HTML dashboards
└── README.md
```

---

## Realistic Simulation Features

### 1. Memory Pressure (Disk I/O Simulation)
In real production, datasets overflow server RAM. In `compose.perf.yml`, `--innodb-buffer-pool-size` defaults to **`32M`** (instead of 512M). Since 100k rows occupy ~25MB, MySQL cannot cache everything and must perform genuine page reads and evictions.
* To run with unconstrained in-memory performance:
  ```bash
  INNODB_BUFFER_POOL_SIZE=512M ./perf/scripts/setup-env.sh
  ```

### 2. Cold Cache Testing
MySQL 8.0 normally auto-reloads its cache on startup. We configured `--innodb-buffer-pool-load-at-startup=OFF`. Restarting the container guarantees a **100% cold cache**:
```bash
docker restart tinylink_mysql_perf
```

### 3. Simulated Network Latency & Jitter
Kill the localhost 0ms illusion by injecting simulated cross-availability-zone latency in JMeter:
```bash
jmeter -n -t perf/jmeter/read_mixed_test.jmx \
       -l perf/jmeter/results/read_run.jtl \
       -Jlatency=15 -Jjitter=5
```
*(Adds $15 \pm 5\text{ ms}$ Gaussian delay to simulate real client-to-server networks).*

### 4. Concurrent Interleaved Reads + Writes
The test plan runs **two parallel Thread Groups simultaneously**:
* **Thread Group 1 (Reads):** 45 threads executing 80/20 hot/cold reads.
* **Thread Group 2 (Writes):** 5 threads executing continuous `POST /api/v1/url`.
You can customize the concurrency ratio via CLI parameters:
```bash
# 90% Read / 10% Write (default):
jmeter -n -t perf/jmeter/read_mixed_test.jmx -Jread_threads=45 -Jwrite_threads=5

# 100% Pure Read (0 writes):
jmeter -n -t perf/jmeter/read_mixed_test.jmx -Jread_threads=50 -Jwrite_threads=0

# 80% Read / 20% Write:
jmeter -n -t perf/jmeter/read_mixed_test.jmx -Jread_threads=40 -Jwrite_threads=10
```

---

## Quick Start Runbook

### Automatic End-to-End Suite
Runs environment startup, 80k seeding, 20k writes, cold cache restart, 5-minute read load test, and generates HTML dashboards for both write and read flows:
```bash
./perf/scripts/run-perf-suite.sh
```

### Step-by-Step Manual Execution

#### Step 1: Start the Isolated Perf Database
Starts MySQL on port `3307`:
```bash
./perf/scripts/setup-env.sh
```

#### Step 2: Pre-seed 80,000 Historical Snowflake Records
Populates 80,000 authentic records in ~1 second:
```bash
./perf/scripts/seed-data.sh
```

#### Step 3: Start TinyLink with the `perf` Profile
In a separate terminal, launch the application pointing to port 3307:
```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=perf
```

#### Step 4: Run the JMeter Write Load Test (20,000 requests)
Inserts 20,000 live records via HTTP `POST /api/v1/url`, bringing the total table size to **100,000 rows**:
```bash
jmeter -n -t perf/jmeter/write_test_20k.jmx \
       -l perf/jmeter/results/write_run.jtl \
       -e -o perf/jmeter/results/write_dashboard
```

#### Step 5: Export Hot & Cold Datasets
Exports the 20,000 live written rows as `hot_codes.csv` and older rows as `cold_codes.csv`:
```bash
./perf/scripts/export-datasets.sh
```

#### Step 6: (Optional) Flush to Cold Cache
```bash
docker restart tinylink_mysql_perf
```

#### Step 7: Run the JMeter Read & Mixed Load Test
Executes realistic Pareto (80/20) read traffic with ample JVM heap for HTML generation:
```bash
JVM_ARGS="-Xms1g -Xmx6g" jmeter -n -t perf/jmeter/read_mixed_test.jmx \
       -l perf/jmeter/results/read_run.jtl \
       -e -o perf/jmeter/results/read_dashboard
```

#### Step 8: View HTML Dashboards
```bash
open perf/jmeter/results/write_dashboard/index.html
open perf/jmeter/results/read_dashboard/index.html
```

#### Step 9: Teardown & Clean Up
Stops the container and wipes all test data cleanly:
```bash
./perf/scripts/teardown-env.sh
```
