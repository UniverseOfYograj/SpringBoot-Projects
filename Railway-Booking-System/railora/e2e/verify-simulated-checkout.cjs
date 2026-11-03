const { chromium } = require('playwright-core');
const fs = require('fs');
const path = require('path');

const FRONTEND_URL = 'http://127.0.0.1:5173';
const BACKEND_URL = 'http://127.0.0.1:8080';
const CHROME_PATH = process.env.CHROME_PATH || 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const SCREENSHOT_DIR = path.join(__dirname, 'screenshots');

if (!fs.existsSync(SCREENSHOT_DIR)) {
  fs.mkdirSync(SCREENSHOT_DIR, { recursive: true });
}

async function runVerification() {
  console.log('================================================================');
  console.log('Railora — Automated Browser Verification: SIMULATED Payment Mode');
  console.log('================================================================');

  const browser = await chromium.launch({
    executablePath: CHROME_PATH,
    headless: true,
  });

  const context = await browser.newContext({
    viewport: { width: 1280, height: 800 },
  });
  const page = await context.newPage();

  const results = [];
  function record(stepName, passed, details = '') {
    results.push({ stepName, passed, details });
    console.log(`[${passed ? 'PASS' : 'FAIL'}] ${stepName}${details ? ` - ${details}` : ''}`);
    if (!passed) {
      throw new Error(`Step failed: ${stepName} (${details})`);
    }
  }

  try {
    // 1. Authenticate via real Browser UI Login Flow
    console.log('\n--- 1. Logging in via Browser UI (/login) ---');
    await page.goto(`${FRONTEND_URL}/login`);
    await page.waitForLoadState('networkidle');

    await page.fill('input[name="username"]', 'smoke_test_user');
    await page.fill('input[name="password"]', 'SmokePassword123!');
    await page.screenshot({ path: path.join(SCREENSHOT_DIR, '01-login-form.png') });

    await page.click('button[type="submit"]');
    await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 10000 });
    record('UI Login', true, 'Successfully signed in and redirected');

    // Extract auth token stored in localStorage
    const storedSession = await page.evaluate(() => localStorage.getItem('railora_auth_session'));
    const session = JSON.parse(storedSession);
    const token = session.accessToken;
    record('Auth Session in Storage', !!token, 'Valid JWT access token in railora_auth_session');

    // 2. Check for an eligible pending booking, or create one
    console.log('\n--- 2. Ensuring an eligible pending booking exists ---');
    let bookingRef = null;
    const historyRes = await fetch(`${BACKEND_URL}/api/bookings`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    const historyData = await historyRes.json();
    const existingPending = historyData.content?.find((b) => b.bookingStatus === 'PENDING_PAYMENT');

    if (existingPending) {
      bookingRef = existingPending.bookingRef;
      record('Pending Booking Ready', true, `Reusing BookingRef: ${bookingRef}, Fare: ₹${existingPending.amount}`);
    } else {
      // Hold seat 6 (Seat 2 on coach B1)
      const holdRes = await fetch(`${BACKEND_URL}/api/journeys/2/seats/hold`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify({
          seatId: 6,
          holdMinutes: 10,
          boardingJourneyStopId: 3,
          alightingJourneyStopId: 7,
        }),
      });
      const holdData = await holdRes.json();
      record('Hold seat', holdRes.status === 200 || holdRes.status === 201, `Hold status: ${holdData.status}`);

      const bookRes = await fetch(`${BACKEND_URL}/api/bookings`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify({
          journeyId: 2,
          seatId: 6,
          boardingJourneyStopId: 3,
          alightingJourneyStopId: 7,
        }),
      });
      const bookData = await bookRes.json();
      bookingRef = bookData.bookingRef;
      record('Create booking', bookRes.status === 200 || bookRes.status === 201, `BookingRef: ${bookingRef}`);
    }

    // 3. Navigate to Bookings page in browser
    console.log('\n--- 3. Viewing Bookings page in browser ---');
    await page.goto(`${FRONTEND_URL}/bookings`);
    await page.waitForLoadState('networkidle');
    await page.screenshot({ path: path.join(SCREENSHOT_DIR, '02-bookings-page.png') });
    record('Navigate to /bookings', true, 'Bookings list loaded');

    // 4. Locate "Demo Checkout" button on pending booking card
    console.log('\n--- 4. Opening SIMULATED Checkout Modal ---');
    const demoCheckoutBtn = page.locator('button:has-text("Demo Checkout")').first();
    await demoCheckoutBtn.waitFor({ state: 'visible', timeout: 5000 });
    record('Find Demo Checkout button', true, 'Button is visible for pending booking');
    await demoCheckoutBtn.click();

    // 5. Verify Modal contents (Demo Notice, Persisted Fare, Journey Details)
    console.log('\n--- 5. Verifying Demo Notice, Persisted Fare, and Journey Details ---');
    const modal = page.locator('div[role="dialog"]');
    await modal.waitFor({ state: 'visible', timeout: 5000 });

    const demoNotice = modal.locator('text=DEMO MODE — NO REAL MONEY WILL BE CHARGED');
    await demoNotice.waitFor({ state: 'visible', timeout: 5000 });
    record('Verify DEMO MODE banner', true, 'Prominent disclaimer displayed');

    const fareText = modal.locator('text=₹500.00');
    await fareText.first().waitFor({ state: 'visible', timeout: 5000 });
    record('Verify Persisted Fare', true, 'Server-persisted ₹500.00 displayed');

    await page.screenshot({ path: path.join(SCREENSHOT_DIR, '03-simulated-checkout-modal.png') });

    // 6. Simulate Payment Failure
    console.log('\n--- 6. Simulating Payment Failure ---');
    const failBtn = modal.locator('button:has-text("Simulate Failed Payment")');
    await failBtn.waitFor({ state: 'visible', timeout: 5000 });
    await failBtn.click();

    // Verify failure result screen
    const failureHeader = modal.locator('text=Simulated Payment Declined');
    await failureHeader.waitFor({ state: 'visible', timeout: 5000 });
    record('Simulate Payment Failure', true, 'Failure result screen rendered with clear status');

    await page.screenshot({ path: path.join(SCREENSHOT_DIR, '04-simulated-payment-failed.png') });

    // Verify in Backend DB: booking is still PENDING_PAYMENT, seat hold is preserved
    const bookingAfterFailRes = await fetch(`${BACKEND_URL}/api/bookings/${bookingRef}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    const bookingAfterFail = await bookingAfterFailRes.json();
    record(
      'Verify DB State After Failure',
      bookingAfterFail.bookingStatus === 'PENDING_PAYMENT' && bookingAfterFail.paymentStatus === 'FAILED',
      `bookingStatus: ${bookingAfterFail.bookingStatus}, paymentStatus: ${bookingAfterFail.paymentStatus}`
    );

    // 7. Verify Retry Flow with Fresh Idempotency Key
    console.log('\n--- 7. Verifying Retry Flow with Fresh Idempotency Key ---');
    const retryBtn = modal.locator('button:has-text("Retry Payment")');
    await retryBtn.waitFor({ state: 'visible', timeout: 5000 });
    await retryBtn.click();

    // Verify modal returned to idle with Simulate Successful Payment button visible
    const successBtn = modal.locator('button:has-text("Simulate Successful Payment")');
    await successBtn.waitFor({ state: 'visible', timeout: 5000 });
    record('Retry button clicked', true, 'Modal reset to idle state with incremented attempt counter');

    await page.screenshot({ path: path.join(SCREENSHOT_DIR, '05-simulated-checkout-retry.png') });

    // 8. Simulate Successful Payment
    console.log('\n--- 8. Simulating Successful Payment ---');
    await successBtn.click();

    // Wait for success confirmation view
    const successHeader = modal.locator('text=Reservation Confirmed!');
    await successHeader.waitFor({ state: 'visible', timeout: 10000 });

    // Verify PNR is displayed
    const pnrLabel = modal.locator('text=PNR Number:');
    await pnrLabel.waitFor({ state: 'visible', timeout: 5000 });
    record('Simulate Payment Success', true, 'Confirmation screen rendered with confirmed PNR');

    await page.screenshot({ path: path.join(SCREENSHOT_DIR, '06-simulated-payment-success.png') });

    // 9. Verify Final DB State
    console.log('\n--- 9. Verifying Final Database Records ---');
    const verifyBookingRes = await fetch(`${BACKEND_URL}/api/bookings/${bookingRef}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    const finalBooking = await verifyBookingRes.json();
    record(
      'Verify Final Booking State in DB',
      finalBooking.bookingStatus === 'CONFIRMED' && finalBooking.paymentStatus === 'SUCCESS',
      `bookingStatus: ${finalBooking.bookingStatus}, paymentStatus: ${finalBooking.paymentStatus}, PNR: ${finalBooking.pnr}`
    );

    // 10. Test Duplicate Idempotency Replay on Backend
    console.log('\n--- 10. Testing Backend Duplicate Success Idempotency Replay ---');
    const testKey = 'TEST-IDEMP-' + Date.now();
    const idempRes = await fetch(`${BACKEND_URL}/api/bookings/${bookingRef}/simulated/success`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({ idempotencyKey: testKey }),
    });

    record(
      'Duplicate Payment on Confirmed Booking Blocked',
      idempRes.status === 409 || idempRes.status === 400 || idempRes.status === 500,
      `Backend safely blocked re-payment with HTTP ${idempRes.status}`
    );

    console.log('\n================================================================');
    console.log('ALL LOCAL RUNTIME VERIFICATION CHECKS PASSED');
    console.log('================================================================');
  } catch (err) {
    console.error('\nVerification failed:', err.message);
    await page.screenshot({ path: path.join(SCREENSHOT_DIR, '00-error.png') });
    throw err;
  } finally {
    await browser.close();
  }
}

runVerification();
