# Railora — Phase 17.2.1: Docker Compose Security & Configuration Review

> **Classification:** Security, Architecture & Reliability Pre-Deployment Audit  
> **Status:** Review Completed (Read-Only)  
> **Audit Date:** November 2026  
> **Repository:** `D:\railway-github-check\Railway-Booking-System`  
> **Scope:** Static configuration audit of Docker Compose, production properties, security filters, Redis durability, networking, and environment isolation.

---

## 1. Executive Summary & Verification Scope

Following the implementation of Phase 17.2 (Unified Docker Compose Orchestration), this focused security and configuration review evaluates the production readiness of:
- [`docker-compose.yml`](file:///D:/railway-github-check/Railway-Booking-System/docker-compose.yml)
- [`.env.example`](file:///D:/railway-github-check/Railway-Booking-System/.env.example)
- [`monitoring/prometheus/prometheus-compose.yml`](file:///D:/railway-github-check/Railway-Booking-System/monitoring/prometheus/prometheus-compose.yml)
- [`monitoring/prometheus/prometheus.yml`](file:///D:/railway-github-check/Railway-Booking-System/monitoring/prometheus/prometheus.yml)
- [`application-production.properties`](file:///D:/railway-github-check/Railway-Booking-System/src/main/resources/application-production.properties)
- [`MetricsSecurityConfig.java`](file:///D:/railway-github-check/Railway-Booking-System/src/main/java/in/yograj/learnspringboot/railway_booking/config/MetricsSecurityConfig.java)
- [`RedisCacheConfig.java`](file:///D:/railway-github-check/Railway-Booking-System/src/main/java/in/yograj/learnspringboot/railway_booking/config/RedisCacheConfig.java)
- Root and frontend [`Dockerfile`](file:///D:/railway-github-check/Railway-Booking-System/Dockerfile)s

### Review Verdict: **CONDITIONAL PASS (P1/P2 Remediation Required Before Runtime)**
The Docker Compose orchestration is architecturally sound: network isolation, non-root users, volume namespaces, fail-open cache handlers, and database separation are properly implemented. However, **one critical credentials mismatch hazard (P1)** and **three reliability/memory safeguards (P2)** must be resolved before executing runtime deployment (`docker compose up`).

---

## 2. Review Area 1: Metrics Authentication & Scrape Isolation

### 1. Verification of `METRICS_SCRAPE_AUTH_ENABLED`
- **Observed Configuration:**
  - In [`docker-compose.yml`](file:///D:/railway-github-check/Railway-Booking-System/docker-compose.yml) line 68: `METRICS_SCRAPE_AUTH_ENABLED: ${METRICS_SCRAPE_AUTH_ENABLED:-true}`.
  - In [`application.properties`](file:///D:/railway-github-check/Railway-Booking-System/src/main/resources/application.properties) line 126: `management.metrics.scrape.auth.enabled=${METRICS_SCRAPE_AUTH_ENABLED:false}`.
  - In [`application-production.properties`](file:///D:/railway-github-check/Railway-Booking-System/src/main/resources/application-production.properties): Property is omitted, falling back to `application.properties`.
- **Finding:** While Compose sets `METRICS_SCRAPE_AUTH_ENABLED=true` by default, if the production profile is ever executed outside Compose without this environment variable, it defaults to `false`. When `false`, [`MetricsSecurityConfig.java`](file:///D:/railway-github-check/Railway-Booking-System/src/main/java/in/yograj/learnspringboot/railway_booking/config/MetricsSecurityConfig.java) is disabled, causing `/actuator/prometheus` to fall into the JWT security filter chain where HTTP Basic authentication is rejected.

### 2. Hardcoded Scrape Password & Authentication Mismatch Hazard (**P1 Finding**)
- **Observed Configuration:**
  - In [`monitoring/prometheus/prometheus-compose.yml`](file:///D:/railway-github-check/Railway-Booking-System/monitoring/prometheus/prometheus-compose.yml) lines 30–32:
    ```yaml
    basic_auth:
      username: "promuser"
      password: "pr0mP@ss!Secure42"
    ```
  - In [`docker-compose.yml`](file:///D:/railway-github-check/Railway-Booking-System/docker-compose.yml) lines 69–70:
    ```yaml
    METRICS_USER: ${METRICS_USER:-promuser}
    METRICS_PASSWORD: ${METRICS_PASSWORD:?METRICS_PASSWORD must be configured in environment}
    ```
- **Analysis:**
  Prometheus's YAML engine does **not** dynamically interpolate environment variables like `${METRICS_PASSWORD}`.
  If an operator follows standard security protocol and changes `METRICS_PASSWORD` in `.env` to a custom secret (e.g., `CustomRandomPass987`), the Spring Boot backend boots expecting that new secret. However, Prometheus will still send `"pr0mP@ss!Secure42"` from the static YAML mount.
- **Impact:**
  Prometheus scrape requests will be rejected with **HTTP 401 Unauthorized**. Scrapes will fail, alert `RailoraDown` or `PrometheusTargetMissing` will fire, and metric collection will halt.
- **Remediation:**
  Prometheus natively supports `basic_auth.password_file`. The password should be mounted via an environment-generated file or Docker secret, or `prometheus.yml` should be rendered via `envsubst` at container launch.

---

## 3. Review Area 2: Production Profile & Property Completeness

Every property in [`application-production.properties`](file:///D:/railway-github-check/Railway-Booking-System/src/main/resources/application-production.properties) was mapped against [`docker-compose.yml`](file:///D:/railway-github-check/Railway-Booking-System/docker-compose.yml):

| Production Property | Compose Environment Variable | Evaluation | Status |
|---|---|---|---|
| `spring.datasource.url=${DB_URL}` | `DB_URL: jdbc:postgresql://postgres:5432/${POSTGRES_DB:-railway_booking_prod}` | Points to Compose Postgres on port 5432 | **VERIFIED** |
| `spring.datasource.username=${DB_USERNAME}` | `DB_USERNAME: ${POSTGRES_USER:-railora_user}` | Mapped to `POSTGRES_USER` | **VERIFIED** |
| `spring.datasource.password=${DB_PASSWORD}` | `DB_PASSWORD: ${POSTGRES_PASSWORD:?...}` | Mandatory fail-safe validation enforced | **VERIFIED** |
| `jwt.access-secret=${JWT_ACCESS_SECRET}` | `JWT_ACCESS_SECRET: ${JWT_ACCESS_SECRET:?...}` | Mandatory fail-safe validation enforced | **VERIFIED** |
| `jwt.refresh-secret=${JWT_REFRESH_SECRET}` | `JWT_REFRESH_SECRET: ${JWT_REFRESH_SECRET:?...}` | Mandatory fail-safe validation enforced | **VERIFIED** |
| `spring.data.redis.host=${REDIS_HOST}` | `REDIS_HOST: redis` | Resolves to internal service `redis` | **VERIFIED** |
| `spring.data.redis.port=${REDIS_PORT}` | `REDIS_PORT: 6379` | Internal port 6379 | **VERIFIED** |
| `spring.data.redis.username=${REDIS_USERNAME}` | `REDIS_USERNAME: default` | Standard Redis 7 ACL username | **VERIFIED** |
| `spring.data.redis.password=${REDIS_PASSWORD}` | `REDIS_PASSWORD: ${REDIS_PASSWORD:?...}` | Mandatory fail-safe validation enforced | **VERIFIED** |
| `spring.data.redis.ssl.enabled=${REDIS_SSL_ENABLED}`| `REDIS_SSL_ENABLED: "false"` | Plaintext on internal bridge network | **VERIFIED** |
| `railway.security.cors.allowed-origins` | `CORS_ALLOWED_ORIGINS: ${CORS_ALLOWED_ORIGINS:-...}` | Configurable with localhost defaults | **VERIFIED** |
| `railway.fare.base-amount=${RAILWAY_BASE_FARE}` | `RAILWAY_BASE_FARE: ${RAILWAY_BASE_FARE:-500.00}` | Server-side fare enforcement active | **VERIFIED** |
| `razorpay.enabled=${RAZORPAY_ENABLED:false}` | `RAZORPAY_ENABLED: ${RAZORPAY_ENABLED:-false}` | Defaults to `false` (opt-in) | **VERIFIED** |
| `railway.payment.mock-enabled=false` | `RAILWAY_PAYMENT_MOCK_ENABLED: "false"` | Hardcoded disabled in Compose | **VERIFIED** |
| `railway.payment.simulated-enabled=false` | `RAILWAY_PAYMENT_SIMULATED_ENABLED: "false"` | Hardcoded disabled in Compose | **VERIFIED** |
| `railway.outbox.kafka.enabled` | `RAILWAY_OUTBOX_KAFKA_ENABLED: ${...:-false}` | Defaults to `false` (opt-in) | **VERIFIED** |

---

## 4. Review Area 3: Redis Reliability, Persistence & Durability Reality

### 1. Redis Command & Persistence Analysis
In [`docker-compose.yml`](file:///D:/railway-github-check/Railway-Booking-System/docker-compose.yml):
```yaml
command: >
  redis-server
  --requirepass ${REDIS_PASSWORD:?REDIS_PASSWORD must be configured in environment}
  --appendonly yes
  --appendfsync everysec
```
- **AOF (Append-Only File):** Enabled with `appendfsync everysec`. Provides an optimal compromise between high write throughput and data safety.
- **Volume:** `redis_data:/data` stores `appendonly.aof` persistently.
- **Authentication:** `--requirepass` enforces authentication for all commands, matching backend Lettuce client configuration.
- **Healthcheck:** Evaluates authenticated ping (`redis-cli -a "$$REDIS_PASSWORD" ping | grep PONG`).

### 2. Durability vs. In-Memory Reality Check (**P2 Finding**)
- **Overstatement Risk:** Previous documentation stated: *"AOF persistence ensures that admission sequence counters and active queue entries survive unexpected container restarts with at most 1 second of journal lag, preventing Tatkal ticket loss."*
- **Technical Reality:**
  1. **Redis is NOT the System of Record:** In Railora, true data durability and double-booking prevention are enforced **exclusively by PostgreSQL** through the `seat_allocations` GiST exclusion constraint (`seat_alloc_no_overlap_excl`) and `bookings` tables.
  2. **Tatkal Queue Passes Are Ephemeral:** A Tatkal queue entry (`RedisTatkalQueueStore`) has a short TTL (typically 5–10 minutes). If Redis is down for longer than 5 minutes during a crash or maintenance window, all restored queue entries will have expired (`admittedUntil <= now`).
  3. **Unbounded Memory Risk:** The Redis container has no `maxmemory` or eviction policy declared (`maxmemory-policy`). In high-throughput production, Redis can consume memory until killed by the Linux OOM killer. If memory fills up without an eviction policy, Redis will reject new Tatkal queue entries with `OOM command not allowed`.

---

## 5. Review Area 4: Docker Networking & Port Isolation

| Service | Internal Port | Host Port Published | Network Name | Security Evaluation |
|---|---|---|---|---|
| `postgres` | `5432` | **None** | `railora-prod-net` | **EXCELLENT:** Zero host exposure; protected from external scanning. |
| `redis` | `6379` | **None** | `railora-prod-net` | **EXCELLENT:** Internal traffic only; authentication required. |
| `backend` | `8080` | **None** | `railora-prod-net` | **EXCELLENT:** Port 8080 is not bound to host; reachable only via Nginx and Prometheus. Prevents conflict with host Spring Boot. |
| `frontend` | `80` | `${FRONTEND_PORT:-80}` | `railora-prod-net` | **VERIFIED:** Configurable; reverse-proxies `/api/` to `backend:8080`. |
| `prometheus`| `9090` | `127.0.0.1:${PROMETHEUS_PORT:-9091}` | `railora-prod-net` | **VERIFIED:** Bound strictly to loopback (`127.0.0.1`), prevents external exposure. Default `9091` avoids conflict with host `9090`. |
| `grafana` | `3000` | `127.0.0.1:${GRAFANA_PORT:-3001}` | `railora-prod-net` | **VERIFIED:** Bound strictly to loopback (`127.0.0.1`). Default `3001` avoids conflict with host `3000`. |

### Volume & Network Scoping
- **Network:** `name: ${COMPOSE_PROJECT_NAME:-railora}-network` defaults to `railora-prod-network`. Completely isolated from the existing `railora-monitoring` bridge network.
- **Volumes:** Prefixed with `${COMPOSE_PROJECT_NAME:-railora}_` (`railora-prod_postgres_data`, `railora-prod_redis_data`, etc.). Completely distinct from `railora_prometheus_data` and `railora_grafana_data`.

---

## 6. Review Area 5: Deployment Safety & Host Service Isolation

1. **Host Backend Isolation (`task-1843`):**  
   The active Spring Boot process on the host listens on `127.0.0.1:8080`. The Compose backend container publishes **no ports to the host interface** (`expose: - "8080"` only). Zero port binding contention can occur.
2. **Database Isolation (`railway_booking_prod`):**  
   The Compose stack initializes a new containerized PostgreSQL instance pointing to database `railway_booking_prod`. The host databases (`railway_booking` and `railway_booking_test`) are never mounted, networked, or accessed.
3. **Upstash Redis & Kafka Isolation:**  
   The backend environment in Compose explicitly overrides `REDIS_HOST=redis` and `RAILWAY_OUTBOX_KAFKA_ENABLED=false`. No cloud endpoints (Upstash or remote Kafka brokers) are reachable by the Compose stack.
4. **Existing Monitoring Stack:**  
   The existing containers `railora-prometheus` (port 9090) and `railora-grafana` (port 3000) remain running and completely isolated on `railora-monitoring`.

---

## 7. Prioritized Findings Table

| Finding ID | Severity | Area | Affected File / Component | Observed Evidence | Why It Matters / Impact | Recommended Fix |
|---|---|---|---|---|---|---|
| **SEC-101** | **P1** | Security / Auth | [`monitoring/prometheus/prometheus-compose.yml`](file:///D:/railway-github-check/Railway-Booking-System/monitoring/prometheus/prometheus-compose.yml) | Hardcoded `password: "pr0mP@ss!Secure42"` in static Prometheus config | If operator changes `METRICS_PASSWORD` in `.env`, backend updates but Prometheus continues sending old hardcoded password, causing **HTTP 401 Unauthorized** scrape failures. | Mount a password file (`basic_auth.password_file`) or use an entrypoint script with `envsubst` to dynamically inject `${METRICS_PASSWORD}` into `prometheus.yml`. |
| **REL-101** | **P2** | Redis Reliability | [`docker-compose.yml`](file:///D:/railway-github-check/Railway-Booking-System/docker-compose.yml) | No `maxmemory` or `maxmemory-policy` in Redis command | Redis has unbounded memory limits. Under high cache load or search traffic, Redis can exhaust container memory and get terminated by the Linux OOM killer. | Append `--maxmemory 256mb --maxmemory-policy volatile-lru` to the Redis command in `docker-compose.yml`. |
| **CFG-101** | **P2** | Configuration | [`application-production.properties`](file:///D:/railway-github-check/Railway-Booking-System/src/main/resources/application-production.properties) | `management.metrics.scrape.auth.enabled` missing from production properties | Outside of Compose, production profile defaults to `false` (inherited from `application.properties`), which disables `MetricsSecurityConfig` and breaks basic auth. | Add `management.metrics.scrape.auth.enabled=${METRICS_SCRAPE_AUTH_ENABLED:true}` directly to `application-production.properties`. |
| **DOC-101** | **P3** | Documentation | [`docs/PHASE_17_2_DOCKER_COMPOSE_ORCHESTRATION.md`](file:///D:/railway-github-check/Railway-Booking-System/docs/PHASE_17_2_DOCKER_COMPOSE_ORCHESTRATION.md) | Section 2.2 claims Redis AOF "prevents Tatkal ticket loss" | Tatkal tickets are not confirmed bookings; confirmed tickets live in PostgreSQL. Queue tickets have 5-min TTLs and will expire during extended downtime. | Clarify that Redis AOF provides best-effort queue ordering recovery, but authoritative ticket inventory durability rests 100% on PostgreSQL GiST exclusion constraints. |
| **NET-101** | **P3** | Port Binding | [`docker-compose.yml`](file:///D:/railway-github-check/Railway-Booking-System/docker-compose.yml) & [`.env.example`](file:///D:/railway-github-check/Railway-Booking-System/.env.example) | `FRONTEND_PORT` defaults to port `80` | Port 80 often requires root privileges or is in use by local host web servers (IIS, Apache). | Document clearly in README/Troubleshooting that setting `FRONTEND_PORT=8080` or `8000` avoids local host port 80 permission or collision issues. |

---

## 8. Recommendations & Next Steps

1. **Before Starting Runtime Stack (`docker compose up`):**
   - Resolve **SEC-101**: Update Prometheus configuration to dynamically receive `METRICS_PASSWORD` (via `password_file` or template entrypoint) to prevent 401 scrape mismatches.
   - Resolve **REL-101**: Add memory bounds (`--maxmemory 256mb --maxmemory-policy volatile-lru`) to the Redis command.
2. **Runtime Deployment Readiness:**
   - Once findings **SEC-101** and **REL-101** are addressed, the stack is completely ready for runtime execution.
   - All existing host services (backend `task-1843`, frontend `task-630`, monitoring containers) remain protected and isolated.

---

*Phase 17.2.1 Review is strictly complete. No files have been modified (except this documentation artifact). No containers have been started. Awaiting your approval before proceeding.*
