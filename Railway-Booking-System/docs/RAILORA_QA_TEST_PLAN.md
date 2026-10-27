# Railora QA Test Plan

**Inspection basis:** current source under `railora\` and `src\` as inspected on 2026-10-26.  
**Execution status:** this plan was produced by read-only source inspection. No tests, services, containers, or database connections were started for this task. Existing coverage below is identified from test source; it is not a claim that those tests passed.

## Scope and risk

The covered user flow is: register/sign in → restore/check current user → search a train and dated journey → inspect seat inventory for a journey segment → hold a seat → create a pending booking → pay or retry → view booking history and complete pending payments.

Highest risks are:

1. **Overlapping seat allocation and database enforcement (P0):** application-level overlap checks, row locking, PostgreSQL exclusion constraints, and actual SQLSTATE `23P01` handling must agree under concurrent requests.
2. **Payment state transitions and retry safety (P0):** a failed payment must leave a valid pending booking/hold retryable; an expired hold must not confirm; duplicate payment requests must not double-charge or double-book.
3. **Ownership and authentication boundaries (P0):** unauthenticated access and cross-user access must be rejected on hold, booking, payment, profile, and history paths.
4. **Stale availability and booking submissions (P1):** availability is a snapshot; another request can hold the seat before this user acts. The server response must be authoritative and the UI must let the user recover.
5. **Registration duplicate behavior (P1):** duplicate username/email errors are raised as generic runtime exceptions in the service and have no explicit matching handler in the inspected global exception advice. The duplicate response status/body is **UNKNOWN** from explicit source contract.

## Verified API contracts

`200` below is the controller’s normal successful GET/POST response where no other status is explicitly set. Errors are included only where source handlers, security rules, or controller tests establish them. A status not declared or mapped in source is marked **UNKNOWN** rather than inferred.

| Flow | Method and endpoint | Request | Response | Authentication | Verified success / relevant error status |
|---|---|---|---|---|---|
| Registration | `POST /api/auth/register` | JSON `UserRequestDTO`: `username`, `fullName`, `email`, optional `phoneNumber`, `password` | `UserResponseDTO`: `userId`, `username`, `fullName`, `email`, `phoneNumber`, `status`, `createdAt` | Public | `201 Created`; bean validation errors map to `400`. Duplicate username/email is thrown as `RuntimeException` by `UserServiceImpl`; no matching `RuntimeException` handler was found: duplicate status/body **UNKNOWN**. |
| Legacy registration | `POST /api/users/register` | Same `UserRequestDTO` | `UserResponseDTO` | Public | Normal controller success status **UNKNOWN** (method returns a body without explicit status); validation `400`. |
| Login | `POST /api/auth/login` | JSON `LoginRequest`: `username` (backend accepts username or email), `password` | `AuthResponse`: `accessToken`, `refreshToken`, `tokenType`, `expiresIn`, `message` | Public | `200 OK`; invalid credentials `401`; validation `400`. |
| Refresh | `POST /api/auth/refresh` | JSON `TokenRefreshRequest`: `refreshToken` | `AuthResponse` | Public endpoint; refresh token is body credential | `200 OK`; blank token validation `400`. Invalid/expired/revoked token is thrown as `IllegalArgumentException`, which global advice maps to `400`. |
| Current user/session check | `GET /api/users/me` | No body; bearer access token in `Authorization` | `UserResponseDTO` | Authenticated | `200 OK`; unauthenticated `401`. There is no persisted frontend restoration mechanism in the inspected frontend; the provider checks its in-memory session and calls this endpoint. |
| Direct train search | `GET /api/trains/search?from={code}&to={code}` | Required query params `from`, `to` | JSON array of `TrainSearchResponseDTO` | Public | `200 OK`; missing required query parameters are not explicitly tested in inspected source: error status **UNKNOWN**. |
| Journey list | `GET /api/journeys?trainId={id}&date={yyyy-MM-dd}` | Optional `trainId`, optional ISO `date` | JSON array of `JourneyResponseDTO` | Public | `200 OK`; no matching journeys is an empty result. |
| Journey search | `GET /api/journeys/search?fromStationId={id}&toStationId={id}&journeyDate={yyyy-MM-dd}` | Required station IDs and ISO date | JSON array of `JourneyResponseDTO` | Public | `200 OK`; invalid/missing required query values are not explicitly covered by inspected controller tests: **UNKNOWN**. |
| Journey filter | `GET /api/journeys/filter` | Query-bound `SearchJourneyRequestDTO`: `fromStationId`, `toStationId`, `journeyDate` | JSON array of `JourneyResponseDTO` | Public | `200 OK`; request validation is declared, but endpoint-specific failure statuses are not verified by the inspected test set: **UNKNOWN**. |
| Journey stops | `GET /api/journeys/{id}/stops` | Path `id` | JSON array of `JourneyStopResponseDTO` | Public | `200 OK`; missing journey behavior comes from service lookup; exact endpoint test not found. |
| Seat inventory | `GET /api/journeys/{journeyId}/seats` | Optional query `boardingJourneyStopId`, `alightingJourneyStopId`; supply both or neither | JSON array of `SeatAvailabilityResponseDTO` | Public | `200 OK`; invalid partial/reversed segment maps to `400`; missing journey/stop can map to `404`. Full-run and segment statuses differ. |
| Available seats | `GET /api/journeys/{journeyId}/seats/available` | Same optional segment query | JSON array of `SeatAvailabilityResponseDTO`, filtered to status `AVAILABLE` | Public | `200 OK`; same segment/lookup errors as seat inventory. The frontend currently calls `/seats`, not this filtered endpoint. |
| Acquire seat hold | `POST /api/journeys/{journeyId}/seats/hold` | JSON `HoldSeatRequestDTO`: required `seatId`, `holdMinutes` with minimum 1, optional boarding/alighting journey-stop IDs | `SeatAvailabilityResponseDTO` | Authenticated; owner is derived from authenticated principal | `200 OK`; unauthenticated `401`; invalid hold/segment, blocked seat, or overlapping active hold `400`; missing journey/seat/stop `404`. |
| Create booking | `POST /api/bookings` | JSON `CreateBookingRequestDTO`: required `journeyId`, required `seatId`, optional boarding/alighting journey-stop IDs | `BookingResponseDTO`: booking reference, statuses, amount, timestamps, seat data | Authenticated | `201 Created`; unauthenticated `401`; missing fields/no active exact-segment hold/duplicate pending booking `400`; missing inventory `404`; someone else’s hold `403`. The service sets the initial booking amount to `null`; payment derives the mock amount server-side. |
| Pay booking | `POST /api/bookings/{bookingRef}/pay` | Optional JSON `PaymentRequestDTO`: `paymentMethod` (defaults to `MOCK_UPI`), `idempotencyKey`, `simulateFailure` (defaults false); body may be omitted | `PaymentResponseDTO`: transaction reference, booking reference/ID, amount, payment/booking statuses, method, message, time | Authenticated; owner or admin may pay | `200 OK` for both a successful and simulated failed payment (failure is represented in response body as `paymentStatus: FAILED`). Expired hold `400`; unauthorized owner `403`; missing booking `404`; repeated confirmed payment or idempotency-parameter mismatch `409`. |
| Booking detail | `GET /api/bookings/{bookingRef}` | Path `bookingRef` | `BookingDetailResponseDTO` | Authenticated; owner or admin | `200 OK`; unauthenticated `401`; not found `404`; cross-user `403`. |
| My Bookings/history | `GET /api/bookings?page={page}&size={size}` | Optional pagination; defaults `page=0`, `size=10` | `PageResponseDTO<BookingDetailResponseDTO>` with `content`, page/size/total and navigation flags | Authenticated; regular users see own bookings, admins see all | `200 OK`; unauthenticated `401`; page < 0, size < 1, or size > 100 `400`. Frontend requests page 0, size 20. |

### Contract details relevant to test assertions

- Status mapping verified in `GlobalExceptionHandler`: `BadRequestException` / `IllegalArgumentException` → `400`; `ResourceNotFoundException` → `404`; `IllegalStateException` / `DataIntegrityViolationException` → `409`; `AccessDeniedException` → `403`; `BadCredentialsException` → `401`; bean validation → `400`.
- Authentication rules verified in `SecurityConfig`: train search and journey GETs are public; auth routes and legacy user registration are public; `/api/users/**` (except explicit public/admin rules) is authenticated; hold is authenticated; booking endpoints require authentication; admin inventory seed/release is admin-only.
- Segment allocation uses half-open stop intervals `[boarding, alighting)`: adjacent segments should be compatible; overlapping segments on the same physical seat should conflict.
- The seat-hold endpoint and booking creation use the exact journey and segment. Booking creation requires an active hold owned by the current user for that exact segment.
- A successful payment transitions the booking to `CONFIRMED`, payment to `SUCCESS`, and allocation to `BOOKED`. A simulated failed payment returns an ordinary successful HTTP response with `FAILED`, leaves booking `PENDING_PAYMENT`, and leaves allocation `HOLD`.
- A new booking response can contain `amount: null`; payment currently uses server-side default mock fare `500.00` if booking amount is null. Test both the API shape and frontend display against that behavior; do not treat the UI’s displayed estimate as authoritative.
- Payment idempotency is scoped to booking reference and key. Reusing the same key with matching payment method and failure flag returns the prior transaction; different parameters produce `409`. A confirmed booking without a matching idempotency replay rejects another payment with `409`.
- Frontend direct-train search sends `from` and `to`; the selected date is not sent to `/api/trains/search`. Date-specific runs are then obtained with `/api/journeys?trainId=...&date=...`, and the UI matches journey stops by station code/direction.
- Registration duplicate errors are not currently a reliable explicit API contract: the service throws generic runtime exceptions for duplicate username/email. Verify and resolve expected HTTP status/body with backend owners before asserting a specific duplicate status.

## Risk-based test matrix

Priority definitions: **P0** release-blocking integrity/security; **P1** high-value flow/recovery; **P2** lower-risk functional/accessibility regression. “Existing coverage” is from test-source inspection only.

| ID / priority | Scenario and preconditions / test data | Steps | Expected result | Existing automated coverage (source inspected) | Test level |
|---|---|---|---|---|---|
| AUTH-01 / P1 | Valid registration; unique username/email; all required fields populated | Submit `POST /api/auth/register` through the UI and verify the request/response | `201`; user DTO fields match; password is not returned; UI proceeds to sign-in with success message | Frontend `AuthExperience.test.tsx` mocks success/payload and navigation; backend registration endpoints exercised in `UserSecurityAuthorizationTests` (including public registration), but no full FE↔BE test identified | Mocked frontend + H2/backend integration |
| AUTH-02 / P1 | Blank username/full name/email/password; malformed email | Submit invalid registration values; also send invalid payload directly to API | Client blocks invalid values; backend validation returns `400` with field errors | Frontend required/email validation in `AuthExperience.test.tsx`; backend DTO validation exists, but registration validation assertions not identified in inspected test sources | Mocked frontend + H2/controller |
| AUTH-03 / P1 | Existing username, existing email, and both duplicate | Register once, then repeat separately with duplicate username/email | Duplicate response must be safe and actionable; status/body are **UNKNOWN** until duplicate exception is explicitly mapped; no internal exception or database detail displayed | Frontend API duplicate mapping test uses mocked `500` / message; backend service duplicate exceptions are present, but no exact duplicate endpoint response contract/test identified | H2/backend integration + actual API integration |
| AUTH-04 / P0 | Correct username and correct email sign-in; invalid password; unknown identity | Login with username, then email; try invalid credentials and blank fields | Successful login returns all `AuthResponse` fields with `200`; invalid credentials `401`; malformed request `400` | Backend `AuthTokenRoleTests`, `UserSecurityAuthorizationTests`; frontend `AuthApi.test.ts` tests mocked login success/401; `AuthExperience.test.tsx` tests login navigation | Mocked frontend + H2/controller |
| AUTH-05 / P0 | Valid/expired/revoked/blank refresh tokens; no browser persistence | Call refresh; restart/reload frontend after sign-in; call `/users/me` without/with valid bearer token | Valid refresh returns `AuthResponse`; blank is `400`; invalid/revoked refresh maps to `400`; `/users/me` is `200` when authenticated and `401` otherwise. Browser reload currently loses in-memory tokens; do not expect automatic restoration | Auth token service tests cover refresh role claims; frontend tests cover in-memory loading and mocked current-user calls. Full browser reload / refresh endpoint integration not identified | Mocked frontend + H2/controller + API integration |
| SEARCH-01 / P1 | Known distinct station codes and a route with results | Submit train-code search and inspect request/query and result list | UI trims/uppercases codes; backend returns `TrainSearchResponseDTO[]`; date is not part of this endpoint request | `TrainSearch.test.tsx` covers form validation and a mocked API-backed page; no direct backend train-search controller test identified | Mocked frontend + API integration |
| SEARCH-02 / P1 | Empty, short, >5-char, non-letter, same source/destination codes | Try invalid inputs and direct API query requests | UI rejects invalid/equal codes and avoids request; API’s exact invalid/missing-parameter status is **UNKNOWN** from inspected tests | Frontend `TrainSearch.test.tsx` tests empty/length/letters/equal; no direct controller validation coverage identified | Unit + mocked frontend + API integration |
| SEARCH-03 / P1 | Train with no run on selected date; multi-stop train with both direction orders | Open seat selection from a result; query journeys by train/date; evaluate stops | Exact dated directional journey is selected; no run or no directional match shows recoverable message; no stale journey from another date is used | `BookingApi.test.ts` mocks journey/stops API; `SeatSelectionModal.test.tsx` covers selecting the direction-matched journey | Mocked frontend + actual API integration |
| SEARCH-04 / P2 | Missing/invalid date; no journeys; duplicate or malformed journey filter inputs | Call journey list/search/filter with valid and invalid query combinations | Valid queries return `JourneyResponseDTO[]`; invalid/missing query status is asserted only when verified for that route; empty result is empty array | Booking API has mocked list call coverage; journey-create validation tests are not equivalent to read/search coverage | H2/controller + API integration |
| SEAT-01 / P1 | Existing journey with available, held, booked, blocked seats | Fetch `/seats` and `/seats/available` with no segment and with a valid segment | DTO fields and availability match the requested scope; available endpoint includes only `AVAILABLE`; full-run status may be `SEGMENTED` when partial allocations exist | `BookingApi.test.ts` verifies mocked segment query; `SeatSelectionModal.test.tsx` renders available/booked seats and checks the booked seat is disabled; backend `RailwaySegmentAllocationTests` covers one H2 search/segment/allocation scenario; no seat-controller tests identified | Mocked frontend + H2/controller |
| SEAT-02 / P1 | Partial stop pair, stop from another journey, reversed/equal stops, journey with fewer than two stops | Query inventory and attempt hold with invalid segment combinations | Partial/reversed segment returns `400`; missing journey/stop returns `404`; no allocation is created | `SeatInventoryService` implements these checks; direct endpoint test coverage not identified | H2/service + API integration |
| SEAT-03 / P0 | Two users, same seat and overlapping segment; also adjacent non-overlapping segments | Race two holds for `[1,3)` and `[2,4)` on same inventory; then try `[3,5)` adjacent to `[1,3)` | At most one overlapping hold succeeds; adjacent segment may succeed; winning hold owner/status/expiry are consistent | `RailwaySegmentAllocationTests` covers a concurrent H2 scenario; `PostgresSegmentAllocationConcurrencyIT` defines adjacent/overlap/concurrent cases, but it has an H2 fallback. PostgreSQL behavior remains **PENDING** | H2 integration now; real PostgreSQL required for DB guarantee |
| SEAT-04 / P1 | Hold duration null, zero, negative, one minute, expiry boundary; already held/booked/blocked seat | Hold with invalid and boundary values; hold unavailable seat; retry after expiry | Invalid duration and blocking allocation return `400`; missing seat/journey `404`; expired hold can be reclaimed; exactly-at-expiry must not be treated as active | Seat allocation/reaper service tests cover expiry/reclamation generally; no frontend hold-component test identified | H2/service + actual API integration |
| SEAT-05 / P0 | Unauthenticated request; user A attempts booking/hold access to user B’s allocation; admin-only release/seed | Call protected endpoints with absent, regular-user, and admin credentials | Unauthenticated `401`; cross-owner action `403`; regular user cannot invoke admin-only routes; no unauthorized mutation | Security tests cover general protected endpoints; lifecycle tests cover payment owner; hold endpoint ownership and admin inventory paths need endpoint-specific confirmation | H2/controller + actual API integration |
| BOOK-01 / P0 | Authenticated owner has active exact-segment hold | Submit valid create-booking payload once | `201`; `PENDING_PAYMENT` / `PENDING`; same journey, seat, owner and segment; amount may be null before payment | `BookingApi.test.ts` tests mocked request/response; `BookingPaymentLifecycleTests` creates bookings as payment fixtures. Dedicated create-booking controller test was not identified | Mocked frontend + H2/controller/service |
| BOOK-02 / P0 | Missing journey/seat IDs; no hold; expired hold; hold for different segment; hold owned by another user | Submit invalid or stale create-booking requests | Missing fields/no active exact hold → `400`; inventory missing → `404`; another owner’s hold → `403`; no booking is created | Service branches are present; frontend API test only covers successful mocked creation; endpoint-level negative test coverage not identified | H2/service + actual API integration |
| BOOK-03 / P1 | Double-click/retry of create-booking; pending booking already exists for allocation | Submit same create request twice, including rapid parallel requests | No duplicate active pending booking; first success is retained; repeat should have documented conflict response (service checks and returns `400`); UI prevents duplicate submit or reports recoverable conflict | `SeatSelectionModal.test.tsx` covers one successful create, not duplicate/parallel submission; backend lifecycle has payment/concurrency tests, but no explicit create duplicate assertion found | H2/integration |
| PAY-01 / P0 | Pending booking with active hold | Pay with default request and with explicit `MOCK_UPI`, `simulateFailure=false` | `200`, `SUCCESS`, `CONFIRMED`, amount derived server-side, allocation `BOOKED`, one transaction | `BookingPaymentLifecycleTests.successfulPayment...`; `BookingPaymentControllerTests` mocked controller response; `SeatSelectionModal.test.tsx` covers the mocked successful full flow; frontend booking API mock covers request format | H2/service/controller + actual API integration |
| PAY-02 / P0 | Pending booking; active hold; failure simulated | Pay with `simulateFailure=true`, including omitted optional fields | HTTP remains `200`; response `FAILED` / `PENDING_PAYMENT`; payment status `FAILED`; allocation stays `HOLD`; retry remains possible before expiry | Lifecycle test `failedPayment_recordsFailedTransactionAndKeepsBookingPending`; `SeatSelectionModal.test.tsx` covers mocked simulated failure and retry; no real frontend-to-backend test | H2/service + mocked UI/API integration |
| PAY-03 / P0 | Failed payment followed by successful retry; same idempotency key with matching and mismatching payload | Retry failed payment with new key; replay same key; alter method/failure flag with same key | New-key retry may confirm before hold expiry; matching same-key replay returns same transaction; mismatched parameters `409`; no duplicate transaction/charge | Lifecycle tests cover retry, same-key replay, mismatch, and concurrent attempts; controller test covers mismatch 409; UI generates a new timestamped key per modal attempt and no idempotency key from BookingsPage | H2/service/controller + actual API integration |
| PAY-04 / P0 | Expired hold, confirmed booking, cancelled/expired booking, missing booking, cross-user booking | Pay stale/invalid booking references and repeat confirmed payment | Expired hold returns `400` and expires booking/allocation; confirmed repeat without replay key `409`; missing ref `404`; cross-user `403`; unauthenticated `401` | Lifecycle and controller tests cover expired, confirmed duplicate, missing, owner, and unauthorized scenarios; frontend handling not exhaustively tested | H2/service/controller + actual API integration |
| PAY-05 / P1 | Concurrent payment attempts, release racing payment, duplicate idempotency key | Start synchronized payment/release requests against the same booking/seat | One consistent terminal state, no duplicate successful transaction or double booking; loser receives idempotent result or conflict as defined by same-key/same-booking state | `BookingPaymentLifecycleTests` includes concurrent payment and release races; PostgreSQL locking semantics not equivalent to H2 | H2 concurrency + isolated PostgreSQL |
| HIST-01 / P1 | Authenticated user with no bookings; own confirmed/pending bookings; another user’s records | Request first page, next page, and history as separate users/admin | Regular user only sees own records; default page/size are 0/10; response page metadata/order is stable; admin behavior matches service contract | `BookingQueryLifecycleTests` and `BookingQueryControllerTests` cover ownership, admin, pagination, empty page; frontend `BookingsExperience.test.tsx` covers unauthenticated, empty, rendered cards | Mocked frontend + H2/controller/service |
| HIST-02 / P1 | Page < 0, size 0, size > 100; stale pending record after hold expiry | Call invalid pages; load pending booking, let hold expire, then pay from stale card | Invalid pagination returns `400`; stale pay is rejected/marked expired; UI refresh/error state explains failure and does not claim payment success | Backend query tests cover invalid pagination; lifecycle covers expired payment. Frontend test does not exercise pending-payment refresh/rejection branch | H2/controller + actual API integration |
| HIST-03 / P1 | Pending booking card, payment succeeds/fails/network error; repeated click | Pay from My Bookings, refresh history, double-submit, and simulate request error | Pay action only applies to pending state; loading prevents duplicate actions; successful history refresh displays current status; failure is surfaced and stale card is not presented as paid | `BookingsExperience.test.tsx` checks presence/pay call but does not await/assert reload, failure, loading, or duplicate action behavior | Mocked frontend + actual API integration |

## Verification levels and what is currently possible

### Level 1 — frontend tests with mocked dependencies (can run without backend/database)

The Railora frontend test suite uses Vitest/jsdom and mocks API calls for auth, booking, and search paths. Source-inspected suites:

- `railora/src/test/AuthApi.test.ts`
- `railora/src/test/AuthExperience.test.tsx`
- `railora/src/test/BookingApi.test.ts`
- `railora/src/test/BookingsExperience.test.tsx`
- `railora/src/test/SeatSelectionModal.test.tsx`
- `railora/src/test/TrainSearch.test.tsx`
- `railora/src/test/Header.test.tsx`
- `railora/src/test/DesignSystem.test.tsx`

This level verifies browser-side input, rendering, mocked request payloads, and mocked response handling only. It does **not** verify real HTTP serialization, Spring security, persistence, transaction behavior, or database constraints.

### Level 2 — backend H2/service/controller tests (can run without PostgreSQL)

Test source config uses H2 in-memory databases (`src/test/resources/application.properties`, plus per-test H2 properties). Existing suites include payment lifecycle, booking query lifecycle, payment/query controllers, security, journey validation, seat reaper, and segment allocation. Controller tests often use `@MockitoBean` for the service, so they establish routing/status/JSON wiring but not necessarily service persistence behavior. Service lifecycle tests exercise persistence against H2.

H2 PostgreSQL-compatibility mode is not PostgreSQL: do not count H2 concurrency outcomes as proof of PostgreSQL row-locking, GiST, exclusion-constraint, or SQLSTATE behavior.

### Level 3 — frontend-to-backend API integration (not established by inspected automated tests)

Requires the frontend and Spring Boot backend configured to use a **disposable isolated test database**, test users/data, and mocked/stubbed external integrations where needed. Exercise real bearer-token propagation, request/response DTOs, CORS/proxy configuration, actual error shapes, date-to-journey matching, seat availability races, booking creation, payment transitions, and history refresh. Do not point this level at the existing `railway_booking` development database.

No browser automation / end-to-end harness for real frontend-to-backend requests was identified in the inspected frontend source.

### Level 4 — real PostgreSQL (PENDING)

`PostgresSegmentAllocationConcurrencyIT` is explicitly designed to accept `RAILORA_TEST_PG_URL`; otherwise it falls back to an H2 in-memory database. Its PostgreSQL GiST bypass test returns early when the active database is not PostgreSQL. The inspected direct-constraint assertion expects a `DataIntegrityViolationException` and names SQLSTATE `23P01` in its assertion message, but does not itself extract and assert the SQLSTATE value.

Therefore **overlapping-seat allocation against PostgreSQL and exact SQLSTATE `23P01` verification remain PENDING**. They have not been run or established by this inspection. Execute only against an isolated disposable PostgreSQL test database after verifying the target. Never use, migrate, reset, or write to `railway_booking`.

### Level 5 — future distributed verification (deferred)

Redis, Kafka, containers, multi-instance/distributed deployment, cross-node hold expiration, message delivery/replay, and distributed locking are outside current scope. No tests for these deployment-level guarantees should be claimed until those components are intentionally introduced and configured.

## Coverage gaps and risks to close

- Add contract tests for registration success/validation/duplicate responses and settle the duplicate error status/body in backend source before making it a stable expectation.
- `SeatSelectionModal.test.tsx` covers the mocked full happy lifecycle, direction matching/available-seat display, and simulated payment failure/retry. Add cases for unauthenticated behavior, no dated journey/empty seats, hold/create failure, timer expiry, duplicate submission, stale availability, and modal/seat-grid keyboard accessibility.
- Add payment UI tests for hold expiry, network failure, repeated clicks, and post-success state. The current My Bookings test checks the payment call but not the refreshed state.
- Add explicit backend tests for hold endpoint validation/ownership and booking creation negative cases, including no hold, wrong segment/owner, expired hold, and duplicate creation.
- Add real controller tests for train/journey search required parameters, empty results, date boundaries, and segment-specific seat availability.
- Assert the current-user endpoint contract with real bearer-token authentication and verify that reload intentionally starts unauthenticated under memory-only storage.
- Verify error response shape at the actual API boundary. Source-level `UNKNOWN` statuses must not be turned into assumed status codes in tests.
- PostgreSQL direct constraint verification should assert the actual SQLSTATE `23P01`, not merely a wrapped data-integrity exception, and should also verify the user-facing mapped `409` response where exercised through HTTP.
- Check stale UI state after hold expiry/reaper cleanup: inventory refresh, pending booking history, and pay action must remain consistent.

## Recommended execution order

1. **Confirm contracts and fixtures:** settle duplicate-registration behavior and seed a deterministic multi-stop route, seats, users/roles, and dates. Keep payment and booking fixtures isolated per test.
2. **Run mocked frontend tests:** validate accessibility, keyboard paths, request payloads, loading/duplicate-submit handling, API error rendering, navigation, payment retry UI, and history refresh. Keep mocked tests deterministic and independent of a running backend.
3. **Run H2 backend tests:** authentication/authorization, DTO validation, journey lookup, inventory/hold expiry, booking ownership, payment failure/retry/idempotency, and history pagination. Check test database URLs before any invocation.
4. **Run real HTTP integration against an isolated test database:** verify end-to-end authentication headers, request/response contracts, server error mappings, stale seat contention, payment state changes, and My Bookings refresh. Never use the existing development database.
5. **Run isolated PostgreSQL tests:** explicitly verify adjacent versus overlapping segment allocation, synchronized concurrent overlap attempts, persisted allocation state, SQLSTATE `23P01`, and HTTP conflict mapping. Report this gate as PENDING until executed successfully against PostgreSQL.
6. **After infrastructure is intentionally added:** define separate Redis/Kafka/multi-instance scenarios; do not treat those as current release gates.

## Source files inspected

### Controllers, configuration, DTOs, and services

- `src/main/java/in/yograj/learnspringboot/railway_booking/controller/AuthController.java`
- `src/main/java/in/yograj/learnspringboot/railway_booking/controller/UserController.java`
- `src/main/java/in/yograj/learnspringboot/railway_booking/controller/TrainSearchController.java`
- `src/main/java/in/yograj/learnspringboot/railway_booking/controller/JourneyController.java`
- `src/main/java/in/yograj/learnspringboot/railway_booking/controller/SeatInventoryController.java`
- `src/main/java/in/yograj/learnspringboot/railway_booking/controller/BookingController.java`
- `src/main/java/in/yograj/learnspringboot/railway_booking/controller/GlobalExceptionHandler.java`
- `src/main/java/in/yograj/learnspringboot/railway_booking/config/SecurityConfig.java`
- `src/main/java/in/yograj/learnspringboot/railway_booking/service/BookingService.java`
- `src/main/java/in/yograj/learnspringboot/railway_booking/service/SeatInventoryService.java`
- `src/main/java/in/yograj/learnspringboot/railway_booking/service/RefreshTokenService.java`
- Relevant DTOs: `UserRequestDTO`, `UserResponseDTO`, `LoginRequest`, `AuthResponse`, `TokenRefreshRequest`, `TrainSearchResponseDTO`, `JourneyResponseDTO`, `SearchJourneyRequestDTO`, `JourneyStopResponseDTO`, `HoldSeatRequestDTO`, `SeatAvailabilityResponseDTO`, `CreateBookingRequestDTO`, `BookingResponseDTO`, `BookingDetailResponseDTO`, `PaymentRequestDTO`, `PaymentResponseDTO`, and `PageResponseDTO` under `src/main/java/in/yograj/learnspringboot/railway_booking/dto/`.

### Frontend integration, pages, and tests

- `railora/src/features/auth/api.ts`
- `railora/src/features/auth/AuthContext.tsx`
- `railora/src/features/booking/api.ts`
- `railora/src/features/booking/types.ts`
- `railora/src/features/booking/components/SeatSelectionModal.tsx`
- `railora/src/features/search/api.ts`
- `railora/src/features/search/hooks/useTrainSearch.ts`
- `railora/src/pages/TrainSearchPage.tsx`
- `railora/src/pages/BookingsPage.tsx`
- `railora/src/app/router.tsx`
- Frontend tests listed in “Level 1”.

### Test configuration and backend coverage

- `src/test/resources/application.properties`
- `pom.xml` (H2 runtime/test setup and test dependencies)
- `src/test/java/in/yograj/learnspringboot/railway_booking/security/AuthTokenRoleTests.java`
- `src/test/java/in/yograj/learnspringboot/railway_booking/security/UserSecurityAuthorizationTests.java`
- `src/test/java/in/yograj/learnspringboot/railway_booking/controller/JourneyControllerValidationTests.java`
- `src/test/java/in/yograj/learnspringboot/railway_booking/controller/BookingPaymentControllerTests.java`
- `src/test/java/in/yograj/learnspringboot/railway_booking/controller/BookingQueryControllerTests.java`
- `src/test/java/in/yograj/learnspringboot/railway_booking/BookingPaymentLifecycleTests.java`
- `src/test/java/in/yograj/learnspringboot/railway_booking/BookingQueryLifecycleTests.java`
- `src/test/java/in/yograj/learnspringboot/railway_booking/RailwaySegmentAllocationTests.java`
- `src/test/java/in/yograj/learnspringboot/railway_booking/service/SeatHoldReaperTests.java`
- `src/test/java/in/yograj/learnspringboot/railway_booking/inventory/PostgresSegmentAllocationConcurrencyIT.java`
- `src/test/java/in/yograj/learnspringboot/railway_booking/validation/JourneyRequestValidationTests.java`

## Execution record for this document

No test commands were run for this documentation-only task. The automated test references in this plan are based on source inspection and must not be interpreted as passing test results.
