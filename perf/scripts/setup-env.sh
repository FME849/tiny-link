#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="${SCRIPT_DIR}/../docker/compose.perf.yml"

echo "=== [1/3] Starting isolated MySQL (3307) and Redis (6380) containers ==="
docker compose -f "${COMPOSE_FILE}" up -d

echo "=== [2/3] Waiting for MySQL container to become healthy ==="
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
    docker compose -f "${COMPOSE_FILE}" logs mysql-perf
    exit 1
fi

echo "=== [3/3] Waiting for Redis container to become healthy ==="
REDIS_RETRIES=15
until [ "${REDIS_RETRIES}" -le 0 ]; do
    REDIS_STATUS=$(docker inspect --format='{{.State.Health.Status}}' tinylink_redis_perf 2>/dev/null || echo "starting")
    if [ "${REDIS_STATUS}" = "healthy" ]; then
        echo "Redis is healthy and ready on port 6380!"
        break
    fi
    echo "  Waiting for Redis... (status: ${REDIS_STATUS})"
    sleep 1
    REDIS_RETRIES=$((REDIS_RETRIES - 1))
done

if [ "${REDIS_RETRIES}" -le 0 ]; then
    echo "ERROR: Redis failed to reach healthy state in time." >&2
    docker compose -f "${COMPOSE_FILE}" logs redis-perf
    exit 1
fi

echo "Performance testing environment is ready (MySQL :3307, Redis :6380)."

