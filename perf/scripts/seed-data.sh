#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="${SCRIPT_DIR}/../.."
ROWS=${1:-80000}
MODE=${2:-"python"}

echo "=== Seeding ${ROWS} authentic historical Snowflake records into tinylink_perf ==="

if [ "${MODE}" = "spring" ]; then
    echo "Using Spring Boot @Profile('perf-seed') seeder..."
    cd "${PROJECT_ROOT}"
    ./mvnw spring-boot:run -Dspring-boot.run.profiles=perf,perf-seed
else
    echo "Using high-speed Python seeder..."
    python3 "${SCRIPT_DIR}/seed-data.py" "${ROWS}"
fi

# Verify record count
TOTAL=$(docker exec -i tinylink_mysql_perf mysql -uadmin -psecret tinylink_perf \
  -e "SELECT COUNT(*) FROM url_mapping;" --batch --skip-column-names)

echo "Verification: url_mapping currently contains ${TOTAL} rows."
