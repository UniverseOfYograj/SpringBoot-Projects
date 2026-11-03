# Railora — Phase 17.2.4: Docker Compose Secret Injection Audit

> **Classification:** Security & Configuration Pre-Deployment Audit  
> **Status:** Completed (Read-Only Audit)  
> **Audit Date:** November 2026  
> **Repository:** `D:\railway-github-check\Railway-Booking-System`  
> **Scope:** Audit of environment variable resolution paths, secret file sources, cross-file naming discrepancies, and multi-file Compose interpolation behavior.

---

## 1. Executive Summary

Docker Compose CLI does **not** automatically merge multiple environment files (`.env`, `.env.secrets`, `monitoring/.env`). By default, Docker Compose looks exclusively for a single file named `.env` in the project root directory.

This audit evaluates the exact variable resolution paths for all containerized services (`backend`, `postgres`, `redis`, `frontend`, `prometheus`, `grafana`), identifies source fragmentation across existing local files, and provides an actionable remediation plan before running Phase 17.3 runtime deployment.

### Audit Verdict: **PRE-DEPLOYMENT ACTION REQUIRED**
While all individual secrets exist locally or are safely generated, they are **fragmented across three different locations** with two variable naming discrepancies. Running `docker compose up -d` without synthesizing a unified, Git-ignored root `.env` file will result in Compose interpolation failures.

---

## 2. Service-by-Service Secret Resolution Audit

### 2.1 Backend Service Environment Variables

| Variable Name | Compose Syntax / Enforcement | Expected Source File | Current Source Status in Repo | Audit Verdict |
|---|---|---|---|:---:|
| `POSTGRES_PASSWORD` | `${POSTGRES_PASSWORD:?mandatory}` | Root `.env` | **Missing in files.** Present in host shell as `$env:DB_PASSWORD`. Not yet written to a root `.env`. | **REQUIRES MAPPING** |
| `JWT_ACCESS_SECRET` | `${JWT_ACCESS_SECRET:?mandatory}` | `.env.secrets` | Present in `.env.secrets` (256-bit cryptographically secure). Compose does not auto-read `.env.secrets`. | **REQUIRES MERGE** |
| `JWT_REFRESH_SECRET` | `${JWT_REFRESH_SECRET:?mandatory}` | `.env.secrets` | Present in `.env.secrets` (256-bit cryptographically secure). Compose does not auto-read `.env.secrets`. | **REQUIRES MERGE** |
| `METRICS_USER` | `${METRICS_USER:-promuser}` | `monitoring/.env` | Sourced from `monitoring/.env` (`promuser`) and has a safe default `promuser`. | **PASS** |
| `METRICS_PASSWORD` | `${METRICS_PASSWORD:?mandatory}` | `monitoring/.env` | Present in `monitoring/.env` & `monitoring/secrets/metrics_password`. Compose does not auto-read `monitoring/.env`. | **REQUIRES MERGE** |
| `RAILWAY_BASE_FARE` | `${RAILWAY_BASE_FARE:-500.00}` | Compose Default | Defaults safely to `500.00`. Mapped to `railway.fare.base-amount` in production properties. | **PASS** |
| `REDIS_PASSWORD` | `${REDIS_PASSWORD:?mandatory}` | Root `.env` | **Missing.** Only a placeholder exists in `.env.example`. Must be cryptographically generated. | **REQUIRES GENERATION** |

---

### 2.2 Prometheus Telemetry Service

| Check Item | Target Configuration | Observed State | Audit Verdict |
|---|---|---|:---:|
| **Password File Bind Mount** | `${METRICS_PASSWORD_FILE:-./monitoring/secrets/metrics_password}:/etc/prometheus/secrets/metrics_password:ro` | Resolves to local file `monitoring/secrets/metrics_password`. Mount is read-only (`:ro`). | **PASS** |
| **Container File Readability** | `/etc/prometheus/secrets/metrics_password` | Host file exists (17 bytes). World-readable on host, accessible by unprivileged container user `nobody`. | **PASS** |
| **Password Content Alignment** | Must match backend `METRICS_PASSWORD` | Cryptographic SHA-256 match confirmed between `monitoring/secrets/metrics_password` and `monitoring/.env`. | **PASS** |
| **Username Alignment** | Must match `promuser` | `monitoring/prometheus/prometheus-compose.yml` specifies `username: "promuser"`. Exact match with backend. | **PASS** |

---

### 2.3 Grafana Service & Naming Discrepancy Analysis

| Variable Name | In `docker-compose.yml` | In `monitoring/.env` | Discrepancy & Impact | Audit Verdict |
|---|---|---|---|:---:|
| `GF_SECURITY_ADMIN_USER` | `${GRAFANA_ADMIN_USER:-admin}` | `GF_SECURITY_ADMIN_USER=admin` | Safe: Defaults to `admin` if unset. | **PASS** |
| `GF_SECURITY_ADMIN_PASSWORD` | `${GRAFANA_ADMIN_PASSWORD:?mandatory}` | `GF_SECURITY_ADMIN_PASSWORD=...` | **Naming Discrepancy:** Compose expects `GRAFANA_ADMIN_PASSWORD`, but `monitoring/.env` defines `GF_SECURITY_ADMIN_PASSWORD`. Direct pass of `monitoring/.env` will fail with an unset error. | **DISCREPANCY (Requires Mapping)** |

---

### 2.4 PostgreSQL and Redis Mandatory Credential Enforcement

| Service | Variable Name | Compose Interpolation Rule | Fail-Safe Behavior | Audit Verdict |
|---|---|---|---|:---:|
| **PostgreSQL** | `POSTGRES_PASSWORD` | `${POSTGRES_PASSWORD:?POSTGRES_PASSWORD must be configured in environment}` | Rejects startup immediately with exit code 1 if empty or unset. No insecure defaults. | **PASS** |
| **Redis** | `REDIS_PASSWORD` | `${REDIS_PASSWORD:?REDIS_PASSWORD must be configured in environment}` | Rejects startup immediately with exit code 1 if empty or unset. Enforced in both `command:` and `environment:`. | **PASS** |

---

## 3. The Root Cause of Variable Fragmentation

Docker Compose variable resolution evaluates variables in the following strict order of precedence:
1. Shell environment variables of the calling process (`$env:...` in PowerShell / `export` in Bash).
2. The project `.env` file in the directory of `docker-compose.yml`.
3. Default values specified in `docker-compose.yml` (`${VAR:-default}`).

### Why Passing Multiple `--env-file` Flags Is Insufficient
Even if an operator runs `docker compose --env-file .env.secrets --env-file monitoring/.env config`:
1. **Missing Variables:** `POSTGRES_PASSWORD` and `REDIS_PASSWORD` exist in neither `.env.secrets` nor `monitoring/.env`. Compose fails with exit code 1.
2. **Name Mismatches:** `GF_SECURITY_ADMIN_PASSWORD` in `monitoring/.env` does not satisfy `${GRAFANA_ADMIN_PASSWORD:?}` in Compose.
3. **No Root `.env`:** The root directory contains only `.env.example` (template with placeholders) and `.env.secrets` (JWT only).

---

## 4. Pre-Deployment Remediation Plan

To achieve 100% reliable, zero-leak runtime deployment for Phase 17.3, execute the following safe, deterministic procedure:

### Step 1: Synthesize a Unified Local `.env` File (Git-Ignored)
Before launching `docker compose up -d`, synthesize a single, complete local `.env` file in the project root by:
1. Copying the structure and production defaults from `.env.example`.
2. Extracting `JWT_ACCESS_SECRET` and `JWT_REFRESH_SECRET` from `.env.secrets`.
3. Extracting `METRICS_USER` and `METRICS_PASSWORD` from `monitoring/.env`.
4. Extracting `GF_SECURITY_ADMIN_PASSWORD` from `monitoring/.env` and mapping it to `GRAFANA_ADMIN_PASSWORD`.
5. Mapping `POSTGRES_PASSWORD` from `$env:DB_PASSWORD` (or generating a dedicated production password).
6. Cryptographically generating a strong random `REDIS_PASSWORD` (e.g. 32-byte Base64 string).

### Step 2: (Optional Hardening) Docker Compose Variable Fallback Resilience
In `docker-compose.yml`, support alternative naming conventions gracefully:
- `GF_SECURITY_ADMIN_PASSWORD: ${GRAFANA_ADMIN_PASSWORD:-${GF_SECURITY_ADMIN_PASSWORD:?GRAFANA_ADMIN_PASSWORD must be configured in environment}}`
- `POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-${DB_PASSWORD:?POSTGRES_PASSWORD must be configured in environment}}`

---

## 5. Summary Table of Environment Resolution Paths

| Service | Target Property | Resolving Variable | Recommended Source | Fallback / Fail-Safe |
|---|---|---|---|---|
| **postgres** | `POSTGRES_PASSWORD` | `POSTGRES_PASSWORD` | Unified `.env` (mapped from `$env:DB_PASSWORD`) | `:?` Fail-Fast Error |
| **postgres** | `POSTGRES_DB` | `POSTGRES_DB` | Unified `.env` / Default | `railway_booking_prod` |
| **redis** | `--requirepass` | `REDIS_PASSWORD` | Unified `.env` (cryptographically generated) | `:?` Fail-Fast Error |
| **backend** | `DB_PASSWORD` | `POSTGRES_PASSWORD` | Unified `.env` | Matches PostgreSQL container |
| **backend** | `REDIS_PASSWORD` | `REDIS_PASSWORD` | Unified `.env` | Matches Redis container |
| **backend** | `JWT_ACCESS_SECRET` | `JWT_ACCESS_SECRET` | Unified `.env` (merged from `.env.secrets`) | `:?` Fail-Fast Error |
| **backend** | `JWT_REFRESH_SECRET` | `JWT_REFRESH_SECRET` | Unified `.env` (merged from `.env.secrets`) | `:?` Fail-Fast Error |
| **backend** | `METRICS_PASSWORD` | `METRICS_PASSWORD` | Unified `.env` (merged from `monitoring/.env`) | Matches `metrics_password` mount |
| **prometheus** | `password_file` | Mount Path | `monitoring/secrets/metrics_password` | `:ro` bind mount |
| **grafana** | `GF_SECURITY_ADMIN_PASSWORD` | `GRAFANA_ADMIN_PASSWORD` | Unified `.env` (mapped from `GF_SECURITY_ADMIN_PASSWORD`) | `:?` Fail-Fast Error |

---

*Phase 17.2.4 Audit is complete. No files have been modified (except this documentation artifact). No containers have been started. Awaiting your authorization.*
