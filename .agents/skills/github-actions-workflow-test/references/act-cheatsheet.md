# Act Cheatsheet & Troubleshooting

`act` allows you to run GitHub Actions workflows locally inside Docker containers.

---

## Essential Commands

| Purpose | Command |
| :--- | :--- |
| **List available jobs** | `act -l` |
| **Dry run (no execution)** | `act -n` |
| **Trigger manual workflow** | `act workflow_dispatch` |
| **Trigger push event** | `act push` |
| **Trigger pull request event** | `act pull_request` |
| **Run a specific job** | `act -j <job_name>` |
| **Automatically remove container on exit** | `act --rm` |
| **Specify image architecture** | `act --container-architecture linux/amd64` |
| **Pass secret** | `act -s MY_SECRET=value` |
| **Load `.env` / secret file** | `act --secret-file .secrets` |

---

## Crucial Configurations for macOS (Apple Silicon)

### 1. Apple Silicon Architecture Flag
If actions or container tools fail due to ARM64 / AMD64 emulation issues:
```bash
act workflow_dispatch --container-architecture linux/amd64
```

### 2. Docker-in-Docker (Using `docker compose` or `docker run` inside workflow)
If your workflow invokes Docker commands (like starting MySQL via `docker compose up -d`), forward the Docker daemon socket:
```bash
act workflow_dispatch --container-daemon-socket /var/run/docker.sock
```

### 3. Choosing Runner Images
`act` supports three runner sizes configured in `~/.actrc`:
- **Micro**: smallest (~200MB), bare minimum tools.
- **Medium**: balanced (~500MB - 1GB), standard tools.
- **Large**: full GitHub environment (~18GB), includes almost all preinstalled tools.

Example `~/.actrc`:
```text
-P ubuntu-latest=catthehacker/ubuntu:act-latest
-P ubuntu-22.04=catthehacker/ubuntu:act-22.04
```

---

## Troubleshooting Common Errors

### "Cannot connect to the Docker daemon"
* Cause: Docker Desktop is closed.
* Fix: Launch Docker Desktop and wait until the engine status shows "running".

### "Error: You are using Apple M-series chip..."
* Cause: Architecture mismatch for x86 Docker images.
* Fix: Append `--container-architecture linux/amd64`.

### "docker: command not found inside runner"
* Cause: The default micro/medium image may not include the Docker CLI.
* Fix: Either use a runner image that includes Docker (`catthehacker/ubuntu:full-latest`) or use GitHub Actions native `services:` block instead of running `docker compose` inside the step.
