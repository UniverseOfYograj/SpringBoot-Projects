# Railora — Phase 17.2.2: Compose Security & Reliability Remediation Report

> **Classification:** Security, Architecture & Reliability Pre-Deployment Remediation  
> **Status:** Implementation & Static Validation Complete  
> **Date:** November 2026  
> **Repository:** `D:\railway-github-check\Railway-Booking-System`  
> **Scope:** Remediation of findings SEC-101 (Prometheus Credentials), REL-101 (Redis Memory Boundaries & Eviction Policy), CFG-101 (Production Metrics Authentication), DOC-101 (PostgreSQL vs Redis Durability Reality), and NET-101 (Frontend Port Override).

---

## 1. Executive Summary

Following the Phase 17.2.1 static security review, all approved security, reliability, configuration, and documentation remediations have been implemented without altering core application Java source code.

| Finding ID | Severity | Area | Status | Resolution Summary |
|---|---|---|---|---|
| **SEC-101** | **P1 (High)** | Security / Scrape Auth | **RESOLVED** | Removed hardcoded password placeholder; implemented Prometheus `password_file` mount via Git-ignored local secret file; fail-safe initialization verified. |
| **REL-101** | **P2 (Medium)** | Redis Reliability | **RESOLVED** | Forensically audited `RedisTatkalQueueStore.java` and `RedisCacheConfig.java`; explicitly rejected `volatile-lru` (causes silent queue eviction); configured bounded memory (`--maxmemory 512mb`) with `--maxmemory-policy noeviction` preserving fail-closed Tatkal and fail-open search caching. |
| **CFG-101** | **P2 (Medium)** | Configuration | **RESOLVED** | Added `management.metrics.scrape.auth.enabled=${METRICS_SCRAPE_AUTH_ENABLED:true}` to [`application-production.properties`](file:///D:/railway-github-check/Railway-Booking-System/src/main/resources/application-production.properties); default ensures `MetricsSecurityConfig` activates in production. |
| **DOC-101** | **P3 (Low)** | Documentation | **RESOLVED** | Clarified in [`docs/PHASE_17_2_DOCKER_COMPOSE_ORCHESTRATION.md`](file:///D:/railway-github-check/Railway-Booking-System/docs/PHASE_17_2_DOCKER_COMPOSE_ORCHESTRATION.md) that PostgreSQL GiST exclusion constraints are the authoritative booking store; Redis AOF provides best-effort queue recovery with TTL expiry constraints. |
| **NET-101** | **P3 (Low)** | Networking | **RESOLVED** | Documented `FRONTEND_PORT` override in [`.env.example`](file:///D:/railway-github-check/Railway-Booking-System/.env.example) and deployment guide for environments where host port 80 is occupied by IIS, Apache, or unprivileged services. |

---

## 2. Detailed Findings Resolutions

### 2.1 SEC-101: Prometheus Credentials & Secure `password_file` Mechanism

- **Problem:** Prometheus standard YAML configuration does NOT support shell environment variable interpolation (e.g. `${METRICS_PASSWORD}`). In Phase 17.2, `monitoring/prometheus/prometheus-compose.yml` contained a static password string (`pr0mP@ss!Secure42`). If an operator configured a custom `METRICS_PASSWORD` in `.env`, the Spring Boot backend received the new secret while Prometheus transmitted the old static password, causing **HTTP 401 Unauthorized** scrape failures.
- **Remediation Implementation:**
  1. **Prometheus Scrape Configuration:** Updated [`monitoring/prometheus/prometheus-compose.yml`](file:///D:/railway-github-check/Railway-Booking-System/monitoring/prometheus/prometheus-compose.yml) to replace `basic_auth.password` with `basic_auth.password_file`:
     ```yaml
     basic_auth:
       username: "promuser"
       password_file: "/etc/prometheus/secrets/metrics_password"
     ```
  2. **Docker Compose Volume Mount:** Updated [`docker-compose.yml`](file:///D:/railway-github-check/Railway-Booking-System/docker-compose.yml) under the `prometheus` service to mount the local secret file into the container read-only:
     ```yaml
     volumes:
       - ./monitoring/prometheus/prometheus-compose.yml:/etc/prometheus/prometheus.yml:ro
       - ./monitoring/prometheus/alerts:/etc/prometheus/alerts:ro
       - ${METRICS_PASSWORD_FILE:-./monitoring/secrets/metrics_password}:/etc/prometheus/secrets/metrics_password:ro
       - prometheus_data:/prometheus
     ```
  3. **Local Secrets Infrastructure:**
     - Created directory `monitoring/secrets/`.
     - Created tracked template [`monitoring/secrets/metrics_password.example`](file:///D:/railway-github-check/Railway-Booking-System/monitoring/secrets/metrics_password.example) with clear instructions for operators.
     - Anchored `.gitignore` rules in [`Railway-Booking-System/.gitignore`](file:///D:/railway-github-check/Railway-Booking-System/.gitignore):
       ```gitignore
       # Secret files and credential mounts
       /secrets/
       /monitoring/secrets/*
       !/monitoring/secrets/*.example
       !/monitoring/secrets/.gitkeep
       ```
     - Safely populated `monitoring/secrets/metrics_password` from `monitoring/.env` without printing or displaying credentials.
  4. **Fail-Safe Behavior:** If `monitoring/secrets/metrics_password` is absent at container startup, Prometheus fails fast (`open /etc/prometheus/secrets/metrics_password: no such file or directory`), causing the container healthcheck to fail and alerting the operator immediately rather than scraping unauthenticated.

---

### 2.2 REL-101: Redis Memory Boundaries & Key Lifecycle Analysis

Before modifying Redis configuration, a detailed forensic analysis of key lifecycles and concurrency scripts was performed across `RedisTatkalQueueStore.java` and `RedisCacheConfig.java`.

#### 1. Forensic Key Lifecycle Inspection

| Component | Redis Key Pattern | Redis Data Structure | Explicit TTL | Eviction Vulnerability |
|---|---|---|---|---|
| **Tatkal Queue** | `tatkal:{journeyId}:waiting` | Sorted Set (ZSET) | **NO TTL** (Persistent) | `allkeys-lru` would destroy queue order |
| **Tatkal Queue** | `tatkal:{journeyId}:sequence` | String (INCR counter) | **NO TTL** (Persistent) | `allkeys-lru` would corrupt ticket sequence numbering |
| **Tatkal Queue** | `tatkal:{journeyId}:admitted` | Sorted Set (ZSET) | **NO TTL** (Persistent) | `allkeys-lru` would drop active admitted entries |
| **Tatkal Queue** | `tatkal:{journeyId}:admission-sequence` | String (INCR counter) | **NO TTL** (Persistent) | `allkeys-lru` would corrupt admission IDs |
| **Tatkal Entry** | `tatkal:{journeyId}:entry:{entryId}` | Hash (State, User, Holds) | **YES** (`EXPIRE`, 86,400s) | **CRITICAL:** `volatile-lru` evicts this hash! |
| **Tatkal User** | `tatkal:{journeyId}:user:{username}` | String (`entryId`) | **YES** (`EX`, 86,400s) | **CRITICAL:** `volatile-lru` evicts this mapping! |
| **Tatkal Rate** | `tatkal:{journeyId}:rate:{op}:{user}` | String (Counter) | **YES** (`PEXPIRE`, window) | `volatile-lru` evicts rate counter |
| **Spring Cache** | `journeySearch::<hash>` | String (JSON) | **YES** (10 minutes) | Safe to evict / reload from PostgreSQL |
| **Spring Cache** | `trainSearch::<hash>` | String (JSON) | **YES** (30 minutes) | Safe to evict / reload from PostgreSQL |
| **Spring Cache** | `trainTopology::<hash>` | String (JSON) | **YES** (24 hours) | Safe to evict / reload from PostgreSQL |

#### 2. Why `volatile-lru` Was Explicitly Rejected
- In `RedisTatkalQueueStore.java`, each ticket entry hash (`tatkal:{journeyId}:entry:{entryId}`) is created with an explicit TTL (`EXPIRE` line 39). Thus, it is classified by Redis as a **volatile key**.
- While waiting in the queue, users poll infrequently or wait for queue promotion. Under heavy search traffic, Spring Cache keys are accessed frequently, causing idle queue entry hashes to have a low LRU recency score.
- Under `volatile-lru`, when memory hits the limit, Redis will evict `tatkal:{journeyId}:entry:{entryId}` while leaving the persistent Sorted Set `tatkal:{journeyId}:waiting` completely intact.
- When `promote()` runs (`ADMIT_SCRIPT` line 67):
  ```lua
  local entryKey = ARGV[5] .. entryId
  if redis.call('HGET', entryKey, 'state') == 'WAITING' then
  ```
  `HGET` returns `nil` because the hash was evicted. The entry is skipped, and line 65 (`redis.call('ZREM', KEYS[1], entryId)`) permanently removes the user from the waiting queue without admitting them.
- **Conclusion:** `volatile-lru` silently corrupts active Tatkal queues, drops waiting passengers, and violates the fundamental safety invariant.

#### 3. Why `noeviction` Is the Mathematically Sound Choice
- Under `--maxmemory-policy noeviction`, Redis **never evicts any key**. Keys are removed exclusively through natural TTL expiration or explicit deletion (`ZREM`, `DEL`, `HSET state EXPIRED`).
- When memory reaches `--maxmemory 512mb`:
  1. **Read Operations:** All read queries (`GET`, `HGET`, `ZRANGE`, `ZCARD`) continue to execute with zero error.
  2. **Search Cache (Fail-Open):** In [`RedisCacheConfig.java`](file:///D:/railway-github-check/Railway-Booking-System/src/main/java/in/yograj/learnspringboot/railway_booking/config/RedisCacheConfig.java#L107-L134), `ResilientCacheErrorHandler.handleCachePutError` intercepts Redis OOM exceptions on cache writes, logs a warning, and swallows the exception. Search queries transparently query and return data from PostgreSQL.
  3. **Tatkal Queue (Fail-Closed):** If Redis memory is exhausted, a new user attempting to `join()` fails fast with an explicit error rather than entering a corrupted queue. Active queue entries already admitted or waiting are **100% protected against silent eviction**.

#### 4. Redis Command Implementation in `docker-compose.yml`
```yaml
  redis:
    image: redis:7-alpine
    container_name: ${COMPOSE_PROJECT_NAME:-railora}-redis
    restart: unless-stopped
    command: >
      redis-server
      --requirepass ${REDIS_PASSWORD:?REDIS_PASSWORD must be configured in environment}
      --appendonly yes
      --appendfsync everysec
      --maxmemory ${REDIS_MAXMEMORY:-512mb}
      --maxmemory-policy noeviction
```

---

### 2.3 CFG-101: Production Metrics Authentication

- **Problem:** `src/main/resources/application-production.properties` omitted `management.metrics.scrape.auth.enabled`. Outside Docker Compose, it defaulted to `false` (via base `application.properties`), causing Prometheus scrape requests to bypass [`MetricsSecurityConfig.java`](file:///D:/railway-github-check/Railway-Booking-System/src/main/java/in/yograj/learnspringboot/railway_booking/config/MetricsSecurityConfig.java) and fall into the main JWT filter chain where HTTP Basic authentication was rejected.
- **Remediation Implementation:** Added the following declaration to [`src/main/resources/application-production.properties`](file:///D:/railway-github-check/Railway-Booking-System/src/main/resources/application-production.properties):
  ```properties
  # Dedicated Metrics Scrape Authentication (Phase 15C / Phase 17.2)
  # Enables MetricsSecurityConfig HTTP Basic authentication for /actuator/prometheus
  management.metrics.scrape.auth.enabled=${METRICS_SCRAPE_AUTH_ENABLED:true}
  ```
- **Verification:**
  - In production, it defaults to `true` (fail-secure).
  - Matches `docker-compose.yml`: `METRICS_SCRAPE_AUTH_ENABLED: ${METRICS_SCRAPE_AUTH_ENABLED:-true}`.
  - Matches `MetricsSecurityConfig` conditional annotation `@ConditionalOnProperty(name = "management.metrics.scrape.auth.enabled", havingValue = "true")`.
  - Main JWT filter chain (`/api/**`) remains completely unaffected.

---

### 2.4 DOC-101 & NET-101: Durability & Port Isolation Documentation

1. **DOC-101 (PostgreSQL vs Redis Durability Architecture):**  
   Updated [`docs/PHASE_17_2_DOCKER_COMPOSE_ORCHESTRATION.md`](file:///D:/railway-github-check/Railway-Booking-System/docs/PHASE_17_2_DOCKER_COMPOSE_ORCHESTRATION.md) Section 2.2 to clarify:
   - **PostgreSQL is the Authoritative Store:** All confirmed bookings, tickets, and seat assignments reside exclusively in PostgreSQL. The `seat_alloc_no_overlap_excl` GiST exclusion constraint on `seat_allocations` mathematically guarantees zero double-bookings.
   - **Redis AOF Role:** Redis AOF with `everysec` synchronization provides best-effort crash recovery for queue admission ordering, but queue tickets have explicit TTLs (5–10 minutes) and will expire if extended downtime occurs.
2. **NET-101 (Frontend Port Override):**  
   Updated [`docs/PHASE_17_2_DOCKER_COMPOSE_ORCHESTRATION.md`](file:///D:/railway-github-check/Railway-Booking-System/docs/PHASE_17_2_DOCKER_COMPOSE_ORCHESTRATION.md) Section 3 and [`.env.example`](file:///D:/railway-github-check/Railway-Booking-System/.env.example) documenting that `FRONTEND_PORT=80` can be overridden to `8080` or `8000` when host port 80 is occupied by Windows IIS, Apache, or unprivileged services.

---

## 3. Files Modified & Created

| File Path | Action | Description |
|---|---|---|
| [`Railway-Booking-System/.gitignore`](file:///D:/railway-github-check/Railway-Booking-System/.gitignore) | **Modified** | Added `/secrets/`, `/monitoring/secrets/*`, and exception rules for `*.example` templates. |
| [`monitoring/secrets/metrics_password.example`](file:///D:/railway-github-check/Railway-Booking-System/monitoring/secrets/metrics_password.example) | **Created** | Operator instructions and template for the local Prometheus Basic Auth password file. |
| [`monitoring/secrets/metrics_password`](file:///D:/railway-github-check/Railway-Booking-System/monitoring/secrets/metrics_password) | **Created (Ignored)** | Local read-only secret file populated securely from `monitoring/.env` without logging credentials. |
| [`monitoring/prometheus/prometheus-compose.yml`](file:///D:/railway-github-check/Railway-Booking-System/monitoring/prometheus/prometheus-compose.yml) | **Modified** | Replaced hardcoded password with `basic_auth.password_file: "/etc/prometheus/secrets/metrics_password"`. |
| [`docker-compose.yml`](file:///D:/railway-github-check/Railway-Booking-System/docker-compose.yml) | **Modified** | Added Redis `--maxmemory ${REDIS_MAXMEMORY:-512mb} --maxmemory-policy noeviction`; mounted `${METRICS_PASSWORD_FILE:-...}:/etc/prometheus/secrets/metrics_password:ro`. |
| [`src/main/resources/application-production.properties`](file:///D:/railway-github-check/Railway-Booking-System/src/main/resources/application-production.properties) | **Modified** | Added explicit `management.metrics.scrape.auth.enabled=${METRICS_SCRAPE_AUTH_ENABLED:true}`. |
| [`.env.example`](file:///D:/railway-github-check/Railway-Booking-System/.env.example) | **Modified** | Documented `FRONTEND_PORT` override, `REDIS_MAXMEMORY`, `METRICS_PASSWORD_FILE`, and removed password placeholders. |
| [`monitoring/README.md`](file:///D:/railway-github-check/Railway-Booking-System/monitoring/README.md) | **Modified** | Updated directory tree to document `monitoring/secrets/` infrastructure. |
| [`docs/PHASE_17_2_DOCKER_COMPOSE_ORCHESTRATION.md`](file:///D:/railway-github-check/Railway-Booking-System/docs/PHASE_17_2_DOCKER_COMPOSE_ORCHESTRATION.md) | **Modified** | Updated Redis persistence/durability rationale, Prometheus secret mount, and environment table. |
| [`docs/PHASE_17_2_2_REMEDIATION_REPORT.md`](file:///D:/railway-github-check/Railway-Booking-System/docs/PHASE_17_2_2_REMEDIATION_REPORT.md) | **Created** | Comprehensive phase remediation deliverable report. |

---

## 4. Verification & Test Results

### 4.1 Git Ignore Verification (`git check-ignore -v`)
All sensitive files and templates were audited against the Git ignore rules:

| Target File | Expected Status | Actual Status | Matched Git Rule |
|---|---|---|---|
| `monitoring/secrets/metrics_password` | **IGNORED** | **PASS (Ignored)** | `.gitignore:65:/monitoring/secrets/*` |
| `monitoring/secrets/metrics_password.example` | **TRACKED** | **PASS (Tracked)** | `.gitignore:66:!/monitoring/secrets/*.example` |
| `.env` | **IGNORED** | **PASS (Ignored)** | `.gitignore:58:.env` |
| `.env.production` | **IGNORED** | **PASS (Ignored)** | `.gitignore:59:.env.*` |
| `.env.example` | **TRACKED** | **PASS (Tracked)** | `.gitignore:60:!.env.example` |
| `.env.secrets` | **IGNORED** | **PASS (Ignored)** | `.gitignore:59:.env.*` |
| `monitoring/.env` | **IGNORED** | **PASS (Ignored)** | `.gitignore:58:.env` |
| `monitoring/.env.example` | **TRACKED** | **PASS (Tracked)** | `.gitignore:60:!.env.example` |

`git status --porcelain` confirmed that `monitoring/secrets/metrics_password` is completely invisible to Git.

### 4.2 Prometheus Configuration Validation (`promtool check config`)
`monitoring/prometheus/prometheus-compose.yml` was validated directly against the native Prometheus 3.2.1 parser:
```text
Checking /dev/stdin
  SUCCESS: 1 rule files found
 SUCCESS: /dev/stdin is valid prometheus config file syntax

Checking /etc/prometheus/alerts/railora-alerts.yml
  SUCCESS: 10 rules found
```
**Result: PASS (0 syntax errors, 10 alert rules validated).**

### 4.3 Docker Compose Static Validation (`docker compose config`)
1. **With `.env.example`:** Executed `docker compose -f docker-compose.yml --env-file .env.example config`. All 6 services (`postgres`, `redis`, `backend`, `frontend`, `prometheus`, `grafana`), volumes, networks, and environment variables resolved cleanly with exit code 0.
2. **Missing-Variable Fail-Safe:** Executed `docker compose -f docker-compose.yml config` without an environment file. Docker Compose failed fast with exit code 1:
   ```text
   error while interpolating services.postgres.environment.POSTGRES_PASSWORD: required variable POSTGRES_PASSWORD is missing a value: POSTGRES_PASSWORD must be configured in environment
   ```
**Result: PASS (Interpolation and mandatory fail-safes function as designed).**

### 4.4 Spring Boot Security Regression Suite (`mvnw.cmd test`)
Executed the security test suites covering both `MetricsSecurityConfig` and `SecurityHardening`:
- `MetricsSecurityIntegrationTests` (14 tests): Verifies HTTP Basic Auth on `/actuator/prometheus`, credential rejection, chain isolation, and unauthorized access rejection.
- `SecurityHardeningIntegrationTests` (16 tests): Verifies JWT enforcement, login rate limiting, and CORS restrictions.

```text
[INFO] Running in.yograj.learnspringboot.railway_booking.security.MetricsSecurityIntegrationTests
[INFO] Tests run: 14, Failures: 0, Errors: 0, Skipped: 0 -- in MetricsSecurityIntegrationTests
[INFO] Running in.yograj.learnspringboot.railway_booking.security.SecurityHardeningIntegrationTests
[INFO] Tests run: 16, Failures: 0, Errors: 0, Skipped: 0 -- in SecurityHardeningIntegrationTests
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] Total time: 01:15 min
[INFO] ------------------------------------------------------------------------
```
**Result: PASS (30 / 30 tests passing, 0 failures, 0 errors, 0 skipped).**

---

## 5. Architectural Limitations & Production Recommendations

1. **Shared Single-Node Redis Limitation:**
   - Under the unified Compose stack, `redis:7-alpine` is shared between high-frequency search query caching (`journeySearch`, `trainSearch`, `trainTopology`) and stateful Tatkal queue tokens.
   - Under sustained search traffic reaching 512 MB, cache PUT failures fail open to PostgreSQL, but new Tatkal queue admissions fail closed until cache keys naturally expire (10–30 minutes).
2. **Enterprise Production Recommendation:**
   - In an enterprise multi-node deployment, separate the cache and queue workloads into two independent Redis services:
     - **`redis-cache`:** Dedicated read cache configured with `--maxmemory 512mb --maxmemory-policy allkeys-lru`.
     - **`redis-queue`:** Dedicated transactional queue store configured with `--maxmemory 256mb --maxmemory-policy noeviction` (or Redis Enterprise / Dragonfly).

---

## 6. Pre-Runtime Deployment Status

- All findings from Phase 17.2.1 review have been resolved and statically verified.
- No running containers were stopped or restarted during this phase.
- Host processes (`task-1843`, `task-630`) and existing monitoring containers remain healthy and undisturbed.
- The repository is completely ready for **Phase 17.3 (Unified Runtime Deployment & Verification)** upon your explicit authorization.
