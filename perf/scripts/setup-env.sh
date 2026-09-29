#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="${SCRIPT_DIR}/../docker/compose.perf.yml"

echo "=== [1/2] Starting isolated MySQL performance container on port 3307 ==="
docker compose -f "${COMPOSE_FILE}" up -d

echo "=== [2/2] Waiting for MySQL container to become healthy ==="
RETRIES=30
until [ "${RETRIES}" -le 0 ]; do
    STATUS=$(docker inspect --format='{{.State.Health.Status}}' tinylink_mysql_perf 2>/dev/null || echo "starting")
    if [ "${STATUS}" = "healthy" ]; then
        echo "MySQL is healthy and ready on port 3307!"
        break
    fi
    echo "  Waiting for MySQL... (status: ${STATUS})"
    sleep 2
    RETRIES=$((RETRIES - 1))
done

if [ "${RETRIES}" -le 0 ]; then
    echo "ERROR: MySQL failed to reach healthy state in time." >&2
    docker compose -f "${COMPOSE_FILE}" logs
    exit 1
fi

echo "Performance testing environment is ready."
