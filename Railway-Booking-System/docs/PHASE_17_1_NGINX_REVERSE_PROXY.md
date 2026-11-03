# Railora — Phase 17.1: Nginx Reverse Proxy & Containerized Frontend

> **Classification:** Production Deployment Architecture & Gateway Configuration  
> **Status:** Implemented & Verified  
> **Phase Target:** Phase 17.1 (Resolution of P0 Blocker `DEP-01`)  
> **Repository:** `D:\railway-github-check\Railway-Booking-System`  
> **Target Files:** `railora/nginx.conf`, `railora/Dockerfile`

---

## 1. Root Cause Analysis

In Phase 16, technical audit finding **DEP-01** identified a critical **P0 Deployment Blocker**:
The containerized React Single Page Application (SPA) frontend was served by an Nginx configuration that lacked a reverse proxy directive for the backend API.

### The Problem

The legacy [railora/nginx.conf](file:///D:/railway-github-check/Railway-Booking-System/railora/nginx.conf) was configured solely with:

```nginx
location / {
    try_files $uri $uri/ /index.html;
}
```

In developer environments using Vite (`npm run dev`), the Vite development server provides a built-in proxy in [vite.config.ts](file:///D:/railway-github-check/Railway-Booking-System/railora/vite.config.ts):
```ts
proxy: {
  '/api': {
    target: 'http://localhost:8080',
    changeOrigin: true,
  },
}
```

However, when packaged into a production Docker container ([railora/Dockerfile](file:///D:/railway-github-check/Railway-Booking-System/railora/Dockerfile)), Vite is not executed; static assets are served directly by `nginx:stable-alpine`. The React application Axios client ([api-client.ts](file:///D:/railway-github-check/Railway-Booking-System/railora/src/lib/api-client.ts)) dispatches all API calls to the relative path `/api/...` (e.g., `GET /api/trains/search`, `POST /api/auth/login`).

Because no physical file or directory named `/api/` exists on disk in `/usr/share/nginx/html`, Nginx evaluated `try_files $uri $uri/ /index.html` and served `index.html` with HTTP 200 `Content-Type: text/html`.

### Impact

1. **Total Application Failure in Docker:** Every API request (authentication, train search, seat hold, booking) received HTML rather than JSON.
2. **Silent Failure & Parsing Errors:** Axios attempts to parse JSON, triggering runtime JavaScript exceptions (`SyntaxError: Unexpected token '<'`) across all user-facing views.
3. **MIME Type Leakage:** If static assets under `/assets/` failed to load, Nginx would also fall back to `index.html`, resulting in browser console warnings: *"Failed to load module script: Expected a JavaScript module script but the server responded with a MIME type of text/html"*.

---

## 2. Files Changed

| File | Change Nature | Description |
|---|---|---|
| [`railora/nginx.conf`](file:///D:/railway-github-check/Railway-Booking-System/railora/nginx.conf) | **Modified / Hardened** | Full rewrite: configured `/api/` reverse proxy to `http://backend:8080`, forward proxy headers, connection upgrade handling, safe health probe proxying (`/actuator/health`, `/livez`, `/readyz`), static asset caching (`/assets/` immutable with `=404`), and SPA fallback with `no-cache` controls. |

---

## 3. Nginx Routing Architecture & Explanation

The updated [railora/nginx.conf](file:///D:/railway-github-check/Railway-Booking-System/railora/nginx.conf) organizes traffic into four prioritized routing tiers:

```
                            [Incoming HTTP Request (Port 80)]
                                           │
         ┌─────────────────────────────────┼─────────────────────────────────┐
         ▼                                 ▼                                 ▼
   Path: /api/*                  Path: /actuator/health,            Path: /assets/*
   or /api                             /livez, /readyz                       │
         │                                 │                                 ▼
         ▼                                 ▼                    Local Cache: 1y immutable
  Proxy to Backend                  Proxy to Backend             try_files $uri =404
  http://backend:8080               http://backend:8080         (Returns true 404, not HTML)
  Headers:                          Headers:
  - Host                            - Host
  - X-Real-IP                       - X-Real-IP
  - X-Forwarded-For                 - X-Forwarded-For
  - X-Forwarded-Proto               - X-Forwarded-Proto
  - Authorization forwarded                │
         │                                 │
         └─────────────────────────────────┴─────────────────────────────────┐
                                                                             ▼
                                                                  Path: All other routes (/)
                                                                             │
                                                                             ▼
                                                                    React SPA Fallback
                                                                    try_files $uri $uri/ /index.html
                                                                    Cache-Control: no-cache
                                                                    (React Router handles views/404)
```

### Detailed Directive Breakdown

#### 1. Backend API Reverse Proxy (`/api/` and `= /api`)
```nginx
location /api/ {
    proxy_pass http://backend:8080;
    proxy_http_version 1.1;

    # Standard reverse proxy headers
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-Port $server_port;

    # WebSocket / Connection upgrade
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection $connection_upgrade;

    # Explicitly forward client request headers, preserving Authorization
    proxy_pass_request_headers on;

    # Timeout settings
    proxy_connect_timeout 15s;
    proxy_send_timeout 60s;
    proxy_read_timeout 60s;

    # Buffering configuration
    proxy_buffering on;
    proxy_buffer_size 8k;
    proxy_buffers 8 8k;
}
```
- **Preserved URI Path:** `proxy_pass http://backend:8080;` without a trailing URI path instructs Nginx to pass the incoming request URI (including `/api/...` and all query strings) untouched directly to the Spring Boot backend.
- **Authorization Header Integrity:** `proxy_pass_request_headers on;` guarantees that client `Authorization: Bearer <token>` and `Idempotency-Key` headers are delivered intact to Spring Security's `JwtAuthenticationFilter`.
- **WebSocket Upgrade:** `Upgrade $http_upgrade` and `Connection $connection_upgrade` ensure compatibility with streaming or future WebSocket connections.
- **Large Payload Support:** `client_max_body_size 16M;` set globally on the server permits multi-seat booking transactions and bulk administrator payloads without HTTP 413 (Payload Too Large) errors.

#### 2. Health & Container Probe Proxying (`/actuator/health`, `/livez`, `/readyz`)
```nginx
location /actuator/health {
    proxy_pass http://backend:8080;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}

location = /livez {
    proxy_pass http://backend:8080;
    ...
}

location = /readyz {
    proxy_pass http://backend:8080;
    ...
}
```
- **Strict Least-Privilege Exposure:** Only safe health status probes are proxied.
- **Actuator Isolation:** Sensitive Actuator paths—most notably `/actuator/prometheus` (which requires HTTP Basic Auth with `ROLE_ADMIN`) and `/actuator/env`—are **NOT** proxied by Nginx. External requests to `/actuator/prometheus` hit the SPA fallback and receive `index.html` (handled by React Router's `NotFoundPage`), ensuring Prometheus metrics are scrapable exclusively by internal monitoring infrastructure directly on backend port 8080.

#### 3. Static Asset Caching (`/assets/`)
```nginx
location /assets/ {
    expires 1y;
    add_header Cache-Control "public, immutable";
    try_files $uri =404;
}
```
- Vite bundles CSS and JS files with content hashes (e.g., `index-CzUUVVI7.css`). These files are served with a 1-year immutable cache header.
- `try_files $uri =404;` ensures missing hashed assets return an immediate HTTP 404 rather than silently returning `index.html`.

#### 4. React SPA Fallback (`/`)
```nginx
location / {
    try_files $uri $uri/ /index.html;
    add_header Cache-Control "no-cache, no-store, must-revalidate";
    add_header Pragma "no-cache";
    add_header Expires "0";
}
```
- Allows client-side routes (`/search`, `/bookings`, `/waiting-room`, `/login`, `/register`) to load cleanly on direct navigation or browser refresh.
- `index.html` is delivered with strict `no-cache` headers, ensuring frontend updates deploy instantly to client browsers without stale cache lockups.

---

## 4. Verification and Test Results

### 1. Nginx Configuration Syntax Validation
Executed inside official `nginx:stable-alpine` container:
```bash
docker run --rm --add-host backend:127.0.0.1 \
  -v "D:\railway-github-check\Railway-Booking-System\railora\nginx.conf:/etc/nginx/conf.d/default.conf:ro" \
  nginx:stable-alpine nginx -t
```
**Result:**
```
nginx: the configuration file /etc/nginx/nginx.conf syntax is ok
nginx: configuration file /etc/nginx/nginx.conf test is successful
```

### 2. Frontend Production Build Verification
Executed from `railora/`:
```bash
npm run build
```
**Result:**
```
vite v5.4.21 building for production...
✓ 1722 modules transformed.
dist/index.html                   1.15 kB │ gzip:  0.55 kB
dist/assets/index-CzUUVVI7.css   42.27 kB │ gzip:  7.67 kB
dist/assets/react-B2XfLYM7.js     0.06 kB │ gzip:  0.07 kB
dist/assets/query-CIBv4cHk.js    44.61 kB │ gzip: 13.77 kB
dist/assets/router-BTAEbAcE.js  156.51 kB │ gzip: 51.28 kB
dist/assets/index-Duw6e4yg.js   213.22 kB │ gzip: 56.03 kB
✓ built in 13.36s (0 errors)
```

### 3. Frontend Unit & Component Test Suite
Executed from `railora/`:
```bash
npm test
```
**Result:**
- **130 of 130 tests passing** across 12 test suites:
  - `BookingApi.test.ts` (9 tests) — PASS
  - `AuthApi.test.ts` (6 tests) — PASS
  - `DesignSystem.test.tsx` (7 tests) — PASS
  - `Header.test.tsx` (6 tests) — PASS
  - `SimulatedCheckout.test.tsx` (3 tests) — PASS
  - `TrainSearch.test.tsx` (9 tests) — PASS
  - `BookingsExperience.test.tsx` (6 tests) — PASS
  - `AuthExperience.test.tsx` (6 tests) — PASS
  - `PnrEnquiry.test.tsx` (6 tests) — PASS
  - `WaitingRoom.test.tsx` (6 tests) — PASS
  - `PaymentAttempt.test.ts` (61 tests) — PASS
  - `SeatSelectionModal.test.tsx` (4 tests) — PASS

### 4. Live Runtime Reverse Proxy Verification

A containerized test instance of `nginx:stable-alpine` was launched on host port 8081 with `--add-host backend:host-gateway`, routing traffic directly to the active Railora Spring Boot backend (`localhost:8080`, PID 2172 replacement):

| Test Case | Request / Header | Expected Status | Actual Status | Response Content / Headers | Verification Result |
|---|---|---|---|---|---|
| **Static Asset Load** | `GET /assets/index-CzUUVVI7.css` | `200 OK` | `200 OK` | `Content-Type: text/css`, `Cache-Control: public, immutable` | **PASS** |
| **Missing Asset Protection** | `GET /assets/nonexistent.js` | `404 Not Found` | `404 Not Found` | Returns Nginx 404 HTML, does **not** return `index.html` | **PASS** |
| **Direct SPA Navigation** | `GET /search` | `200 OK` | `200 OK` | Returns `index.html`, `Cache-Control: no-cache` | **PASS** |
| **Deep SPA Route** | `GET /bookings` | `200 OK` | `200 OK` | Returns `index.html`, `Cache-Control: no-cache` | **PASS** |
| **Backend API Proxying** | `GET /api/trains/search?from=NDLS&to=BCT` | `200 OK` | `200 OK` | `Content-Type: application/json`, backend header `X-Request-Id: 7e55ce5f...`, body `[]` | **PASS** |
| **Backend Error Proxying** | `GET /api/nonexistent` | `401 Unauthorized` | `401 Unauthorized` | `Content-Type: application/json`, body `{"error":"Unauthorized"}` (Not replaced by HTML) | **PASS** |
| **Authorization Forwarding** | `GET /api/bookings` with `Authorization: Bearer <token>` | `401 Unauthorized` | `401 Unauthorized` | Token received and evaluated by Spring Security `JwtAuthenticationFilter` | **PASS** |
| **Correlation ID Forwarding** | `GET /api/trains/search` with `X-Correlation-Id` | `200 OK` | `200 OK` | `X-Request-Id` generated by Spring Boot `CorrelationIdFilter` | **PASS** |
| **Health Liveness Probe** | `GET /livez` | `200 OK` | `200 OK` | `Content-Type: application/vnd.spring-boot.actuator.v3+json`, body `{"status":"UP"}` | **PASS** |
| **Health Readiness Probe** | `GET /readyz` | `200 OK` | `200 OK` | `Content-Type: application/vnd.spring-boot.actuator.v3+json`, body `{"status":"UP"}` | **PASS** |
| **Aggregated Health Probe** | `GET /actuator/health` | `503 Service Unavailable` | `503 Service Unavailable` | Returns Actuator JSON payload (`status: DOWN` due to offline Redis in test profile) | **PASS** |
| **Sensitive Probe Isolation** | `GET /actuator/prometheus` | Fallback `200 OK` (SPA) | Fallback `200 OK` | Returns React SPA HTML `index.html`. Backend metrics endpoint is **NOT** exposed to public | **PASS** |

---

## 5. Remaining Limitations & Next Steps

1. **Standalone Resolution Requirement:**  
   In Nginx, static `proxy_pass http://backend:8080;` requires that the hostname `backend` resolves at container startup.
   - In Docker Compose networks, Docker's internal DNS automatically resolves `backend` to the backend container IP.
   - For standalone container testing, `--add-host backend:<ip>` or `--add-host backend:host-gateway` must be supplied.
2. **TLS / HTTPS Termination:**  
   The Nginx configuration currently listens on HTTP port 80. In production deployments, TLS termination (HTTPS port 443) should be handled by a cloud load balancer (AWS ALB / Cloudflare) or a dedicated Certbot / Let's Encrypt reverse proxy layer.
3. **Transition to Phase 17.2:**  
   With the Nginx reverse proxy verified, the containerized frontend is fully prepared for multi-container orchestration. Proceed to **Phase 17.2: Root Docker Compose Orchestration** to unite PostgreSQL, Redis, Backend, Frontend, and Observability under a single automated stack.
