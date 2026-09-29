#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="${SCRIPT_DIR}/../docker/compose.perf.yml"

echo "=== Tearing down performance test database and removing volumes ==="
docker compose -f "${COMPOSE_FILE}" down -v

echo "Performance test environment cleaned up successfully."
