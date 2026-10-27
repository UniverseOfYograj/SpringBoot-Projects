# Railora Authentication and Session Security Review

**Review date:** 2026-10-26  
**Scope:** Read-only source inspection of the current Railora frontend and Spring Boot authentication/session implementation. No application code was changed. No tests, services, migrations, or database connections were run.

## Executive summary

The current frontend persists the complete authentication response—including both bearer access and refresh tokens—in `localStorage`. It restores the user by calling `/api/users/me` with the saved access token, but does not use the saved refresh token when that check or a later API call receives an error. The default access-token lifetime is 15 minutes and refresh-token lifetime is 7 days.

The backend validates refresh tokens against a database row and their JWT signature/expiry. Refresh does not rotate the refresh token: a successful response returns the same refresh token. There is no backend logout endpoint. The visible frontend sign-out clears only browser state, leaving server-side refresh-token records untouched; stateless access tokens remain valid until expiry.

The highest-priority issue is the persistent, JavaScript-readable refresh token. The least disruptive immediate mitigation is to stop persisting bearer credentials in browser storage and keep the session in memory, accepting that reload requires sign-in. If persistence is a product requirement, coordinate a backend-supported HttpOnly-cookie design with CSRF and deployment-origin controls rather than changing the frontend alone.

## Actual authentication lifecycle

### Registration

1. `RegisterPage` validates required fields and basic email syntax, then calls `authApi.register`.
2. The frontend sends the registration fields to `POST /api/auth/register` through the shared Axios client.
3. `AuthController.register` calls `UserService.registerUser` and returns HTTP `201`.
4. `UserServiceImpl` trims username/email and encodes the password with the configured `PasswordEncoder` before saving the user. `SecurityConfig` supplies `BCryptPasswordEncoder`.
5. Registration does not issue tokens or create an authenticated frontend session. On success the UI navigates to sign-in.

Sources: [`RegisterPage.tsx`](../railora/src/pages/RegisterPage.tsx), [`api.ts`](../railora/src/features/auth/api.ts), [`AuthController.java`](../src/main/java/in/yograj/learnspringboot/railway_booking/controller/AuthController.java), [`UserServiceImpl.java`](../src/main/java/in/yograj/learnspringboot/railway_booking/service/UserServiceImpl.java), [`SecurityConfig.java`](../src/main/java/in/yograj/learnspringboot/railway_booking/config/SecurityConfig.java).

### Login and initial session establishment

1. `LoginPage` validates non-empty username/email and password, then calls `AuthContext.login`.
2. `AuthContext.login` calls `authApi.login`, which POSTs credentials to `/api/auth/login`.
3. The backend `AuthenticationManager` authenticates the username or email and password. It then creates a signed access JWT and a signed refresh JWT, persists a refresh-token row, and returns both tokens in `AuthResponse`.
4. The frontend's `setSession` stores the entire response in module memory **and** serializes it to `localStorage` under `railora_auth_session`.
5. `AuthContext.login` next calls `GET /api/users/me` with the access token. It sets state to authenticated on success. If that profile request fails for any reason, it deletes the stored session, marks the app unauthenticated, and rethrows the error to the sign-in UI.

Sources: [`LoginPage.tsx`](../railora/src/pages/LoginPage.tsx), [`AuthContext.tsx`](../railora/src/features/auth/AuthContext.tsx), [`session.ts`](../railora/src/features/auth/session.ts), [`api.ts`](../railora/src/features/auth/api.ts), [`AuthController.java`](../src/main/java/in/yograj/learnspringboot/railway_booking/controller/AuthController.java), [`RefreshTokenService.java`](../src/main/java/in/yograj/learnspringboot/railway_booking/service/RefreshTokenService.java), [`JwtService.java`](../src/main/java/in/yograj/learnspringboot/railway_booking/security/JwtService.java).

### Browser reload and current-user restoration

At module initialization, `session.ts` reads and parses the stored `AuthResponse` from `localStorage`. On `AuthProvider` mount:

- No session means the provider immediately sets `unauthenticated`.
- A session means it requests `/api/users/me` with the saved access token.
- A successful request restores authenticated UI state.
- Any rejection—including a `401`, network error, timeout, or server error—clears both tokens and sets unauthenticated. No refresh attempt is made.

Storage access/parsing errors are caught in `session.ts` and treated as no stored session. `setSession` updates module memory before attempting persistence, so storage write failure leaves the current page authenticated in memory but loses that state on reload.

Sources: [`session.ts`](../railora/src/features/auth/session.ts), [`AuthContext.tsx`](../railora/src/features/auth/AuthContext.tsx).

### Bearer-token attachment and expired/invalid access tokens

The shared Axios request interceptor reads the active access token via `getAccessToken()` and attaches it as `Authorization: Bearer <token>` to requests. `getCurrentUser` also explicitly supplies the same bearer header. The response interceptor does not refresh, clear auth state, or retry; it only rejects the request error.

Backend `JwtAuthenticationFilter` parses and validates bearer JWTs, including signature/expiry via `JwtService`. Invalid tokens are caught in the filter and the security context is cleared; the request proceeds through authorization. A protected request without a valid authentication is answered by `JwtAuthenticationEntryPoint` with HTTP `401` and `{"error":"Unauthorized"}`. For ordinary protected API calls after the provider has already restored, the rejection does not update `AuthContext`; the Header can still display the authenticated user while API operations fail.

Default lifetimes are configured as `jwt.access-expiration-ms=900000` (15 minutes) and `jwt.refresh-expiration-ms=604800000` (7 days), both overrideable via environment variables. The response includes `expiresIn`, but the frontend does not schedule refresh from it.

Sources: [`api-client.ts`](../railora/src/lib/api-client.ts), [`session.ts`](../railora/src/features/auth/session.ts), [`JwtAuthenticationFilter.java`](../src/main/java/in/yograj/learnspringboot/railway_booking/security/JwtAuthenticationFilter.java), [`JwtAuthenticationEntryPoint.java`](../src/main/java/in/yograj/learnspringboot/railway_booking/security/JwtAuthenticationEntryPoint.java), [`JwtService.java`](../src/main/java/in/yograj/learnspringboot/railway_booking/security/JwtService.java), [`application.properties`](../src/main/resources/application.properties).

### Refresh-token issuance, persistence, and refresh endpoint

- Login calls `RefreshTokenService.createRefreshToken`. It creates a signed refresh JWT, stores the raw token string with its user, expiry timestamp and `revoked=false`, then returns that token to the frontend in the JSON auth response.
- `POST /api/auth/refresh` is public and accepts `{ "refreshToken": "..." }`. The refresh service looks up the exact token string in the database, rejects missing, revoked, or database-expired tokens, parses the signed JWT to obtain its subject, and returns the associated user.
- The controller issues a new access JWT and returns the same refresh-token string received in the request. It does not rotate or replace the persisted refresh record.
- The frontend has `authApi.refresh(refreshToken)`, which makes this body-based request, but no page, `AuthContext` method, or interceptor calls it. The refresh token is therefore stored but unused in the normal frontend lifecycle.
- Each login creates another refresh-token row. No login-time replacement or routine cleanup is present in the inspected service/repository.

Sources: [`AuthController.java`](../src/main/java/in/yograj/learnspringboot/railway_booking/controller/AuthController.java), [`RefreshTokenService.java`](../src/main/java/in/yograj/learnspringboot/railway_booking/service/RefreshTokenService.java), [`RefreshToken.java`](../src/main/java/in/yograj/learnspringboot/railway_booking/entity/RefreshToken.java), [`RefreshTokenRepository.java`](../src/main/java/in/yograj/learnspringboot/railway_booking/repository/RefreshTokenRepository.java), [`TokenRefreshRequest.java`](../src/main/java/in/yograj/learnspringboot/railway_booking/dto/TokenRefreshRequest.java), [`AuthResponse.java`](../src/main/java/in/yograj/learnspringboot/railway_booking/dto/AuthResponse.java), [`api.ts`](../railora/src/features/auth/api.ts).

### Logout and revocation

The Header's sign-out action calls `AuthContext.logout`. That clears the in-memory session and removes the `localStorage` item, then sets provider state to unauthenticated. It makes no HTTP request.

No logout/revoke route exists in `AuthController`. `RefreshTokenService.revokeAllForUser` deletes a user's refresh-token rows, but the inspected usage is only inside refresh validation when a row is already revoked or expired. No normal logout path calls it, and the inspected service never marks a token revoked. Since access JWTs are stateless, clearing the browser copy does not invalidate a previously copied access token; it can remain valid until its JWT expiry.

Sources: [`Header.tsx`](../railora/src/components/layout/Header.tsx), [`AuthContext.tsx`](../railora/src/features/auth/AuthContext.tsx), [`session.ts`](../railora/src/features/auth/session.ts), [`AuthController.java`](../src/main/java/in/yograj/learnspringboot/railway_booking/controller/AuthController.java), [`RefreshTokenService.java`](../src/main/java/in/yograj/learnspringboot/railway_booking/service/RefreshTokenService.java).

## Findings

Severity reflects impact and evidence in this implementation; it does not assert that an exploit has occurred.

| # | Severity | Finding and evidence | User/security impact | Smallest reasonable mitigation |
|---|---|---|---|---|
| 1 | **HIGH** | **Access and refresh JWTs are persisted in `localStorage`.** `setSession` serializes the complete `AuthResponse`; this includes the refresh token. Any script running in the Railora origin can read this storage. | A successful same-origin script injection could read and exfiltrate a refresh credential that remains valid for up to the configured refresh lifetime, as well as the current access credential. This is an exposure risk; this review did not establish an XSS vulnerability. | Immediate frontend-only reduction: keep credentials in memory and clear them on reload. If persistent sessions are required, coordinate backend refresh-cookie support using Secure/HttpOnly/SameSite controls, CSRF defenses, and strict origin/CORS policy. |
| 2 | **MEDIUM** | **Refresh is not wired into the session lifecycle, so ordinary sessions do not recover from access-token expiry.** Default access lifetime is 15 minutes; `expiresIn` is unused. `authApi.refresh` exists but has no caller. The Axios response interceptor only rejects errors. | Protected requests after expiry return `401`. The app can keep showing an authenticated Header while page actions fail. On reload, a `401` from `/users/me` clears the entire session even though the refresh token may still be valid. | Add one centralized, single-flight refresh path for eligible `401`s; update the stored session, retry the original request at most once, and clear auth state only when refresh is definitively rejected. Until then, explicitly handle `401` as a sign-in-required state. |
| 3 | **MEDIUM** | **Sign-out is local-only and does not revoke server credentials.** Frontend logout clears storage only; no backend logout endpoint is present. Refresh records remain valid until expiry. Access JWTs have no server-side revocation check. | A copied refresh token can continue to obtain access tokens after UI sign-out; a copied access token continues to work until expiry. Sign-out does not terminate other copies/sessions server-side. | Add a backend logout/revoke contract and call it before clearing local state. Revoke the current refresh token (or deliberately all user sessions) and document that already-issued access JWTs remain valid until expiry unless a revocation mechanism is added. |
| 4 | **MEDIUM** | **Session restoration conflates authentication rejection with service/network failure.** The initial `/users/me` rejection handler clears the full session for every error, without checking HTTP status or attempting refresh. Login similarly clears tokens if its post-login profile fetch fails. | A temporary outage, timeout, or transient server error forces sign-in and discards valid refresh credentials. A newly successful login can appear to fail after credentials were accepted. | Distinguish `401` from network/5xx failures. Attempt refresh for an expired access token; for transient errors retain credentials and represent auth verification as unavailable/retryable rather than unauthenticated. |
| 5 | **LOW** | **Refresh-token values are stored as raw strings in the database.** `RefreshToken.token` is a `String` column and the repository looks up the raw value. | A database read compromise exposes bearer refresh credentials directly while they remain valid. This is a consequence of bearer-token persistence, not evidence of a database breach. | Consider storing a one-way token hash/digest and looking up by digest. Treat as a production hardening item if database access controls and backups are in scope. |
| 6 | **LOW** | **Refresh-validation cleanup is attempted in a transaction that then throws.** `validateAndGetUser` calls `deleteByUser` for revoked/expired records and then throws `IllegalArgumentException`; the method is `@Transactional`. Spring's default rollback for unchecked exceptions means this cleanup is expected to roll back. | This can leave expired/revoked rows behind and makes the cleanup branch ineffective. It does not make a valid expired JWT usable; validation still rejects it. | Move cleanup into a separate transaction that commits, or remove the misleading cleanup and add an explicit expiry cleanup job. Confirm behavior with an isolated service test before changing it. |
| 7 | **LOW** | **Auth identifiers are emitted to application logs.** The JWT filter logs the username at DEBUG in multiple paths; registration writes the submitted username to standard output. The token value/password are not logged in these inspected statements. | Log readers may learn account identifiers; standard output may be retained by deployment logging. No production log access/retention configuration was inspected. | Remove or mask usernames from routine diagnostics and use the configured logger rather than `System.out`; keep any necessary identifying diagnostics access-controlled. |

## Confirmed defects vs. risks needing verification

### Confirmed from source

- Both access and refresh JWTs are currently persisted in `localStorage`.
- Frontend refresh method is not used by `AuthContext` or the Axios interceptors.
- No automatic refresh, refresh single-flight coordination, or one-time retry after `401` exists.
- Initial session restoration clears credentials for any `/users/me` error, not only invalid/expired credentials.
- Logout clears client state only; the backend exposes no logout endpoint.
- Refresh responses reuse the same refresh token rather than rotating it.
- Login creates a new persisted refresh record without invalidating prior login records.
- The refresh-service delete-on-error branch is called before throwing from a transactional method.

### Potential risks requiring deployment/runtime verification

- Whether production enforces HTTPS end-to-end is not established by this source review. The frontend uses relative `/api` paths and its Vite development proxy targets local HTTP; production is expected to provide a same-origin API rewrite.
- No explicit CORS configuration or `@CrossOrigin` declaration was found in the inspected backend source. Reverse-proxy/web-server CORS behavior was not inspected.
- Whether `localStorage` access is practically exposed depends on the application's XSS posture, CSP, third-party scripts, and origin controls; these were not audited.
- The exact HTTP status/body for malformed or expired JWT refresh requests depends on how the JWT parser exception is handled by Spring. The service source proves token validation, but no controller/service integration test for malformed or expired refresh credentials was found in the inspected tests.
- Revocation availability and retention of refresh-token rows beyond the service logic depend on the deployed database/cleanup configuration; no database was inspected.

## Cookie, CSRF, and CORS considerations

The current frontend sends bearer tokens explicitly in an `Authorization` header and the backend is stateless with CSRF disabled. Browsers do not automatically attach `localStorage` bearer tokens to cross-site requests, so the current design does not have the same ambient-credential CSRF behavior as cookie authentication. The principal storage concern is JavaScript access if same-origin script execution occurs.

Do not simply move the existing body-based refresh token into a cookie on the frontend: the backend currently reads `TokenRefreshRequest.refreshToken` from JSON and sets no auth cookies. A coordinated HttpOnly-cookie design would need to:

1. Have the backend issue, scope, expire, and clear the refresh cookie (Secure and HttpOnly, appropriate SameSite/domain/path).
2. Add CSRF protection appropriate to the chosen cookie policy (for example, a CSRF token/header and/or strict Origin validation for state-changing requests).
3. Configure a narrow trusted-origin CORS allowlist with credentials only if the frontend and API are cross-origin; do not combine credentialed requests with wildcard origins.
4. Define refresh rotation, reuse detection/revocation, and logout semantics together.

Because the current frontend and API use relative `/api` routes, same-origin deployment behind a reverse proxy is a compatible option and avoids unnecessary credentialed cross-origin CORS. The actual production topology must be confirmed before selecting cookie flags and origin policy.

## Practical recommendations

### Highest priority

Stop persisting bearer credentials in `localStorage` unless persistent sessions are a deliberate requirement. For the smallest change compatible with the current backend, keep both tokens in memory and document that a reload requires a new login. This immediately limits token-at-rest exposure to browser storage but does not make tokens inaccessible to active same-origin scripts.

### Next reliability and session-lifecycle improvements

1. Integrate refresh at one shared API boundary, with a single in-flight refresh promise, request replay at most once, and no interception recursion for login/refresh. Update `AuthContext` on terminal refresh failure.
2. Make session restoration distinguish `401` from transient network/server errors; do not erase still-valid credentials merely because `/users/me` is temporarily unavailable.
3. Add a backend logout endpoint that revokes the current refresh token (preferably with per-session semantics) and have the UI call it; clear local state regardless, but surface server revocation failure if logout assurance is required.
4. Add focused tests for: access-expired refresh success; invalid/expired refresh and terminal logout; concurrent `401`s making one refresh call; one retry maximum; transient `/users/me` failure preserving a retryable state; logout revocation; refresh-token persistence/expiry; and refresh rotation/reuse if rotation is introduced.
5. Before production, confirm HTTPS termination, deployment CORS/origin behavior, CSP/script inventory, and log retention/access.

### Safe to defer pending product/deployment requirements

- HttpOnly-cookie migration, until backend and deployment are designed together with CSRF/CORS controls.
- Refresh-token hashing, if database exposure/hardening priorities do not currently justify the schema/lookup change.
- Immediate access-token revocation, unless logout must invalidate already-issued access JWTs before their expiry; short access lifetime plus refresh revocation is a smaller compatible first step.
- Cross-device session management and UI to list/revoke sessions; the current implementation does not expose per-session controls.

## Test evidence inspected

No test commands were run for this documentation-only review.

Existing test sources inspected:

- Frontend [`AuthApi.test.ts`](../railora/src/test/AuthApi.test.ts): mocked login, registration, current-user bearer header, and refresh request-body contract.
- Frontend [`AuthExperience.test.tsx`](../railora/src/test/AuthExperience.test.tsx): form behavior, login/context state transition, and local logout behavior. The logout test does not establish backend revocation.
- Backend [`AuthTokenRoleTests.java`](../src/test/java/in/yograj/learnspringboot/railway_booking/security/AuthTokenRoleTests.java): login token claims/roles and controller refresh token role claims. It mocks `RefreshTokenService`; it does not verify refresh persistence, expiry, revocation, or logout.

No focused frontend test for automatic refresh, `401` state recovery, concurrent refresh coordination, or clearing tokens after a failed refresh was identified in the inspected test set. No focused backend refresh-service integration tests for persisted token expiry/revocation or logout were identified by source search.

## Files inspected

### Frontend

- `railora/src/features/auth/session.ts`
- `railora/src/features/auth/AuthContext.tsx`
- `railora/src/features/auth/api.ts`
- `railora/src/features/auth/types.ts`
- `railora/src/lib/api-client.ts`
- `railora/src/lib/constants.ts`
- `railora/src/pages/LoginPage.tsx`
- `railora/src/pages/RegisterPage.tsx`
- `railora/src/components/layout/Header.tsx`
- `railora/src/test/AuthApi.test.ts`
- `railora/src/test/AuthExperience.test.tsx`

### Backend

- `src/main/java/in/yograj/learnspringboot/railway_booking/controller/AuthController.java`
- `src/main/java/in/yograj/learnspringboot/railway_booking/controller/UserController.java`
- `src/main/java/in/yograj/learnspringboot/railway_booking/controller/GlobalExceptionHandler.java`
- `src/main/java/in/yograj/learnspringboot/railway_booking/config/SecurityConfig.java`
- `src/main/java/in/yograj/learnspringboot/railway_booking/security/JwtService.java`
- `src/main/java/in/yograj/learnspringboot/railway_booking/security/JwtAuthenticationFilter.java`
- `src/main/java/in/yograj/learnspringboot/railway_booking/security/JwtAuthenticationEntryPoint.java`
- `src/main/java/in/yograj/learnspringboot/railway_booking/security/CustomUserDetailsService.java`
- `src/main/java/in/yograj/learnspringboot/railway_booking/service/RefreshTokenService.java`
- `src/main/java/in/yograj/learnspringboot/railway_booking/service/UserServiceImpl.java`
- `src/main/java/in/yograj/learnspringboot/railway_booking/entity/RefreshToken.java`
- `src/main/java/in/yograj/learnspringboot/railway_booking/repository/RefreshTokenRepository.java`
- `src/main/java/in/yograj/learnspringboot/railway_booking/dto/AuthResponse.java`
- `src/main/java/in/yograj/learnspringboot/railway_booking/dto/TokenRefreshRequest.java`
- `src/main/resources/application.properties`
- `src/test/java/in/yograj/learnspringboot/railway_booking/security/AuthTokenRoleTests.java`

