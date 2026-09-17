# Common GitHub Actions Workflow Patterns

Reference templates for common CI/CD scenarios.

---

## Pattern 1: Java / Spring Boot with Database Service & Maven

```yaml
name: Test & Build

on:
  workflow_dispatch:
  pull_request:
    branches: [main]
  push:
    branches: [main]

jobs:
  test:
    name: Run Unit & Integration Tests
    runs-on: ubuntu-latest

    services:
      mysql:
        image: mysql:8.0
        env:
          MYSQL_DATABASE: tinylinkdb
          MYSQL_USER: admin
          MYSQL_PASSWORD: secret
          MYSQL_ROOT_PASSWORD: verysecret
        ports:
          - 3306:3306
        options: --health-cmd="mysqladmin ping" --health-interval=10s --health-timeout=5s --health-retries=3

    steps:
      - name: Checkout repository
        uses: actions/checkout@v4

      - name: Set up JDK
        uses: actions/setup-java@v4
        with:
          java-version: '25'
          distribution: 'temurin'
          cache: 'maven'

      - name: Make Maven wrapper executable
        run: chmod +x ./mvnw

      - name: Run Maven Tests
        run: ./mvnw clean test

      - name: Upload Test Reports
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: surefire-reports
          path: target/surefire-reports/
```

---

## Pattern 2: Node.js / TypeScript Test Suite

```yaml
name: Node Test

on:
  pull_request:
    branches: [main]
  workflow_dispatch:

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: 20
          cache: 'npm'
      - run: npm ci
      - run: npm test
```

---

## Pattern 3: Matrix Build Strategy (Multi-version testing)

```yaml
name: Matrix Test

on: [workflow_dispatch]

jobs:
  matrix-test:
    runs-on: ubuntu-latest
    strategy:
      fail-fast: false
      matrix:
        java-version: ['17', '21', '25']
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: ${{ matrix.java-version }}
          distribution: 'temurin'
          cache: 'maven'
      - run: chmod +x ./mvnw && ./mvnw test
```
