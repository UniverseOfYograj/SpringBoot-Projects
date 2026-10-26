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
- Authentication (Stage 4), train search (Stage 3), and booking flows (Stage 5) are pending.
- All secrets are resolved from environment variables on the backend; no credentials in this repo.
