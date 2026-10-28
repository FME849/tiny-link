# TinyLink Production Deployment Guide

This guide provides end-to-end instructions for deploying the **TinyLink** backend (Java 25 Spring Boot) and frontend to **Render**, with a managed **MySQL** database on **Aiven**, automated via **GitHub Actions** and **GitHub Container Registry (GHCR)**.

---

## Architecture Overview

```
[ Client Browser ]
       │
       ├──────────────► [ Render: Static Site (Frontend SPA) ] ── (24/7, No sleep)
       │                              │ (REST API / CORS)
       └──────────────► [ Render: Web Service (Spring Boot Container) ]
                                      │ (TLS/SSL JDBC)
                                      ▼
                        [ Aiven: Managed MySQL ]

─────────────────────────────────────────────────────────────────────────────
CI/CD Flow:
[ Git Push to main ] ──► [ GitHub Actions ]
                              ├─ 1. Run Tests (Testcontainers + JDK 25)
                              ├─ 2. Package JAR (`mvn package`)
                              ├─ 3. Build & Push Image (`ghcr.io`)
                              └─ 4. Call Render Deploy Hook ──► [ Render Redeploys ]
```

---

## Table of Contents

1. [Phase 1: Database Provisioning (Aiven MySQL)](#phase-1-database-provisioning-aiven-mysql)
2. [Phase 2: Codebase & Container Configuration](#phase-2-codebase--container-configuration)
3. [Phase 3: CI/CD Pipeline (GitHub Actions + GHCR)](#phase-3-cicd-pipeline-github-actions--ghcr)
4. [Phase 4: Backend Deployment (Render Web Service)](#phase-4-backend-deployment-render-web-service)
5. [Phase 5: Frontend Deployment (Render Static Site)](#phase-5-frontend-deployment-render-static-site)
6. [Phase 6: Free-Tier Optimization & Cold-Start Mitigation](#phase-6-free-tier-optimization--cold-start-mitigation)

---

## Phase 1: Database Provisioning (Aiven MySQL)

### Step 1.1: Create MySQL Service on Aiven
1. Sign in or register at [aiven.io](https://aiven.io).
2. Click **Create Service** and choose **MySQL**.
3. Select cloud provider and region closest to your target audience.
4. Select plan and launch service.

> **Why:** You cannot boot Spring Boot in production without an active database connection. HikariCP pool initialization fails on startup if the database is unreachable.

### Step 1.2: Gather Connection Parameters
From the Aiven Service **Overview** tab, copy:
- **Host**: (e.g., `mysql-xxxx.aivencloud.com`)
- **Port**: (e.g., `12345`)
- **User**: (default: `avnadmin`)
- **Password**: `<YOUR_AIVEN_PASSWORD>`

Go to the **Databases** tab in Aiven and create a database named `tinylinkdb` (or use `defaultdb`).

> **Why:** Aiven requires TLS/SSL encryption for all connections (`sslMode=REQUIRED`). These parameters will be supplied via environment variables to Spring Boot.

---

## Phase 2: Codebase & Container Configuration

### Step 2.1: Externalize Database & Port in `application.yaml`
Update [`src/main/resources/application.yaml`](file:///Users/fme849/Personal/Project/tinylink/src/main/resources/application.yaml):

```yaml
server:
  port: ${PORT:8080}

spring:
  application:
    name: tinylink

  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:mysql://localhost:3306/tinylinkdb}
    username: ${SPRING_DATASOURCE_USERNAME:admin}
    password: ${SPRING_DATASOURCE_PASSWORD:secret}
    driver-class-name: com.mysql.cj.jdbc.Driver

  jpa:
    hibernate:
      ddl-auto: ${SPRING_JPA_HIBERNATE_DDL_AUTO:update}
    show-sql: false

debug: false
```

> **Why this matters:**
> - **Avoid Data Loss:** Setting `ddl-auto: update` prevents Hibernate from dropping existing tables whenever Render spins the container down and up.
> - **Port Binding:** Render injects the container port through the `PORT` environment variable (typically `10000`). `${PORT:8080}` allows dynamic binding while maintaining local `8080` fallback.

### Step 2.2: Add CORS Configuration
When hosting frontend and backend on separate Render subdomains, browsers enforce CORS preflight checks.

Add `src/main/java/com/fme/tinylink/config/WebConfig.java`:

```java
package com.fme.tinylink.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${app.cors.allowed-origins:*}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(allowedOrigins.split(","))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false);
    }
}
```

> **Why:** Without CORS headers, frontend AJAX/fetch requests will be rejected by client browsers.

### Step 2.3: Create Lightweight Runtime `Dockerfile`
Create `Dockerfile` in the project root:

```dockerfile
FROM eclipse-temurin:25-jre-noble
WORKDIR /app

RUN groupadd -r spring && useradd -r -g spring spring
USER spring:spring

# Pre-built JAR produced by GitHub Actions runner
COPY target/*.jar app.jar

ENV PORT=8080
EXPOSE 8080

# Tuned JVM parameters for 512MB RAM constraints
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+UseSerialGC", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-jar", "app.jar"]
```

Create `.dockerignore`:
```
target/
!target/*.jar
.git/
.mvn/
*.md
```

> **Why:**
> - Building the JAR in GitHub Actions avoids compiling on Render (which is CPU/RAM-constrained).
> - `-XX:MaxRAMPercentage=75.0` caps heap to ~384MB on 512MB instances, reserving space for metaspace and OS threads.
> - `-XX:+UseSerialGC` minimizes GC thread memory overhead.

---

## Phase 3: CI/CD Pipeline (GitHub Actions + GHCR)

### Step 3.1: Configure GitHub Repository Permissions
1. In your GitHub repository, navigate to **Settings** -> **Actions** -> **General**.
2. Under **Workflow permissions**, select **Read and write permissions**.
3. Save changes.

### Step 3.2: Store Render Deploy Hook Secret
1. Create your Render Web Service first (see Phase 4) or obtain the Deploy Hook URL.
2. In GitHub, go to **Settings** -> **Secrets and variables** -> **Actions**.
3. Click **New repository secret**:
   - **Name**: `RENDER_DEPLOY_HOOK_URL`
   - **Value**: `https://api.render.com/deploy/srv-xxxxxxx?key=yyyyyyy`

### Step 3.3: Set Up `.github/workflows/deploy.yml`
Update [`.github/workflows/deploy.yml`](file:///Users/fme849/Personal/Project/tinylink/.github/workflows/deploy.yml):

```yaml
name: Tinylink Build & Deploy to Render

on:
  push:
    branches: [ "main" ]
  workflow_dispatch:

permissions:
  contents: read
  packages: write

jobs:
  build-and-deploy:
    name: Build, Package & Trigger Render
    runs-on: ubuntu-latest

    steps:
      - name: Checkout code
        uses: actions/checkout@v4

      - name: Set up JDK 25
        uses: actions/setup-java@v4
        with:
          java-version: '25'
          distribution: 'temurin'
          cache: 'maven'

      - name: Make Maven wrapper executable
        run: chmod +x ./mvnw

      # 1. Run integration tests (using Testcontainers)
      - name: Run tests with Maven
        env:
          TESTCONTAINERS_RYUK_DISABLED: 'true'
        run: ./mvnw clean test

      # 2. Package production JAR
      - name: Package JAR
        run: ./mvnw package -DskipTests

      # 3. Authenticate with GitHub Container Registry
      - name: Log in to GHCR
        uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      # 4. Lowercase repository name for Docker tagging
      - name: Downcase repo name
        id: repo
        run: echo "REPO_NAME=${GITHUB_REPOSITORY,,}" >> $GITHUB_OUTPUT

      # 5. Build & push Docker image
      - name: Build and push Docker image
        uses: docker/build-push-action@v5
        with:
          context: .
          push: true
          tags: |
            ghcr.io/${{ steps.repo.outputs.REPO_NAME }}:latest
            ghcr.io/${{ steps.repo.outputs.REPO_NAME }}:${{ github.sha }}

      # 6. Trigger Render deployment
      - name: Trigger Render Deploy Hook
        if: success()
        run: |
          curl -f -X POST "${{ secrets.RENDER_DEPLOY_HOOK_URL }}"
```

> **Why:** GitHub runners have 4 vCPUs and 16 GB RAM with Maven caching, producing builds in 1-2 minutes without consuming Render build minutes.

---

## Phase 4: Backend Deployment (Render Web Service)

### Step 4.1: Create Web Service from Image
1. Go to [render.com](https://render.com) -> **New +** -> **Web Service**.
2. Select **Existing Image**.
3. Specify image URL:
   ```
   ghcr.io/<github-username>/tinylink:latest
   ```
   *(Ensure the package visibility in GitHub Packages is set to Public, or configure Render Image Pull Credentials).*
4. Select the **Free** instance type.
5. Choose region nearest to your Aiven MySQL database.

### Step 4.2: Configure Environment Variables
In the Render service **Environment** section, add:

| Variable | Value | Notes |
| :--- | :--- | :--- |
| `SPRING_DATASOURCE_URL` | `jdbc:mysql://<HOST>:<PORT>/tinylinkdb?sslMode=REQUIRED` | Aiven connection URL |
| `SPRING_DATASOURCE_USERNAME` | `avnadmin` | Aiven user |
| `SPRING_DATASOURCE_PASSWORD` | `<AIVEN_PASSWORD>` | Aiven password |
| `SPRING_JPA_HIBERNATE_DDL_AUTO` | `update` | Keeps schema safely |
| `APP_CORS_ALLOWED_ORIGINS` | `*` | Update to frontend URL later |

### Step 4.3: Copy Deploy Hook URL
Under service **Settings** -> **Deploy Hook**, copy the generated webhook URL and set it as `RENDER_DEPLOY_HOOK_URL` in your GitHub repository secrets.

---

## Phase 5: Frontend Deployment (Render Static Site)

### Step 5.1: Create Render Static Site
1. Go to Render Dashboard -> **New +** -> **Static Site**.
2. Connect your frontend repository.
3. Configure build:
   - **Build Command**: `npm install && npm run build` (or `yarn build`)
   - **Publish Directory**: `dist` (Vite) or `build` (CRA)
4. Add Environment Variable:
   - `VITE_API_URL`: `https://<your-backend-service>.onrender.com`

> **Why Static Site?** Render Static Sites run globally on a CDN, are **100% free**, **never spin down/sleep**, and provide unlimited bandwidth.

### Step 5.2: Configure SPA Client-Side Routing
Under the Static Site's **Redirects/Rewrites** tab, add:
- **Source**: `/*`
- **Destination**: `/index.html`
- **Action**: `Rewrite`

> **Why:** Prevents HTTP 404 errors when users refresh deep client-side routes (e.g., `/dashboard`).

### Step 5.3: Restrict Backend CORS
Once the frontend is assigned a live URL (e.g., `https://tinylink-web.onrender.com`):
1. Open your Backend Web Service on Render.
2. Update `APP_CORS_ALLOWED_ORIGINS` to `https://tinylink-web.onrender.com`.

---

## Phase 6: Free-Tier Optimization & Cold-Start Mitigation

### Step 6.1: Set up Keep-Alive Uptime Monitor
Free Render Web Services sleep after 15 minutes of inactivity. Cold starts for a Java 25 JVM take **30–50 seconds**.

1. Register at [UptimeRobot](https://uptimerobot.com) or [cron-job.org](https://cron-job.org).
2. Add an HTTP monitor:
   - **Target**: `https://<your-backend-service>.onrender.com/actuator/health` (or your root status endpoint).
   - **Interval**: Every **10 minutes**.

> **Why:** Periodic pings prevent Render from cycling the container into sleep mode, ensuring snappy redirects.

---

## Checklist Summary

- [ ] Aiven MySQL service active & credentials saved
- [ ] `application.yaml` updated with environment variables and `ddl-auto: update`
- [ ] `Dockerfile` and `.dockerignore` committed
- [ ] GitHub Actions workflow (`deploy.yml`) configured with GHCR and deploy hook
- [ ] GitHub repository secret `RENDER_DEPLOY_HOOK_URL` set
- [ ] Render Web Service deployed using GHCR image
- [ ] Render Static Site deployed for frontend
- [ ] CORS restricted to production frontend domain
- [ ] UptimeRobot ping set to 10-minute intervals
