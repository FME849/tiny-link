#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PERF_DIR="${SCRIPT_DIR}/.."
PROJECT_ROOT="${PERF_DIR}/.."
RESULTS_DIR="${PERF_DIR}/jmeter/results/run_$(date +%Y%m%d_%H%M%S)"

# Allocate ample JVM heap so JMeter can generate HTML reports for millions of samples without OOM
export JVM_ARGS="${JVM_ARGS:--Xms1g -Xmx6g}"

echo "================================================================="
echo "   TinyLink Performance Test Suite: 80k Base + 20k Write Flow    "
echo "================================================================="

# 1. Environment Setup
echo ""
echo ">>> [Step 1/6] Setting up isolated MySQL test database on port 3307..."
"${SCRIPT_DIR}/setup-env.sh"

# 2. Base Seeding
echo ""
echo ">>> [Step 2/6] Pre-seeding 80,000 historical Snowflake records..."
"${SCRIPT_DIR}/seed-data.sh" 80000

# 3. Check if app is running on port 8080
echo ""
echo ">>> [Step 3/6] Checking if TinyLink application is responding on port 8080..."
if ! curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/swagger-ui.html | grep -q "200\|302\|404"; then
    echo "NOTICE: TinyLink is not currently running on port 8080."
    echo "Please start the app in a separate terminal using:"
    echo "    ./mvnw spring-boot:run -Dspring-boot.run.profiles=perf"
    echo ""
    read -rp "Press [Enter] once the app is running on http://localhost:8080 to proceed..."
fi

# 4. JMeter Execution
if command -v jmeter >/dev/null 2>&1; then
    mkdir -p "${RESULTS_DIR}"

    # Phase 1: Write Load Test (20k rows)
    echo ""
    echo ">>> [Step 4/6] Executing JMeter Write Load Test (20,000 requests)..."
    jmeter -n -t "${PERF_DIR}/jmeter/write_test_20k.jmx" \
           -l "${RESULTS_DIR}/write_test.jtl" \
           -e -o "${RESULTS_DIR}/write_dashboard" \
           -Jhost=localhost -Jport=8080 -Jthreads=50 -Jloops=400 -Jrampup=30

    # Phase 2: Export Datasets
    echo ""
    echo ">>> [Step 5/6] Exporting hot and cold datasets..."
    "${SCRIPT_DIR}/export-datasets.sh" 20000 10000

    # Cold Cache Simulation (flush in-memory buffer pool before read test)
    echo ""
    echo ">>> Simulating Cold Cache: Restarting MySQL container (buffer pool reload disabled)..."
    docker restart tinylink_mysql_perf >/dev/null
    until [ "$(docker inspect --format='{{.State.Health.Status}}' tinylink_mysql_perf 2>/dev/null)" = "healthy" ]; do
        sleep 1
    done
    echo "    MySQL is back online with a 100% cold cache."

    # Phase 3: Read & Mixed Load Test (5 minutes)
    echo ""
    echo ">>> [Step 6/6] Executing JMeter Read & Mixed Load Test (5 minutes)..."
    jmeter -n -t "${PERF_DIR}/jmeter/read_mixed_test.jmx" \
           -l "${RESULTS_DIR}/read_test.jtl" \
           -e -o "${RESULTS_DIR}/read_dashboard" \
           -Jhost=localhost -Jport=8080 -Jdata_dir="${PERF_DIR}/jmeter/data" -Jduration=300

    echo ""
    echo "================================================================="
    echo "  Performance Tests Completed Successfully!"
    echo "  Write Dashboard : ${RESULTS_DIR}/write_dashboard/index.html"
    echo "  Read Dashboard  : ${RESULTS_DIR}/read_dashboard/index.html"
    echo "================================================================="
else
    echo ""
    echo ">>> Step 4: JMeter is not found in system PATH."
    echo "    Exporting datasets now so you can run JMeter GUI or CLI manually..."
    "${SCRIPT_DIR}/export-datasets.sh" 20000 10000
    echo ""
    echo "Test assets are prepared under:"
    echo "  - JMX files : ${PERF_DIR}/jmeter/"
    echo "  - Datasets  : ${PERF_DIR}/jmeter/data/"
fi

echo ""
echo "To view dashboards in browser:"
echo "  open ${RESULTS_DIR}/write_dashboard/index.html"
echo "  open ${RESULTS_DIR}/read_dashboard/index.html"
echo ""
echo "To clean up and drop the test database, run:"
echo "  ${SCRIPT_DIR}/teardown-env.sh"
