#!/usr/bin/env bash
# Utility script to inspect act cache and Docker resource consumption

set -e

echo "=================================================="
echo "      ACT & DOCKER STORAGE CONSUMPTION CHECK      "
echo "=================================================="

echo ""
echo "--- 1. Action Cache (~/.cache/act) ---"
if [ -d "$HOME/.cache/act" ]; then
  du -sh "$HOME/.cache/act"
  echo "Contents:"
  ls -lh "$HOME/.cache/act"
else
  echo "No ~/.cache/act directory found."
fi

echo ""
echo "--- 2. Docker Daemon Status ---"
if docker info >/dev/null 2>&1; then
  echo "Docker is running."
  echo ""
  echo "--- Docker System Disk Usage ---"
  docker system df

  echo ""
  echo "--- Act-related Docker Images ---"
  docker images | grep -E 'act|catthehacker' || echo "No act-specific images found."

  echo ""
  echo "--- Leftover Act Containers ---"
  act_containers=$(docker ps -a --filter "name=act-" --format "{{.ID}} {{.Image}} {{.Status}} {{.Names}}")
  if [ -n "$act_containers" ]; then
    echo "$act_containers"
    echo ""
    echo "Tip: Run 'docker rm -f \$(docker ps -aq --filter \"name=act-\")' to remove these containers."
  else
    echo "No leftover act containers."
  fi
else
  echo "Docker is NOT currently running. Start Docker Desktop to inspect Docker storage."
fi

echo ""
echo "=================================================="
echo "To clean up all unused Docker resources, run:"
echo "  docker system prune -f"
echo "To clean up act cache, run:"
echo "  rm -rf ~/.cache/act"
echo "=================================================="
