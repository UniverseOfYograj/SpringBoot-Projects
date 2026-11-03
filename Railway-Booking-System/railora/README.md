# Railora Frontend

> **Stage 1 — Scaffold & Design Foundation**

A professional railway booking web application powered by:
- **Backend:** Java 21 + Spring Boot 4.1.0 + PostgreSQL (port 8080)
- **Frontend:** React 18 + TypeScript + Vite (port 5173)

## Project structure

```
railora/
├── src/
│   ├── app/router.tsx           # Central React Router configuration
│   ├── components/layout/       # AppShell, Header, Footer
│   ├── lib/
│   │   ├── api-client.ts        # Centralized Axios instance (proxy → :8080)
│   │   ├── constants.ts         # Route paths, API paths, brand tokens
│   │   └── utils.ts             # cn, formatTime, formatCurrency
│   ├── pages/                   # HomePage, NotFoundPage
│   └── test/                    # Vitest + RTL test suite
├── tailwind.config.js           # Railora design tokens
├── vite.config.ts               # Dev server :5173, Vite proxy /api → :8080
└── index.html
```

## Commands

```bash
npm install       # Install dependencies
npm run dev       # Development server on http://localhost:5173
npm run build     # Production build (dist/)
npm run test      # Run Vitest test suite
npm run lint      # TypeScript type-check
```

## Development notes

- The Vite dev server proxies `/api/*` → `http://localhost:8080`. No CORS changes required.
- Authentication, seat holds, bookings, and payment lifecycle are available.
- All secrets are resolved from environment variables on the backend; no credentials in this repo.

## Razorpay sandbox payments

- Razorpay is disabled by default. Enable the backend with `RAZORPAY_ENABLED=true` and provide `RAZORPAY_KEY_ID` (must use the `rzp_test_` prefix), `RAZORPAY_KEY_SECRET`, and `RAZORPAY_WEBHOOK_SECRET` as server environment variables.
- Enable the frontend Checkout UI at build time with `VITE_RAZORPAY_ENABLED=true`. The public key ID and server-created order are returned by the authenticated backend; secret values are never included in the frontend.
- The frontend API base defaults to `/api`; configure `VITE_API_BASE_URL` only when the deployment uses a different API route.
- MOCK_UPI is available only in development and tests. Production builds do not fall back to MOCK_UPI when Razorpay is disabled.
- Booking fares use a fixed server-side amount configured by `RAILWAY_BASE_FARE` in INR. Non-production defaults to 500.00; production requires the variable. No route-distance data currently exists, so all bookings use this same flat fare.
- Gateway refund requests are intentionally deferred until a refund policy is defined. Cancellation records `REFUND_REQUIRED` for captured Razorpay payments, and only a verified refund webhook can mark a refund processed.

### Isolated local Razorpay E2E profile

Use the backend `razorpay-e2e` Spring profile only with the dedicated PostgreSQL database `railway_booking_test`. It requires `RAZORPAY_E2E_DB_URL`, `RAZORPAY_E2E_DB_USERNAME`, and `RAZORPAY_E2E_DB_PASSWORD`; there is no fallback to the default `railway_booking` datasource. Before Flyway runs, startup checks that the configured URL names `railway_booking_test` and that the connected PostgreSQL catalog and JDBC metadata confirm the same database. Startup fails if either identity cannot be verified.

The profile also requires `RAZORPAY_ENABLED`, `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET`, `JWT_ACCESS_SECRET`, `JWT_REFRESH_SECRET`, and `RAILWAY_BASE_FARE`. Razorpay must be enabled and the key ID must start with `rzp_test_`. MOCK_UPI, Kafka publishing, outbox polling, cache usage, and data seeding are disabled in this profile. Flyway remains enabled; only run the profile after confirming the dedicated test database is disposable/test-only and the configured credentials cannot access the development database.

In PowerShell, set the profile and non-secret settings explicitly:

```powershell
$env:SPRING_PROFILES_ACTIVE = 'razorpay-e2e'
$env:RAZORPAY_E2E_DB_URL = 'jdbc:postgresql://localhost:5432/railway_booking_test'
$env:RAZORPAY_E2E_DB_USERNAME = Read-Host 'Test database username'
$env:RAZORPAY_E2E_DB_PASSWORD = Read-Host 'Test database password' -AsSecureString | ForEach-Object {
  $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($_)
  try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
  finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
}
$env:RAZORPAY_ENABLED = 'true'
$env:RAZORPAY_KEY_ID = Read-Host 'Razorpay Test Mode key ID'
foreach ($name in 'RAZORPAY_KEY_SECRET', 'RAZORPAY_WEBHOOK_SECRET', 'JWT_ACCESS_SECRET', 'JWT_REFRESH_SECRET') {
  $secure = Read-Host $name -AsSecureString
  $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
  try { [Environment]::SetEnvironmentVariable($name, [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr), 'Process') }
  finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
}
$env:RAILWAY_BASE_FARE = '500.00'
```

For the React development server, use the same-origin API proxy and enable only the checkout UI:

```powershell
$env:VITE_API_BASE_URL = '/api'
$env:VITE_RAZORPAY_ENABLED = 'true'
```

In the Razorpay Dashboard, explicitly switch to **Test Mode** before generating or viewing API keys. Use only a key whose ID begins `rzp_test_`; do not use keys shown in Live Mode. Configure the dashboard webhook with the local tunnel/test endpoint only when separately authorized to test webhook delivery. The React app can use its existing Vite `/api` proxy to reach `http://localhost:8080`; browser code receives only the public key ID, never backend secrets.

## Authentication session behavior

- Access and refresh tokens are held in memory only. Refreshing the browser clears the current session until the backend's final refresh-cookie design is available.
- Signing out clears the in-memory session; it does not revoke the server-side refresh token.
- The frontend exposes the refresh API client method, but does not automatically refresh tokens or persist them in browser storage.
