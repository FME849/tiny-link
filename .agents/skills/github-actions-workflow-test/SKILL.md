---
name: github-actions-workflow-test
description: >-
  Skill to design, create, review, and validate GitHub Actions workflows, and test them locally using act and actionlint before pushing to GitHub. Use this skill when the user asks to create or optimize CI/CD workflows, test workflows locally with act, validate GitHub Actions syntax, debug failed workflow runs, or monitor and clean up local Docker and act storage.
---

# GitHub Actions Workflow Creation & Local Testing Skill

This skill guides the end-to-end process of designing, writing, reviewing, and locally testing GitHub Actions workflows before pushing changes to GitHub.

---

## Workflow Lifecycle Overview

```text
1. Requirements & Spec Analysis
       ↓
2. Workflow Authoring (Best Practices & Pitfalls)
       ↓
3. Static Validation & Linting (actionlint)
       ↓
4. Local Execution & Verification (act + Docker)
       ↓
5. Storage & Artifact Management (Cleanup)
```

---

## Step 1: Workflow Design & Authoring

When creating or modifying `.github/workflows/<name>.yml`:

1. **Triggers (`on:`)**:
   - For manual triggers: use `workflow_dispatch:`.
   - For automatic PR/Push triggers: specify target branches (e.g. `branches: [main, develop]`) and optionally paths (e.g. `paths-ignore: ['**.md', 'docs/**']`).
2. **Runner Label (`runs-on:`)**:
   - Always use standard GitHub-hosted labels: `ubuntu-latest`, `ubuntu-24.04`, `ubuntu-22.04`, `windows-latest`, `macos-latest`.
   - ⚠️ Never use non-existent runners such as `ubuntu-slim`.
3. **Environment & Runtimes**:
   - Pin major action versions (e.g. `actions/checkout@v4`, `actions/setup-java@v4`, `actions/setup-node@v4`).
   - Enable package manager caching (`cache: 'maven'`, `cache: 'npm'`, `cache: 'pip'`).
4. **Service Dependencies (Databases/Caches)**:
   - If tests require MySQL, Postgres, Redis, etc., either:
     - Use GitHub Actions `services:` with health checks (`options: --health-cmd="..."`).
     - Or start the project's `compose.yml` with `docker compose up -d` followed by a readiness wait loop (`until ...; do sleep 2; done`).
5. **Script Permissions**:
   - Ensure scripts and wrappers have executable permissions before execution:
     ```yaml
     - name: Make wrapper executable
       run: chmod +x ./mvnw # or ./gradlew
     ```
6. **Artifact Archiving**:
   - Preserve test reports and failure logs using `actions/upload-artifact@v4` with `if: always()` or `if: failure()`.

---

## Step 2: Static Validation (Pre-flight Linting)

Before running containers, validate the workflow syntax and schema.

1. **Using `actionlint`**:
   ```bash
   actionlint .github/workflows/<workflow-file>.yml
   ```
   *Checks for: syntax errors, invalid runner labels, nonexistent action inputs, shell script syntax in `run:` steps.*

---

## Step 3: Local Execution with `act`

Run and test the workflow locally using `act`.

### 1. Pre-checks
- Verify Docker daemon is running:
  ```bash
  docker info >/dev/null 2>&1 || open -a Docker
  ```
- Inspect available jobs and workflow parsing:
  ```bash
  act -l
  ```

### 2. Dry-Run Check
```bash
act -n
```

### 3. Execute Workflow Locally
Choose the appropriate command based on your workflow trigger:

- **For `workflow_dispatch` workflows**:
  ```bash
  act workflow_dispatch --rm
  ```
- **Targeting a specific job**:
  ```bash
  act -j <job-id> --rm
  ```
- **Apple Silicon (M-series) Mac**:
  If pulling or running x86 images, specify container architecture:
  ```bash
  act workflow_dispatch --container-architecture linux/amd64 --rm
  ```
- **Workflows using Docker inside steps (`docker compose`, `docker run`)**:
  Mount the host Docker daemon socket into the `act` container:
  ```bash
  act workflow_dispatch --container-daemon-socket /var/run/docker.sock --rm
  ```

---

## Step 4: Storage Monitoring & Resource Cleanup

Local `act` executions consume storage in **Docker** (runner images, containers) and in the **Action Cache** (`~/.cache/act`).

### Inspect Storage Usage:
1. **Run the storage check utility**:
   ```bash
   bash scripts/check-act-storage.sh
   ```
2. **Or run manually**:
   - Docker overall consumption:
     ```bash
     docker system df
     ```
   - `act` runner images:
     ```bash
     docker images | grep -E 'act|catthehacker'
     ```
   - Leftover `act` containers:
     ```bash
     docker ps -a --filter "name=act-"
     ```
   - Action download cache:
     ```bash
     du -sh ~/.cache/act 2>/dev/null
     ```

### Cleanup Procedure:
- Remove leftover `act` test containers:
  ```bash
  docker rm -f $(docker ps -aq --filter "name=act-") 2>/dev/null
  ```
- Clean up unused Docker images and build cache:
  ```bash
  docker system prune
  ```
- Clear cached actions (if corrupted or reclaiming disk space):
  ```bash
  rm -rf ~/.cache/act
  ```

---

## Additional References

- [Act Cheatsheet & Tips](./references/act-cheatsheet.md)
- [Common CI/CD Workflow Patterns](./references/common-workflow-patterns.md)
