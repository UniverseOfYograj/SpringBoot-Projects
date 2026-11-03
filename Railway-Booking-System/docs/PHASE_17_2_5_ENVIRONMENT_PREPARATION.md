# Railora — Phase 17.2.5: Secure Unified Environment Preparation Report

> **Classification:** Security & Configuration Pre-Deployment Delivery  
> **Status:** Completed (Pre-Deployment Verified)  
> **Date:** November 2026  
> **Repository:** `D:\railway-github-check\Railway-Booking-System`  
> **Deliverable:** `docs/PHASE_17_2_5_ENVIRONMENT_PREPARATION.md`  
> **Target Environment:** Isolated Railora Production Docker Compose Stack

---

## 1. Executive Summary

In accordance with the findings of the **Phase 17.2.4 Docker Compose Secret Injection Audit**, the configuration of the Railora production stack has been unified into a single root `.env` file. 

Prior to this phase, Docker Compose execution would have failed because environment variables were fragmented across `.env.secrets`, `monitoring/.env`, host environment variables, and placeholder defaults in `.env.example`. Compose does not merge multi-source environment files automatically.

In Phase 17.2.5:
1. A production-ready root `.env` file was synthesized securely using `.env.example` as the structural template.
2. Verified secrets were extracted from existing secure sources (`.env.secrets` and `monitoring/.env`) without modifying or re-generating them.
3. Independent, cryptographically secure passwords (32 alphanumeric characters, ~190.4 bits of cryptographic entropy each) were generated for `POSTGRES_PASSWORD`, `REDIS_PASSWORD`, and `GRAFANA_ADMIN_PASSWORD`.
4. Host database credentials (`$env:DB_PASSWORD`) and legacy monitoring passwords (`GF_SECURITY_ADMIN_PASSWORD`) were strictly isolated and not reused.
5. All security boundaries, Git-ignore policies, fail-safe validations, and zero-leak requirements were verified.
6. `docker compose config -q` passed with exit code 0.

---

## 2. Files Created and Preserved

### 2.1 Files Created

| Path | Purpose | Security Classification | Permissions / Git Status |
|---|---|---|---|
| `.env` | Unified root environment file for Docker Compose orchestration | **Secret (Confidential)** | Git-ignored (`.gitignore` line 58) |
| `docs/PHASE_17_2_5_ENVIRONMENT_PREPARATION.md` | Verification and audit report | Public / Documentation | Tracked in version control |

### 2.2 Source Files Preserved (Unmodified)

All existing secret files and configuration templates were preserved with byte-for-byte SHA-256 integrity:

| File Path | Original Status | Action Taken | SHA-256 Verification |
|---|---|---|:---:|
| `.env.example` | Configuration template with safe defaults & comments | Read-only template reference | **Preserved / Unmodified** |
| `.env.secrets` | Existing JWT access and refresh secrets | Read-only source | **Preserved / Unmodified** |
| `monitoring/.env` | Telemetry credentials (`METRICS_USER`, `METRICS_PASSWORD`) | Read-only source | **Preserved / Unmodified** |
| `monitoring/secrets/metrics_password` | Raw secret file mounted read-only to Prometheus | Read-only validation target | **Preserved / Unmodified** |

---

## 3. Variable Source Mapping Table

The unified root `.env` aggregates configurations according to the following resolution paths:

| Variable Name | Category | Source Origin | Resolution Method / Rule | Fail-Safe Operator |
|---|---|---|---|:---:|
| `COMPOSE_PROJECT_NAME` | Project | `.env.example` | Sourced directly (`railora-prod`) | Default fallback |
| `FRONTEND_PORT` | Networking | `.env.example` | Host port for React Nginx SPA (`80`) | Default fallback |
| `PROMETHEUS_PORT` | Networking | `.env.example` | Host loopback bind port (`9091`) | Default fallback |
| `GRAFANA_PORT` | Networking | `.env.example` | Host loopback bind port (`3001`) | Default fallback |
| `POSTGRES_DB` | Database | `.env.example` | Isolated database (`railway_booking_prod`) | Default fallback |
| `POSTGRES_USER` | Database | `.env.example` | Application user (`railora_user`) | Default fallback |
| `POSTGRES_PASSWORD` | Database | **Cryptographic RNG** | Generated independent 32-char secret (isolated from host DB) | `:?` Fail-Fast |
| `REDIS_PASSWORD` | Cache / Tatkal | **Cryptographic RNG** | Generated independent 32-char secret for `--requirepass` | `:?` Fail-Fast |
| `REDIS_MAXMEMORY` | Cache / Tatkal | `.env.example` | Memory limit (`512mb`) with `noeviction` | Default fallback |
| `JWT_ACCESS_SECRET` | Security / Auth | `.env.secrets` | Merged from existing 256-bit cryptographically secure secret | `:?` Fail-Fast |
| `JWT_REFRESH_SECRET` | Security / Auth | `.env.secrets` | Merged from existing 256-bit cryptographically secure secret | `:?` Fail-Fast |
| `JWT_ACCESS_EXPIRATION_MS` | Security / Auth | `.env.example` | Access token lifespan (`900000` ms = 15 min) | Default fallback |
| `JWT_REFRESH_EXPIRATION_MS` | Security / Auth | `.env.example` | Refresh token lifespan (`604800000` ms = 7 days) | Default fallback |
| `CORS_ALLOWED_ORIGINS` | Networking | `.env.example` | Permitted browser origins (`http://localhost,http://localhost:80,http://127.0.0.1`) | Default fallback |
| `RAILWAY_BASE_FARE` | Business Logic | `.env.example` | Flat fare (`500.00`) | Default fallback |
| `SEAT_HOLD_REAPER_*` | Business Logic | `.env.example` | Background seat reaper schedule and batch parameters | Default fallback |
| `METRICS_SCRAPE_AUTH_ENABLED` | Observability | `.env.example` | Enforces Spring Security Basic Auth on `/actuator/prometheus` | Default fallback |
| `METRICS_USER` | Observability | `monitoring/.env` | Dedicated scrape identity (`promuser`) | Default fallback |
| `METRICS_PASSWORD` | Observability | `monitoring/.env` | Aligned with `monitoring/secrets/metrics_password` mount | `:?` Fail-Fast |
| `METRICS_PASSWORD_FILE` | Observability | `.env.example` | Host path `./monitoring/secrets/metrics_password` | Default fallback |
| `GRAFANA_ADMIN_USER` | Observability | `.env.example` | Dashboard admin username (`admin`) | Default fallback |
| `GRAFANA_ADMIN_PASSWORD` | Observability | **Cryptographic RNG** | Generated independent 32-char secret (mapped to `GF_SECURITY_ADMIN_PASSWORD`) | `:?` Fail-Fast |
| `PROMETHEUS_RETENTION` | Observability | `.env.example` | Metric storage retention (`30d`) | Default fallback |
| `RAILWAY_PAYMENT_*` | Payments | `.env.example` | Simulated and mock payment flags set to `false` | Default fallback |
| `RAZORPAY_*` | Payments | `.env.example` | Production safe defaults (opt-in `false`, blank placeholders) | Default fallback |
| `RAILWAY_OUTBOX_KAFKA_*` | Messaging | `.env.example` | Production safe defaults (opt-in `false`, blank placeholders) | Default fallback |
| `RAILRADAR_*` | Integrations | `.env.example` | RailRadar base URL and blank API key placeholder | Default fallback |

---

## 4. Secret Security & Password Independence Validation

To ensure no cross-contamination between local development environments, existing monitoring installations, and the isolated containerized stack, all generated secrets were evaluated against cryptographic and independence rules.

### 4.1 Generation Algorithm
All newly generated secrets were created using `System.Security.Cryptography.RandomNumberGenerator` with unbiased rejection sampling over an alphanumeric keyspace (`[a-zA-Z0-9]`, 62 characters):
$$\text{Entropy} = 32 \times \log_2(62) \approx 190.54\text{ bits}$$

### 4.2 Independence Matrix

| Credential Comparison | Constraint | Observed Result | Status |
|---|---|:---:|:---:|
| `POSTGRES_PASSWORD` vs `REDIS_PASSWORD` | Must be independent | Distinct secrets | **PASS** |
| `POSTGRES_PASSWORD` vs `GRAFANA_ADMIN_PASSWORD` | Must be independent | Distinct secrets | **PASS** |
| `REDIS_PASSWORD` vs `GRAFANA_ADMIN_PASSWORD` | Must be independent | Distinct secrets | **PASS** |
| `POSTGRES_PASSWORD` vs Host `$env:DB_PASSWORD` | Must not reuse host development password | Distinct secrets | **PASS** |
| `GRAFANA_ADMIN_PASSWORD` vs `monitoring/.env` (`GF_SECURITY_ADMIN_PASSWORD`) | Must not reuse existing monitoring password | Distinct secrets | **PASS** |
| `METRICS_PASSWORD` vs `monitoring/secrets/metrics_password` | Must be byte-for-byte identical (Prometheus auth) | Byte-for-byte match confirmed | **PASS** |
| `JWT_ACCESS_SECRET` vs `.env.secrets` | Must preserve existing JWT secret | Byte-for-byte match confirmed | **PASS** |
| `JWT_REFRESH_SECRET` vs `.env.secrets` | Must preserve existing JWT secret | Byte-for-byte match confirmed | **PASS** |

---

## 5. Compose Configuration & Fail-Safe Validation

### 5.1 Syntax and Interpolation Test (`docker compose config -q`)
Running `docker compose config -q` against the newly prepared root `.env` executes the complete interpolation of all 6 services (`postgres`, `redis`, `backend`, `frontend`, `prometheus`, `grafana`) and 4 named volumes:
- **Interpolation Status:** Successful
- **Exit Code:** `0`
- **Output:** Clean (zero warnings, zero missing variable notices)
- **Zero-Leak Enforcement:** No rendered secrets printed to stdout or logs

### 5.2 Mandatory Variable Fail-Safe Test
Every mandatory variable guarded by the Compose `:?` operator was tested in isolation by setting it to empty/unset and evaluating Compose behavior:

| Tested Variable | Target Service | Compose Fail-Safe Directive | Fail-Safe Triggered? | Exit Code |
|---|---|---|:---:|:---:|
| `POSTGRES_PASSWORD` | `postgres`, `backend` | `${POSTGRES_PASSWORD:?mandatory}` | **Yes** (Interpolation aborted) | `1` |
| `REDIS_PASSWORD` | `redis`, `backend` | `${REDIS_PASSWORD:?mandatory}` | **Yes** (Interpolation aborted) | `1` |
| `JWT_ACCESS_SECRET` | `backend` | `${JWT_ACCESS_SECRET:?mandatory}` | **Yes** (Interpolation aborted) | `1` |
| `JWT_REFRESH_SECRET` | `backend` | `${JWT_REFRESH_SECRET:?mandatory}` | **Yes** (Interpolation aborted) | `1` |
| `METRICS_PASSWORD` | `backend` | `${METRICS_PASSWORD:?mandatory}` | **Yes** (Interpolation aborted) | `1` |
| `GRAFANA_ADMIN_PASSWORD` | `grafana` | `${GRAFANA_ADMIN_PASSWORD:?mandatory}` | **Yes** (Interpolation aborted) | `1` |

All six mandatory variables fail fast and halt orchestration before containers can be launched.

### 5.3 Service & Volume Resolution
- **Detected Services (6):** `postgres`, `redis`, `backend`, `frontend`, `prometheus`, `grafana`
- **Detected Volumes (4):** `postgres_data`, `redis_data`, `prometheus_data`, `grafana_data`
- **Detected Network (1):** `railora-network` (bridge)

---

## 6. Git-Ignore and Secret Leak Prevention

### 6.1 Git-Ignore Evaluation (`git check-ignore`)
All secret files in the workspace were checked against `.gitignore`:

```
Railway-Booking-System/.gitignore:58:.env                              .env
Railway-Booking-System/.gitignore:59:.env.*                            .env.secrets
Railway-Booking-System/.gitignore:58:.env                              monitoring/.env
Railway-Booking-System/.gitignore:65:/monitoring/secrets/*            monitoring/secrets/metrics_password
```

### 6.2 Version Control Cleanliness Check (`git status`)
- `git ls-files .env` returns empty (file is untracked).
- `git status --porcelain .env` returns empty (file is completely excluded from Git).
- No secrets exist in staged commits, Git index, or documentation reports.

---

## 7. Remaining Blockers & Pre-Deployment Checklist

With the completion of Phase 17.2.5, **all environment and credential blockers identified in Phase 17.2.4 have been resolved.**

### Pre-Deployment Verification Checklist for Phase 17.3

| Verification Item | Status | Notes |
|---|:---:|---|
| Unified Root `.env` Synthesized | **READY** | All required variables populated |
| PostgreSQL Independent Secret Set | **READY** | Isolated from host development DB |
| Redis High-Speed Cache Secret Set | **READY** | Isolated 32-char alphanumeric secret |
| Grafana Administrator Secret Set | **READY** | Isolated from existing monitoring stack |
| Actuator Metrics Scrape Secret Set | **READY** | Synchronized with Prometheus password mount |
| JWT Authentication Secret Set | **READY** | Merged from verified `.env.secrets` |
| Docker Compose Interpolation | **READY** | `docker compose config -q` passes (Exit Code 0) |
| Git Ignored & Untracked | **READY** | Zero secret leakage to version control |
| Host Port 80 Availability | **OPERATOR CHECK** | If port 80 is occupied on the host by Windows IIS, override `FRONTEND_PORT=8080` in `.env` |

---

## 8. Conclusion and Next Steps

Phase 17.2.5 is **complete**. The environment configuration for the isolated Railora Docker Compose production stack is fully prepared, hardened, and verified.

> **Instruction Adherence:**
> No containers were started, no existing services were stopped or restarted, no databases were modified, and no Git commits were created. Execution has paused awaiting explicit user authorization before proceeding to **Phase 17.3: Runtime Deployment & Verification**.
