#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATA_DIR="${SCRIPT_DIR}/../jmeter/data"
mkdir -p "${DATA_DIR}"

HOT_COUNT=${1:-20000}
COLD_COUNT=${2:-10000}

echo "=== Exporting JMeter Datasets from tinylink_perf ==="

TOTAL_ROWS=$(docker exec -i tinylink_mysql_perf mysql -uadmin -psecret tinylink_perf \
  -e "SELECT COUNT(*) FROM url_mapping;" --batch --skip-column-names)

echo "Total rows in database: ${TOTAL_ROWS}"

# Export hot codes (the latest generated records, e.g. from write load test)
echo "Exporting top ${HOT_COUNT} newest short codes to ${DATA_DIR}/hot_codes.csv..."
docker exec -i tinylink_mysql_perf mysql -uadmin -psecret tinylink_perf \
  -e "SELECT short_code FROM url_mapping ORDER BY short_code DESC LIMIT ${HOT_COUNT};" \
  --batch --skip-column-names > "${DATA_DIR}/hot_codes.csv"

# Export cold codes (older baseline historical records)
echo "Exporting ${COLD_COUNT} older historical short codes to ${DATA_DIR}/cold_codes.csv..."
docker exec -i tinylink_mysql_perf mysql -uadmin -psecret tinylink_perf \
  -e "SELECT short_code FROM url_mapping ORDER BY short_code ASC LIMIT ${COLD_COUNT};" \
  --batch --skip-column-names > "${DATA_DIR}/cold_codes.csv"

HOT_LINES=$(wc -l < "${DATA_DIR}/hot_codes.csv" | tr -d ' ')
COLD_LINES=$(wc -l < "${DATA_DIR}/cold_codes.csv" | tr -d ' ')

echo "=== Export Complete ==="
echo "  Hot Codes  : ${HOT_LINES} rows (${DATA_DIR}/hot_codes.csv)"
echo "  Cold Codes : ${COLD_LINES} rows (${DATA_DIR}/cold_codes.csv)"
