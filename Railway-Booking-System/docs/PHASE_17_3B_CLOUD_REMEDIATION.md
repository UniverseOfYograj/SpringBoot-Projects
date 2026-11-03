# Railora — Phase 17.3B: Cloud Deployment Remediation Report

> **Classification:** Security, Architecture & Cloud Deployment Remediation  
> **Status:** Completed & Verified (Ready for Deployment Approval)  
> **Date:** November 2026  
> **Repository:** `D:\railway-github-check\Railway-Booking-System`  
> **Source of Truth:** Phase 17.3A Audit (`docs/PHASE_17_3A_CLOUD_DEPLOYMENT_PREFLIGHT.md`)  
> **Target Cloud Stack:**  
> - **Frontend:** Vercel (React 18 + Vite SPA)  
> - **Backend:** Render (Docker container, Java 21 / Spring Boot 3.3.5)  
> - **Database:** Neon (Serverless Managed PostgreSQL 16)  
> - **Cache / Tatkal Store:** Upstash Redis (Serverless Managed Redis with TLS)  
> - **Monitoring:** Existing Prometheus/Grafana stack preserved for deferred cloud evaluation  

---

## 1. Executive Summary

Following the **Phase 17.3A Cloud Deployment Preflight & Compatibility Audit**, this phase resolves the identified compatibility warnings with minimal, verified, backward-compatible configuration changes.

All 10 required tasks were executed under strict safety constraints:
- Zero cloud deployments or account modifications.
- Zero local database or remote Redis connections.
- Zero Docker containers started.
- Zero Git mutations (no commits, staging, pushes, resets, or stashes).
- Automated test validation executed: **130 Frontend unit/integration tests passed (100%)**, **64 Backend security/observability/payment tests passed (100%)**, and Docker Compose validation passed with exit code 0.

---

## 2. Remediated Files & Technical Changes

### 2.1 File Change Summary

| File Path | Nature of Change | Purpose |
|---|---|---|
| `railora/vercel.json` | **Created** | Configures Vercel edge rewrite rules for HTML5 SPA client-side deep linking. |
| `src/main/resources/application.properties` | **Modified** | Configures dynamic port binding: `server.port=${PORT:8080}`. |
| `src/main/resources/application-production.properties` | **Modified** | Enables native forwarded headers (`X-Forwarded-For`), enables simulated payment for public demo (`RAILWAY_PAYMENT_SIMULATED_ENABLED:true`), and defaults metrics scrape auth safely to false when Prometheus is deferred. |
| `src/main/java/in/yograj/learnspringboot/railway_booking/config/SecurityConfig.java` | **Modified** | Updates CORS configuration to use `setAllowedOriginPatterns(origins)` to support exact and pattern-based Vercel domains with credentials. |
| `docs/PHASE_17_3B_CLOUD_REMEDIATION.md` | **Created** | Comprehensive remediation and verification report. |

---

### 2.2 Detailed Diffs and Explanations

#### 1. Vercel SPA Client-Side Routing (`railora/vercel.json`)
```json
{
  "$schema": "https://openapi.vercel.sh/vercel.json",
  "rewrites": [
    {
      "source": "/(.*)",
      "destination": "/index.html"
    }
  ]
}
```
*Impact:* Eliminates HTTP 404 Not Found errors when users navigate directly or refresh deep links (`/search`, `/bookings`, `/waiting-room`, `/login`).

#### 2. Dynamic Port Binding (`application.properties`)
```properties
- server.port=8080
+ server.port=${PORT:8080}
```
*Impact:* If Render supplies the `PORT` environment variable (e.g., `PORT=10000`), Spring Boot automatically binds to it. When running locally or in Docker Compose without `PORT`, it safely defaults to `8080`.

#### 3. Cloud Edge Proxy Headers & Public Demo Settings (`application-production.properties`)
```properties
+ # Trust reverse proxy headers (X-Forwarded-For, X-Forwarded-Proto) from cloud edge (Render/Cloudflare)
+ server.forward-headers-strategy=${SERVER_FORWARD_HEADERS_STRATEGY:native}

  railway.security.cors.allowed-origins=${CORS_ALLOWED_ORIGINS}

  razorpay.enabled=${RAZORPAY_ENABLED:false}
  razorpay.key-id=${RAZORPAY_KEY_ID:}
  razorpay.key-secret=${RAZORPAY_KEY_SECRET:}
  razorpay.webhook-secret=${RAZORPAY_WEBHOOK_SECRET:}
  railway.payment.mock-enabled=false
- railway.payment.simulated-enabled=false
+ railway.payment.simulated-enabled=${RAILWAY_PAYMENT_SIMULATED_ENABLED:true}

  # Dedicated Metrics Scrape Authentication (Phase 15C / Phase 17.2)
- management.metrics.scrape.auth.enabled=${METRICS_SCRAPE_AUTH_ENABLED:true}
+ # When external Prometheus scraping is deferred, defaults safely to false (endpoint protected by ROLE_ADMIN)
+ management.metrics.scrape.auth.enabled=${METRICS_SCRAPE_AUTH_ENABLED:false}
```
*Impact:* 
- `server.forward-headers-strategy=native` activates Tomcat's `RemoteIpValve`, allowing `LoginRateLimiter` to inspect the true client IP from `X-Forwarded-For` rather than Render's internal reverse proxy IP.
- `railway.payment.simulated-enabled` allows public demo visitors to simulate checkout end-to-end without entering credit card details, while mock and Razorpay payments remain strictly disabled.
- `management.metrics.scrape.auth.enabled` defaults to `false`, preventing startup failure if `METRICS_USER` is unset while keeping `/actuator/prometheus` strictly guarded by `ROLE_ADMIN` JWT authentication.

#### 4. CORS Allowed Origin Patterns (`SecurityConfig.java`)
```java
- configuration.setAllowedOrigins(origins);
+ configuration.setAllowedOriginPatterns(origins);
```
*Impact:* Standard W3C CORS forbids wildcard `*` with `allowCredentials(true)`. By using Spring's `setAllowedOriginPatterns`, exact origins (`https://railora.vercel.app`) as well as pattern-based Vercel branch/preview URLs (`https://*-railora.vercel.app`) are supported with credentials.

---

## 3. Systematic Task Verification

### Task 1: Vercel SPA Rewrite Configuration
- **Status:** **RESOLVED**
- `railora/vercel.json` created with `$schema` validation and a catch-all rewrite rule (`/(.*) -> /index.html`).

### Task 2: Render Port Binding & Forwarded Headers
- **Status:** **RESOLVED**
- Dynamic port `server.port=${PORT:8080}` verified.
- `server.forward-headers-strategy=native` verified.

### Task 3: Health-Check Endpoints (`/livez` and `/readyz`)
- **Status:** **VERIFIED**
- Both `/livez` and `/readyz` are configured via `management.endpoint.health.probes.add-additional-paths=true` and explicitly permitted without authentication in `SecurityConfig.java`.
- Verified in `ObservabilityFoundationTests`:
  - `/livez` tests process liveness independently of databases or external systems.
  - `/readyz` tests core readiness (PostgreSQL + disk space) and remains resiliently `UP` even when Redis is offline (fail-open architecture).
  - Health responses never leak database connection strings or credentials.

### Task 4: Production CORS Configuration
- **Status:** **RESOLVED**
- `configuration.setAllowedOriginPatterns(origins)` dynamically reflects matching origins when credentials are used.
- Permitted headers include `Authorization`, `Content-Type`, `Accept`, `X-Tatkal-Admission`, `Idempotency-Key`, `X-Request-Id`, `X-Requested-With`, `Origin`.
- Exposed headers include `Authorization`, `Retry-After`, `X-Request-Id`.
- In Render, the operator configures:
  `CORS_ALLOWED_ORIGINS=https://<railora-frontend>.vercel.app`

### Task 5: Redis Key Prefixes & Upstash Production Isolation
- **Status:** **VERIFIED**
- Codebase review confirms strict key namespacing:
  - Cache: `journeySearch::*`, `trainSearch::*`, `trainTopology::*`
  - Tatkal Store: `tatkal:{<journeyId>}:*`
- Redis cluster hash tags `{journeyId}` guarantee all keys for a train journey hash to the same cluster slot on Upstash.
- No connections or mutations were made to Upstash.

### Task 6: Prometheus Metrics Exposure & Security
- **Status:** **VERIFIED**
- Unauthenticated requests to `/actuator/prometheus` receive `401 Unauthorized` in all configurations.
- When `management.metrics.scrape.auth.enabled=false`, `/actuator/prometheus` falls back to `SecurityConfig.java` line 113: `.requestMatchers("/actuator/prometheus").hasRole("ADMIN")`.
- When `management.metrics.scrape.auth.enabled=true`, `MetricsSecurityConfig` activates HTTP Basic Auth requiring non-blank `METRICS_USER` and `METRICS_PASSWORD`.

### Task 7: Initial Admin and Journey Seeding
- **Status:** **VERIFIED**
- In `RailwayBookingApplication.java`:
  - **Idempotency:** Both seeders check database existence before inserting (`userRepository.existsByUsername("admin")` and `journeyRepository.existsByTrain_TrainIdAndFromStation_StationIdAndToStation_StationIdAndJourneyDate(...)`).
  - **Secure Password Enforcement:** Throws `IllegalStateException` immediately on startup if `SEED_ADMIN_PASSWORD` is blank or unset when admin seeding is enabled.
  - **No Default Credentials:** The hardcoded fallback `Admin@123` has been completely eliminated (verified by `AdminUserSeedingTests`).
  - **Safe Disabling:** Default property values are `railway.data-seed.admin.enabled=false` and `railway.data-seed.journey.enabled=false`. After initial boot seeding, toggling both environment variables to `false` prevents execution on subsequent restarts.

### Task 8: Simulated Payment Gateway for Public Demo
- **Status:** **RESOLVED**
- `railway.payment.simulated-enabled=${RAILWAY_PAYMENT_SIMULATED_ENABLED:true}` is configured in `application-production.properties`.
- Allows end-to-end booking, hold confirmation, simulated payment, and PNR issuance without requiring active merchant credentials.

### Task 9: Real Payment Processing Strictly Disabled
- **Status:** **VERIFIED**
- In `application-production.properties`:
  - `razorpay.enabled=${RAZORPAY_ENABLED:false}` (opt-in only with merchant keys).
  - `razorpay.key-id=`, `razorpay.key-secret=`, `razorpay.webhook-secret=` (empty by default).
  - `railway.payment.mock-enabled=false` (test mocks disabled in production).

### Task 10: Neon JDBC Direct Connection, SSL & Flyway Startup
- **Status:** **VERIFIED**
- Connection string formatted as:
  `DB_URL=jdbc:postgresql://<neon-host>:5432/<dbname>?sslmode=require`
- Flyway executes before Hibernate (`EntityManagerFactoryDependsOnFlywayPostProcessor`).
- Neon's Direct Connection (`ep-xyz...`) is specified rather than the PgBouncer pooled connection (`ep-xyz-pooler...`), ensuring DDL migrations V1 through V7 and `CREATE EXTENSION IF NOT EXISTS btree_gist;` execute cleanly without transaction pool interference.

---

## 4. Automated Test Validation Results

### 4.1 Frontend Test Suite (`railora`)
- **Command:** `npm run typecheck && npm run test`
- **Result:** **100% PASS**
  - **TypeScript Compilation:** 0 errors (`tsc --noEmit`).
  - **Test Files:** 12 passed / 12 total.
  - **Tests Run:** 130 passed / 130 total.
  - **Coverage Areas:** `TrainSearch`, `SeatSelectionModal`, `SimulatedCheckout`, `PnrEnquiry`, `AuthExperience`, `BookingsExperience`, `WaitingRoom`, `DesignSystem`.

### 4.2 Backend Test Suite (`Maven Surefire`)
- **Command:** `./mvnw.cmd test -Dtest=SecurityHardeningIntegrationTests,MetricsSecurityIntegrationTests,ObservabilityFoundationTests,AdminUserSeedingTests,SimulatedPaymentLifecycleTests`
- **Result:** **100% PASS**
  - **Tests Run:** 64 passed / 64 total.
  - **Failures:** 0.
  - **Errors:** 0.
  - **Skipped:** 0.
  - **Coverage Areas:**
    - `AdminUserSeedingTests` (9 tests): Validates fail-fast on missing password, absence of default fallback, idempotency, and conditional registration.
    - `ObservabilityFoundationTests` (5 tests): Validates `/livez`, `/readyz`, Hikari connection pool configuration, and zero credential leakage.
    - `SecurityHardeningIntegrationTests` (18 tests): Validates brute force rate limiting, JWT rotation, revocation, CORS, and endpoint authorization.
    - `MetricsSecurityIntegrationTests` (17 tests): Validates `/actuator/prometheus` security chain, credential validation, and chain isolation.
    - `SimulatedPaymentLifecycleTests` (15 tests): Validates end-to-end simulated checkout, hold expiration, retry with idempotency keys, and PNR issuance.

### 4.3 Local Compose Validation
- **Command:** `docker compose config -q`
- **Result:** Exit Code `0` (clean parse, resolution, and interpolation).

---

## 5. Remaining Warnings & Pre-Deployment Checklist

All code and template configuration warnings from Phase 17.3A have been **resolved**. Only operator environment variable inputs remain for Phase 17.3 runtime deployment:

| Item | Status | Action Required by Operator during Deployment |
|---|:---:|---|
| **Neon Database** | **READY** | Create Neon database, copy direct JDBC URL with `?sslmode=require`. |
| **Upstash Redis** | **READY** | Verify Upstash endpoint, port 6379, and password. |
| **Render Web Service** | **READY** | Deploy from Dockerfile, set Health Check path to `/livez`, populate environment variables. |
| **Vercel Frontend** | **READY** | Deploy `railora/` with `VITE_API_BASE_URL` pointing to Render. |
| **First-Boot Seeding** | **READY** | Set `SEED_ADMIN_USER_ENABLED=true`, provide strong `SEED_ADMIN_PASSWORD`, set `SEED_JOURNEY_DATA_ENABLED=true`. Toggle to `false` on subsequent deployments. |

---

## 6. Safety Compliance Summary

- **Safety Invariant:** Zero database connections or mutations were made.
- **Safety Invariant:** Zero Upstash Redis commands or connections were made.
- **Safety Invariant:** Zero cloud accounts or deployments were initiated.
- **Safety Invariant:** Zero Docker containers were started.
- **Safety Invariant:** Zero credentials or secret values were logged or printed.
- **Safety Invariant:** Repository Git status strictly preserved (no commits, staging, pushes, resets, or stashing).

*Phase 17.3B remediation is complete. Awaiting explicit user approval before proceeding to Phase 17.3 runtime deployment.*
