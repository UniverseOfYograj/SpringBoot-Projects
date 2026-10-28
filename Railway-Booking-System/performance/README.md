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
