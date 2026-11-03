# Railora Read-Only Performance Baseline

> [!IMPORTANT]
> **Scope & Classification Notice**
> This test suite is a **10-virtual-user, 60-second read-only API baseline test**, **not** a production-capacity or stress benchmark. It measures local response latency, assertion integrity, and throughput for read-only query endpoints on a developer environment. It does not measure write concurrency, database row-locking contention, payment gateway latency, or multi-node production limits.

---

## 1. Test Architecture & Endpoints

The test executes sequential read queries simulating an end-user searching for trains, selecting a journey, and checking seat availability:

| Step | Endpoint | Method | Query / Path Parameters | Assertions Checked |
|---|---|---|---|---|
| `01_GET_Train_Search` | `/api/trains/search` | `GET` | `from=NDLS&to=BCT` | HTTP `200 OK`, response body contains train `12952` |
| `02_GET_Journeys_By_Date` | `/api/journeys` | `GET` | `trainId=1&date=2026-10-27` | HTTP `200 OK`, response body contains `journeyId: 3` and date `2026-10-27` |
| `03_GET_Seat_Availability` | `/api/journeys/3/seats` | `GET` | `boardingJourneyStopId=5&alightingJourneyStopId=6` | HTTP `200 OK`, response body contains `seatId` and `available` |

### Concurrency Profile
- **Virtual Users (Threads):** 10 concurrent threads (`-Jthreads=10`, default 10)
- **Ramp-up:** 10 seconds (`-Jrampup=10`)
- **Duration:** 60 seconds steady-state (`-Jduration=60`)
- **Think Time:** 200 ms constant delay + 0–200 ms uniform random pacing (200–400 ms per cycle)
- **HTTP Client:** Keep-Alive enabled, 5 000 ms connect timeout, 10 000 ms response timeout

---

## 2. Measured Results

### Phase B Validation Run (10 Virtual Users, 60 Seconds)
- **Result file:** `results/phase-b-validation-10-users.jtl`
- **Dashboard:** `results/validation-html-report/index.html`
- **Total Requests:** 1 785
- **Failed Requests:** 0 (**0.00% error rate**)
- **Aggregate Throughput:** **29.96 req/s** (~10.0 req/s per endpoint)

#### Detailed Endpoint Latency Breakdown

| Sampler | Samples | Errors | Error % | Throughput | Avg Latency | Median (p50) | 90th % | 95th % | 99th % | Min | Max |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `01_GET_Train_Search` | 599 | 0 | 0.00% | 10.05 req/s | 8.12 ms | 8.0 ms | 9.0 ms | 10.0 ms | 13.0 ms | 4.0 ms | 43.0 ms |
| `02_GET_Journeys_By_Date` | 593 | 0 | 0.00% | 10.10 req/s | 5.41 ms | 5.0 ms | 6.0 ms | 7.0 ms | 11.0 ms | 3.0 ms | 32.0 ms |
| `03_GET_Seat_Availability` | 593 | 0 | 0.00% | 10.08 req/s | 9.05 ms | 9.0 ms | 10.0 ms | 11.0 ms | 14.06 ms | 6.0 ms | 25.0 ms |
| **Total / Aggregate** | **1 785** | **0** | **0.00%** | **29.96 req/s** | **7.53 ms** | **8.0 ms** | **10.0 ms** | **10.0 ms** | **13.0 ms** | **3.0 ms** | **43.0 ms** |

---

## 3. How to Reproduce

### Prerequisites
1. **Java Runtime:** Java 21 (`java -version`)
2. **Apache JMeter:** 5.6.3 (`jmeter.bat -v`)
3. **Railora Backend:** Spring Boot running on `http://localhost:8080` connected to PostgreSQL

### Command Line Execution (Non-GUI Mode)
Run from the repository root or `performance` directory:

```powershell
& "<path-to-jmeter>\bin\jmeter.bat" `
  -n `
  -t "performance/HTTP Request Defaults.jmx" `
  -l "performance/results/repro-run.jtl" `
  -e -o "performance/results/repro-html-report" `
  -j "performance/results/repro-jmeter.log" `
  -Jthreads=10 -Jrampup=10 -Jduration=60 -Jhost=localhost -Jport=8080
```

---

## 4. Test Artifacts & Historical Record

All historical test runs are archived and preserved in `performance/results/`:

| Artifact | Samples | Errors | Purpose / Historical Context |
|---|---|---|---|
| `phase-a-baseline.jtl` | 896 | 0 (0.00%) | Phase A baseline run (5 VU, 60s, 15.07 req/s). |
| `phase-b-10-users.jtl` | 1 702 | 569 (33.43%) | Historical diagnostic run with date `2026-10-28`. Failed assertion because Train 1 does not run on `2026-10-28` (returns `[]`). |
| `phase-b-10-users-corrected.jtl` | 1 801 | 0 (0.00%) | Historical rerun with corrected date `2026-10-27`. |
| `phase-b-validation-10-users.jtl` | 1 785 | 0 (**0.00%**) | **Current verified clean validation run** using parameterized JMX with no listener file locks. |
| `validation-html-report/` | — | — | Full Apache JMeter HTML dashboard with graphs, percentiles, and request breakdowns. |

---

## 5. Technical Limitations & Boundaries

1. **Read-Only Scope:** No mutating endpoints (`POST /api/bookings/hold`, `/api/bookings/confirm`, `/api/bookings/{id}/cancel`) are included in this plan. It does not measure write lock contention, pessimistic locks on seat inventories, or database transaction commit latencies.
2. **Local Loopback Network:** All traffic runs over `localhost` (`::1`), avoiding real-world network packet loss, internet latency (typically 20–150 ms), TLS handshake overhead, and reverse-proxy/load-balancer layers.
3. **Database Dataset Size:** The test executes against seed/demo data. Production-scale volumes with millions of journey stops, historical bookings, and concurrent seat holds will require indexing validation and query optimization under scale.
4. **Hardware Environment:** Results reflect a single developer machine running Spring Boot, PostgreSQL, and JMeter simultaneously. Real-world capacity benchmarks require dedicated load generators isolated from target servers.

---

## 6. Write-Lifecycle Smoke Test (`smoke-write-lifecycle.jmx`)

> [!WARNING]
> **Execution Safety & Database Preconditions**
> - **Dedicated Test Database Required:** Do **not** execute this write test against production or a dirty development database. The test executes mutating transactions that persist rows in `seat_allocations`, `bookings`, `payment_transactions`, and `outbox_events`.
> - **Pre-Created Test Account:** The test requires an existing active user account. Credentials must be passed at runtime via `-Jusername` and `-Jpassword`. Real credentials must never be committed.
> - **Pre-Seeded Journey & Available Seat:** The target `journeyId` and `seatId` must exist and be free of overlapping holds or bookings before starting.
> - **Decoupled Outbox Execution:** Outbox Kafka publishing defaults to disabled (`railway.outbox.kafka.enabled=false`), allowing the full transactional write path (`outbox_events` staging) to be tested without requiring a running Kafka broker.

### Test Architecture & Sequenced Flow

The smoke test executes a single-user, single-iteration lifecycle verifying the complete write chain:

| Step | Sampler | Method | Endpoint | Authentication | Key Assertions & Data Extractions |
|---|---|---|---|---|---|
| 1 | `01_POST_Auth_Login` | `POST` | `/api/auth/login` | None (`permitAll`) | HTTP `200 OK`, extracts `access_token` from `$.accessToken` |
| 2 | `02_POST_Seat_Hold` | `POST` | `/api/journeys/{journeyId}/seats/hold` | `Bearer ${access_token}` | HTTP `200 OK`, verifies status `HOLD` and `holdExpiresAt` |
| 3 | `03_POST_Booking_Create` | `POST` | `/api/bookings` | `Bearer ${access_token}` | HTTP `201 Created`, extracts `bookingRef` and `pnr`, verifies `PENDING_PAYMENT` |
| 4 | `04_POST_Booking_Pay` | `POST` | `/api/bookings/{bookingRef}/pay` | `Bearer ${access_token}` | HTTP `200 OK`, verifies `CONFIRMED` and `SUCCESS`, uses `SMOKE-PAY-${__UUID()}` idempotency key |
| 5 | `05_POST_Booking_Cancel` | `POST` | `/api/bookings/{bookingRef}/cancel` | `Bearer ${access_token}` | HTTP `200 OK`, verifies `CANCELLED` and `REFUNDED`, releases seat allocation |

### Parameter Reference

All runtime parameters are fully configurable via `-J` JMeter properties. Mandatory parameters default to non-existent placeholders (`REQUIRED_*_NOT_SET`) to prevent accidental execution against active environments without explicit parameters:

| Parameter | Default | Required? | Description |
|---|---|---|---|
| `host` | `localhost` | No | Target application host |
| `port` | `8080` | No | Target application port |
| `username` | `REQUIRED_USERNAME_NOT_SET` | **Yes** | Username of the test account |
| `password` | `REQUIRED_PASSWORD_NOT_SET` | **Yes** | Password of the test account |
| `journeyId` | `REQUIRED_JOURNEY_ID_NOT_SET` | **Yes** | Target journey ID with available inventory |
| `seatId` | `REQUIRED_SEAT_ID_NOT_SET` | **Yes** | Target seat ID on the journey |
| `boardingStopId` | `null` | Optional | Boarding stop ID (defaults to full route origin) |
| `alightingStopId` | `null` | Optional | Alighting stop ID (defaults to full route destination) |
| `holdMinutes` | `10` | No | Hold duration for the seat reservation |
| `bookingMode` | `GENERAL` | No | Booking mode (`GENERAL` or `TATKAL`) |
| `paymentMethod` | `MOCK_UPI` | No | Payment gateway method |
| `threads` | `1` | No | Virtual users (must remain 1 for smoke test) |
| `rampup` | `1` | No | Ramp-up period in seconds |
| `loops` | `1` | No | Number of lifecycle iterations per thread |

> [!NOTE]
> **Segment & Stop ID Constraints**
> - **Both Stop IDs Null:** Supported by the domain logic only if the journey has at least two dated stops in `journey_stops`. The domain automatically resolves to the journey's first stop sequence and last stop sequence.
> - **Mixed Null Stop IDs:** If only one stop ID is provided, the backend rejects the request with HTTP `400 Bad Request` (`Both boardingJourneyStopId and alightingJourneyStopId are required`).
> - **Both Stop IDs Provided:** Both IDs must belong to the target journey, and the boarding stop sequence must be strictly less than the alighting stop sequence.

### Guarded Execution & Cascading Failure Prevention

The test plan utilizes nested JMeter `IfController` blocks to prevent cascading invalid requests when earlier steps fail:
1. **`01_POST_Auth_Login`**: Authenticates the test user.
2. **`If Login Succeeded` (`vars.get('access_token') != 'NOT_FOUND'`)**: Only attempts seat hold if an access token was successfully extracted.
3. **`If Seat Hold Succeeded` (`vars.get('JMeterThread.last_sample_ok') == 'true'`)**: Only attempts booking creation if the seat hold was granted.
4. **`If Booking Created` (`vars.get('bookingRef') != 'NOT_FOUND'`)**: Contains both payment and cancellation. If booking creation fails, neither payment nor cancellation is called with `NOT_FOUND` references.

### Cleanup Semantics & Failure Limitations

- **Successful Lifecycle:** When payment succeeds, `05_POST_Booking_Cancel` transitions the booking to `CANCELLED`, releases the `SeatAllocation` (`status = CANCELLED`), and records a mock refund ledger entry (`paymentStatus = REFUNDED`).
- **Payment Failure Path:** If payment fails (e.g. simulated failure or timeout), `05_POST_Booking_Cancel` still executes because `bookingRef` was created. In Railora, unpaid bookings in `PENDING_PAYMENT` are eligible for cancellation, which immediately releases the seat allocation.
- **Hold Leak Limitation (Step 3 Failure):** If booking creation fails after a hold was acquired in Step 2, no `bookingRef` is generated, and the test cannot cancel the hold via API. The seat remains on `HOLD` until `holdMinutes` elapses, at which point the application's background `SeatHoldReaperService` (running every 30 seconds) automatically marks the hold `EXPIRED` and frees the inventory.
- **Cancellation Failure Limitation (Step 5 Failure):** If cancellation fails after payment confirmation, the booking remains `CONFIRMED` and the allocation remains `BOOKED`. The background reaper will not expire confirmed bookings. The seat must be manually cancelled or the test database re-initialized.

### Command Line Execution Template (Non-GUI Mode)

Prerequisite: Ensure output directory `performance/results` exists before running:
```powershell
if (-not (Test-Path "performance/results")) { New-Item -ItemType Directory -Path "performance/results" }
```

Execution command:
```powershell
& "<path-to-jmeter>\bin\jmeter.bat" `
  -n `
  -t "performance/smoke-write-lifecycle.jmx" `
  -l "performance/results/smoke-write-run.jtl" `
  -j "performance/results/smoke-write-jmeter.log" `
  -Jhost=localhost -Jport=8080 `
  -Jusername=<TEST_USERNAME> -Jpassword=<TEST_PASSWORD> `
  -JjourneyId=<JOURNEY_ID> -JseatId=<SEAT_ID> `
  -Jthreads=1 -Jrampup=1 -Jloops=1
```

---

## 7. Isolated Test Environment Setup (`railway_booking_test`)

> [!CAUTION]
> **Safety Rule: Never Execute Against Development Database**
> Mutating performance or smoke tests must **never** target `railway_booking`. All tests must execute against a dedicated, disposable database `railway_booking_test`.

### 7.1 Prerequisites & PostgreSQL Permissions
1. **PostgreSQL Server:** Running locally on `localhost:5432` (PostgreSQL 12+ required for generated columns and range constraints).
2. **Role Privileges:** The connecting PostgreSQL role (e.g. `postgres`) must have `CREATEDB` privilege to create `railway_booking_test` and permission to install `btree_gist` extension (required by Flyway migration V2).

### 7.2 Safe Provisioning Workflow

#### Step 1: Provision the Isolated Test Database
Execute the safeguarded provisioner script (requires explicit confirmation flag):
```powershell
powershell -File scripts/setup_test_db.ps1 -ConfirmTargetDatabase "railway_booking_test"
```
*Safety guarantee:* The script halts immediately if `-ConfirmTargetDatabase` is missing, incorrect, or references `railway_booking`. It executes `scripts/create_railway_booking_test.sql` to idempotently create `railway_booking_test` and pre-provision `btree_gist`.

#### Step 2: Launch Spring Boot Bound to `railway_booking_test`
In a separate terminal, start the backend with explicit overrides:
```powershell
$env:DB_URL = "jdbc:postgresql://localhost:5432/railway_booking_test"
$env:RAILWAY_OUTBOX_KAFKA_ENABLED = "false"
$env:SEED_JOURNEY_DATA_ENABLED = "true"

mvn spring-boot:run `
  -Dspring-boot.run.arguments="--spring.datasource.url=jdbc:postgresql://localhost:5432/railway_booking_test --DB_URL=jdbc:postgresql://localhost:5432/railway_booking_test --railway.outbox.kafka.enabled=false --railway.data-seed.journey.enabled=true"
```
*What happens on startup:*
- Flyway automatically applies migrations `V1__initial_schema.sql` through `V5__create_outbox_events.sql` on `railway_booking_test`.
- `seedJourneyReferenceData` automatically seeds Train `12190`, Coach `B1` (4 seats), and a valid dated journey between `NDLS` and `BCT` with 2 dated stops.
- Console logs confirm: `Database: jdbc:postgresql://localhost:5432/railway_booking_test`.

#### Step 3: Register a Test User
Once the backend is up on `http://localhost:8080`, register an isolated test user via the public endpoint (avoiding manual password hashing):
```powershell
Invoke-RestMethod -Uri "http://localhost:8080/api/auth/register" -Method Post -ContentType "application/json" -Body (@{
    username    = "smoke_test_user"
    fullName    = "Smoke Test User"
    email       = "smoke_test_user@railwaybooking.local"
    phoneNumber = "9999999999"
    password    = "SmokePassword123!"
} | ConvertTo-Json)
```

#### Step 4: Run the JMeter Smoke Test
Execute the write-lifecycle smoke test using the provisioned credentials and journey data:
```powershell
& "<path-to-jmeter>\bin\jmeter.bat" `
  -n `
  -t "performance/smoke-write-lifecycle.jmx" `
  -l "performance/results/smoke-write-run.jtl" `
  -j "performance/results/smoke-write-jmeter.log" `
  -Jhost=localhost -Jport=8080 `
  -Jusername=smoke_test_user -Jpassword=SmokePassword123! `
  -JjourneyId=1 -JseatId=1 `
  -Jthreads=1 -Jrampup=1 -Jloops=1
```

### 7.3 Disaster Recovery & Database Reset
If a test failure leaves orphaned bookings or holds:
```powershell
# Drop and recreate the isolated test database safely:
psql -h localhost -p 5432 -U postgres -d postgres -c "DROP DATABASE IF EXISTS railway_booking_test; CREATE DATABASE railway_booking_test;"
```
Re-running Step 2 will re-apply clean migrations and seed fresh fixtures in seconds.
