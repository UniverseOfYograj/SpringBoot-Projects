const { chromium } = require('playwright-core');
const { execSync } = require('child_process');

const FRONTEND_URL = process.env.FRONTEND_URL || 'http://localhost:5173';
const BACKEND_URL = process.env.BACKEND_URL || 'http://localhost:8080';
const CHROME_PATH = process.env.CHROME_PATH || 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';

const FROM_STATION = 'ORG';
const TO_STATION = 'DST';
const TRAVEL_DATE = '2026-11-06';

async function main() {
  console.log('================================================================');
  console.log('Railora Tatkal End-to-End Hardened Browser Test (Phase 1)');
  console.log('Target: Isolated PostgreSQL (15432) & Isolated Redis (16379)');
  console.log('================================================================');

  const steps = [];
  function recordStep(name, passed, detail = '') {
    steps.push({ name, passed, detail });
    console.log(`[${passed ? 'PASS' : 'FAIL'}] ${name}${detail ? ` - ${detail}` : ''}`);
    if (!passed) {
      throw new Error(`Step failed: ${name} - ${detail}`);
    }
  }

  // 1. Confirm database isolation & backend connectivity
  console.log('\n--- Step 1: Confirming Database Isolation & Backend Health ---');
  try {
    const res = await fetch(`${BACKEND_URL}/api/trains/search?from=ORG&to=DST`);
    if (res.status === 200) {
      const data = await res.json();
      recordStep('Backend connectivity & isolated DB response', data.length > 0, `Found ${data.length} test train(s)`);
    } else {
      recordStep('Backend connectivity & isolated DB response', false, `Status ${res.status}`);
    }
  } catch (err) {
    recordStep('Backend connectivity & isolated DB response', false, err.message);
  }

  // Generate unique test user for complete test isolation
  const uniqueId = Math.random().toString(36).substring(2, 10);
  const testUser = `e2e-user-${uniqueId}`;
  const testPass = 'E2ePassword123!';
  const testEmail = `e2e-${uniqueId}@example.test`;

  console.log(`\nRegistering fresh isolated test user: ${testUser}`);
  const regRes = await fetch(`${BACKEND_URL}/api/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      username: testUser,
      password: testPass,
      fullName: 'E2E Real Browser Traveler',
      email: testEmail,
    }),
  });
  if (regRes.status !== 201) {
    const errText = await regRes.text();
    recordStep('Register isolated test user', false, `HTTP ${regRes.status}: ${errText}`);
  }
  recordStep('Register isolated test user', true, testUser);

  // 2. Launch Real Browser
  console.log('\n--- Step 2: Launching Chrome Browser ---');
  const browser = await chromium.launch({
    executablePath: CHROME_PATH,
    headless: true,
  });
  console.log(`Browser: Google Chrome v${browser.version()}`);
  const context = await browser.newContext({
    viewport: { width: 1280, height: 800 },
  });
  const page = await context.newPage();

  page.on('console', msg => {
    if (msg.type() === 'error') {
      console.log(`[Browser Console Error]: ${msg.text()}`);
    }
  });

  try {
    // 3. User Login
    console.log('\n--- Step 3: Login User Flow ---');
    await page.goto(`${FRONTEND_URL}/login`, { waitUntil: 'networkidle' });
    recordStep('Navigate to Login Page', page.url().includes('/login'));

    await page.fill('input[name="username"]', testUser);
    await page.fill('input[name="password"]', testPass);
    await page.click('button[type="submit"]');

    await page.waitForURL(`${FRONTEND_URL}/`, { timeout: 10000 });
    recordStep('Sign in and redirect to Home', page.url() === `${FRONTEND_URL}/`);

    // Verify session in localStorage
    const storedSession = await page.evaluate(() => localStorage.getItem('railora_auth_session'));
    recordStep('Session initialized in browser', Boolean(storedSession));

    // 4. Train Search
    console.log('\n--- Step 4: Train Search Flow ---');
    await page.goto(`${FRONTEND_URL}/search`, { waitUntil: 'networkidle' });

    // Fill form
    await page.fill('#search-from-station', FROM_STATION);
    await page.fill('#search-to-station', TO_STATION);
    await page.fill('#search-travel-date', TRAVEL_DATE);

    // Submit search
    await page.click('button[type="submit"]');

    // Wait for train card
    await page.waitForSelector('article[aria-label*="Isolated Tatkal Integration Express"]', { timeout: 10000 });
    recordStep('Train Search displays Isolated Tatkal Express', true, 'Card found in DOM');

    // 5. Waiting Room Queue Admission
    console.log('\n--- Step 5: Tatkal Waiting Room & Queue Admission ---');
    const waitingRoomLink = page.locator('a[aria-label*="Enter Tatkal waiting room"]');
    await waitingRoomLink.click();

    await page.waitForURL(url => url.pathname.includes('/waiting-room'), { timeout: 10000 });
    recordStep('Navigated to Virtual Waiting Room', page.url().includes('/waiting-room'));

    // Wait for Admission Granted status
    await page.waitForSelector('text=Admission Granted', { timeout: 15000 });
    const admittedHeading = await page.textContent('h3');
    recordStep('Queue FIFO admission granted', admittedHeading.includes('You are Admitted to Book!'), admittedHeading);

    // Verify admission credentials are absent from URL, localStorage, and sessionStorage
    const currentUrl = page.url();
    const tokenInUrl = currentUrl.includes('admissionToken') ||
                       currentUrl.includes('admissionPass') ||
                       currentUrl.includes('queueToken') ||
                       currentUrl.includes('queueEntryId');

    const storageAudit = await page.evaluate(() => {
      const sensitiveKeys = ['admissionToken', 'admissionPass', 'queueToken', 'queueEntryId', 'tatkalAdmission'];
      const foundInLocal = sensitiveKeys.filter(k => localStorage.getItem(k) !== null);
      const foundInSession = sensitiveKeys.filter(k => sessionStorage.getItem(k) !== null);

      const localKeys = Object.keys(localStorage);
      const sessionKeys = Object.keys(sessionStorage);

      const localFuzzy = localKeys.filter(k => /admission|queue.*token|queue.*entry/i.test(k));
      const sessionFuzzy = sessionKeys.filter(k => /admission|queue.*token|queue.*entry/i.test(k));

      return {
        foundInLocal,
        foundInSession,
        localFuzzy,
        sessionFuzzy,
        localKeys,
        sessionKeys,
      };
    });

    const isCredentialStorageSafe = !tokenInUrl &&
      storageAudit.foundInLocal.length === 0 &&
      storageAudit.foundInSession.length === 0 &&
      storageAudit.localFuzzy.length === 0 &&
      storageAudit.sessionFuzzy.length === 0;

    recordStep(
      'Admission credentials absent from URL query, localStorage, and sessionStorage',
      isCredentialStorageSafe,
      `URL safe: ${!tokenInUrl}, Local: [${storageAudit.localKeys.join(', ')}], Session: [${storageAudit.sessionKeys.join(', ')}]`
    );

    // 6. Proceed to Seat Selection & Hold
    console.log('\n--- Step 6: Seat Selection & Tatkal Hold ---');
    const proceedBtn = page.locator('button:has-text("Proceed to Seat Selection")');
    await proceedBtn.click();

    // Modal opens
    await page.waitForSelector('div[role="dialog"]', { timeout: 10000 });
    recordStep('Seat selection modal opened', true);

    // Find available seat buttons
    await page.waitForSelector('div.grid button:not([disabled])', { timeout: 10000 });
    const availableSeats = page.locator('div.grid button:not([disabled])');
    const seatCount = await availableSeats.count();
    recordStep('Available seats loaded in modal', seatCount > 0, `Found ${seatCount} available seats`);

    // Click on available seat
    await availableSeats.first().click();
    recordStep('Seat selected', true);

    // Click Hold & Proceed
    const holdBtn = page.locator('button:has-text("Hold & Proceed")');
    await holdBtn.click();

    // 7. Payment Simulation
    console.log('\n--- Step 7: Payment Screen & Confirmation ---');
    await page.waitForSelector('text=Booking Reference', { timeout: 10000 });
    const bookingRefLocator = page.locator('div:has(span:has-text("Booking Reference")) > span.font-mono');
    const bookingRef = (await bookingRefLocator.textContent()).trim();
    recordStep('Seat held and booking created (PENDING_PAYMENT)', Boolean(bookingRef), `BookingRef: ${bookingRef}`);

    // Click Pay & Confirm
    const payBtn = page.locator('button:has-text("Pay")');
    await payBtn.click();

    // Wait for Confirmed screen
    await page.waitForSelector('div[role="dialog"] h3:has-text("Reservation Confirmed!")', { timeout: 15000 });
    const modalHeading = await page.locator('div[role="dialog"] h3').textContent();
    recordStep('Payment confirmed and reservation finalized', modalHeading.includes('Reservation Confirmed!'), modalHeading);

    // 8. Navigation to My Bookings and Strengthened Assertions
    console.log('\n--- Step 8: My Bookings Verification (Strengthened Assertions) ---');
    const viewBookingsBtn = page.locator('button:has-text("View in My Bookings")');
    await viewBookingsBtn.click();

    await page.waitForURL(url => url.pathname.includes('/bookings'), { timeout: 10000 });
    recordStep('Navigated to My Bookings', page.url().includes('/bookings'));

    // Verify booking reference appears in list
    await page.waitForSelector(`text=PNR: ${bookingRef}`, { timeout: 10000 });
    const pnrLocator = page.locator(`text=PNR: ${bookingRef}`);
    const isPnrVisible = await pnrLocator.isVisible();

    // Verify the booking card contains both bookingRef and the CONFIRMED status badge
    const bookingCard = page.locator('div.rounded-2xl, div.overflow-hidden', { has: pnrLocator }).first();
    const isCardVisible = await bookingCard.isVisible();

    // Verify the status badge within this card displays CONFIRMED
    const badgeLocator = bookingCard.locator('span:text-is("CONFIRMED")');
    await badgeLocator.waitFor({ state: 'visible', timeout: 5000 });
    const badgeText = (await badgeLocator.textContent() || '').trim();

    recordStep(
      'Booking appears in My Bookings with verified CONFIRMED status badge',
      isPnrVisible && isCardVisible && badgeText === 'CONFIRMED',
      `PNR: ${bookingRef}, Status Badge: "${badgeText}"`
    );

    // Post-booking storage audit: confirm credentials were never placed in storage throughout entire journey
    const postBookingStorageAudit = await page.evaluate(() => {
      const sensitiveKeys = ['admissionToken', 'admissionPass', 'queueToken', 'queueEntryId', 'tatkalAdmission'];
      const foundInLocal = sensitiveKeys.filter(k => localStorage.getItem(k) !== null);
      const foundInSession = sensitiveKeys.filter(k => sessionStorage.getItem(k) !== null);
      return {
        safe: foundInLocal.length === 0 && foundInSession.length === 0,
        foundInLocal,
        foundInSession,
      };
    });
    recordStep(
      'Post-booking credential audit: zero credential remnants in storage',
      postBookingStorageAudit.safe,
      `Local violations: [${postBookingStorageAudit.foundInLocal}], Session violations: [${postBookingStorageAudit.foundInSession}]`
    );

    // 9. Negative & Security Tests: Direct Hold Rejection without Admission Pass
    console.log('\n--- Step 9: Security Negative Test (Direct Tatkal Hold without Admission Pass) ---');
    const sessionObj = JSON.parse(storedSession);
    const authHeader = `Bearer ${sessionObj.accessToken}`;

    const rejectRes = await fetch(`${BACKEND_URL}/api/journeys/1/seats/hold`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: authHeader,
      },
      body: JSON.stringify({
        seatId: 5,
        holdMinutes: 10,
        boardingJourneyStopId: 1,
        alightingJourneyStopId: 2,
        bookingMode: 'TATKAL',
      }),
    });
    recordStep(
      'Direct Tatkal seat-hold without admission pass rejected with 403 Forbidden',
      rejectRes.status === 403,
      `HTTP ${rejectRes.status}`
    );

    // 10. Payment Idempotency & Duplicate Prevention Test
    console.log('\n--- Step 10: Payment Idempotency & Duplicate Prevention Test (Strict HTTP 409 & DB Check) ---');
    const dupPayRes = await fetch(`${BACKEND_URL}/api/bookings/${bookingRef}/pay`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: authHeader,
      },
      body: JSON.stringify({
        paymentMethod: 'MOCK_UPI',
        idempotencyKey: 'IDEMP-TEST-RETRY-1234',
        simulateFailure: false,
      }),
    });
    let dupPayData = {};
    try {
      dupPayData = await dupPayRes.json();
    } catch (_) {}

    recordStep(
      'Duplicate payment attempt strictly rejected with HTTP 409 Conflict',
      dupPayRes.status === 409,
      `HTTP ${dupPayRes.status}${dupPayData.message ? ` - "${dupPayData.message}"` : ''}`
    );

    // Verify database state: exactly one payment transaction remains for this booking
    const txnCountStr = execSync(
      `docker exec railora-tatkal-it-postgres-1 psql -U railora_test -d railora_tatkal_it -t -A -c "SELECT COUNT(*) FROM payment_transactions WHERE booking_ref = '${bookingRef}';"`
    ).toString().trim();
    const txnCount = parseInt(txnCountStr, 10);

    const txnSummary = execSync(
      `docker exec railora-tatkal-it-postgres-1 psql -U railora_test -d railora_tatkal_it -t -A -c "SELECT status, payment_method, amount FROM payment_transactions WHERE booking_ref = '${bookingRef}';"`
    ).toString().trim();

    recordStep(
      'Database payment isolation: exactly one payment transaction recorded',
      txnCount === 1,
      `Count: ${txnCount}, Record: [${txnSummary}]`
    );

    console.log('\n================================================================');
    console.log('ALL PHASE 1 HARDENED BROWSER E2E STEPS PASSED SUCCESSFULLY!');
    console.log('================================================================');
  } finally {
    await browser.close();
  }
}

main().catch(err => {
  console.error('\nE2E EXECUTION FAILED:');
  console.error(err);
  process.exit(1);
});
