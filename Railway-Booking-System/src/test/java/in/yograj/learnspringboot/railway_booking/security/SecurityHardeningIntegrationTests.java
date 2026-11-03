package in.yograj.learnspringboot.railway_booking.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.yograj.learnspringboot.railway_booking.dto.LoginRequest;
import in.yograj.learnspringboot.railway_booking.dto.TokenRefreshRequest;
import in.yograj.learnspringboot.railway_booking.entity.RefreshToken;
import in.yograj.learnspringboot.railway_booking.entity.User;
import in.yograj.learnspringboot.railway_booking.integration.githubdata.GitHubRailwayDataImportService;
import in.yograj.learnspringboot.railway_booking.integration.railradar.RailRadarClient;
import in.yograj.learnspringboot.railway_booking.repository.RefreshTokenRepository;
import in.yograj.learnspringboot.railway_booking.repository.UserRepository;
import in.yograj.learnspringboot.railway_booking.service.RefreshTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "DB_URL=jdbc:h2:mem:security-hardening-tests;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.url=jdbc:h2:mem:security-hardening-tests;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.access-secret=test-access-signing-secret-with-minimum-32-bytes-length",
        "jwt.refresh-secret=test-refresh-signing-secret-with-minimum-32-bytes-length",
        "railway.seat-hold-reaper.enabled=false",
        "railway.cache.enabled=false",
        "tatkal.queue.enabled=false",
        "railway.security.cors.allowed-origins=http://localhost:5173,http://127.0.0.1:5173,http://localhost:3000",
        "railway.security.login.max-attempts-username=3",
        "railway.security.login.max-attempts-ip=5",
        "railway.security.login.block-duration-seconds=60"
})
class SecurityHardeningIntegrationTests {

    @MockitoBean
    private GitHubRailwayDataImportService gitHubRailwayDataImportService;

    @MockitoBean
    private RailRadarClient railRadarClient;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private LoginRateLimiter loginRateLimiter;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        loginRateLimiter.resetAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();

        testUser = new User();
        testUser.setUsername("alice");
        testUser.setFullName("Alice Traveler");
        testUser.setEmail("alice@example.com");
        testUser.setPassword(passwordEncoder.encode("ValidPass123!"));
        testUser.setRole("ROLE_USER");
        testUser.setStatus("ACTIVE");
        testUser = userRepository.save(testUser);
    }

    // =========================================================================
    // 1. Invalid Credentials
    // =========================================================================
    @Test
    void login_invalidPassword_returns401Unauthorized() throws Exception {
        LoginRequest req = new LoginRequest("alice", "WrongPassword!");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Invalid username/email or password"));
    }

    // =========================================================================
    // 2. Repeated Login Attempts and Rate Limits
    // =========================================================================
    @Test
    void login_repeatedFailedAttempts_triggersRateLimit429WithRetryAfter() throws Exception {
        LoginRequest wrongReq = new LoginRequest("alice", "WrongPassword!");

        // First 3 failed attempts (maxAllowed = 3)
        for (int i = 1; i <= 3; i++) {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(wrongReq)))
                    .andExpect(status().isUnauthorized());
        }

        // 4th attempt should be blocked by LoginRateLimiter with HTTP 429
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(wrongReq)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Too many failed login attempts")));
    }

    @Test
    void login_successfulLogin_resetsFailureCounter() throws Exception {
        LoginRequest wrongReq = new LoginRequest("alice", "WrongPassword!");

        // 2 failures (threshold is 3)
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(wrongReq)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(wrongReq)))
                .andExpect(status().isUnauthorized());

        // Successful login resets counter
        LoginRequest correctReq = new LoginRequest("alice", "ValidPass123!");
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(correctReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());

        // Subsequent failure starts from 1 again (not blocked)
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(wrongReq)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void login_spoofedForwardedFor_usesRemoteAddressForRateLimit() throws Exception {
        String remoteAddress = "198.51.100.10";
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertEquals(401, performLogin(
                    "spoofed-user-" + attempt,
                    "WrongPassword!",
                    remoteAddress,
                    "203.0.113." + attempt));
        }

        assertEquals(429, performLogin("another-user", "WrongPassword!", remoteAddress, null));
    }

    @Test
    void login_successfulLogin_doesNotResetSharedIpFailureCounter() throws Exception {
        String remoteAddress = "198.51.100.11";
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertEquals(401, performLogin(
                    "shared-ip-user-" + attempt,
                    "WrongPassword!",
                    remoteAddress,
                    null));
        }

        assertEquals(200, performLogin("alice", "ValidPass123!", remoteAddress, null));
        assertEquals(401, performLogin("shared-ip-user-4", "WrongPassword!", remoteAddress, null));
        assertEquals(401, performLogin("shared-ip-user-5", "WrongPassword!", remoteAddress, null));
        assertEquals(429, performLogin("shared-ip-user-6", "WrongPassword!", remoteAddress, null));
    }

    @Test
    void login_concurrentFailures_respectUsernameAttemptLimit() throws Exception {
        String body = objectMapper.writeValueAsString(new LoginRequest("alice", "WrongPassword!"));
        List<Integer> statuses = runConcurrently(8, () -> mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn()
                .getResponse()
                .getStatus());

        assertEquals(3, statuses.stream().filter(status -> status == 401).count());
        assertEquals(5, statuses.stream().filter(status -> status == 429).count());
    }

    // =========================================================================
    // 3. Expired Access Token
    // =========================================================================
    @Test
    void expiredAccessToken_isRejectedWith401Unauthorized() throws Exception {
        // Build expired access token (-60 seconds)
        String expiredToken = io.jsonwebtoken.Jwts.builder()
                .subject("alice")
                .claim("role", "ROLE_USER")
                .issuedAt(new java.util.Date(System.currentTimeMillis() - 120_000))
                .expiration(new java.util.Date(System.currentTimeMillis() - 60_000))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        "test-access-signing-secret-with-minimum-32-bytes-length".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .compact();

        mockMvc.perform(get("/api/bookings")
                        .header("Authorization", "Bearer " + expiredToken)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // 4. Refresh Token Rotation (RTR)
    // =========================================================================
    @Test
    void refresh_validToken_rotatesToken_issuesNewTokensAndRevokesOld() throws Exception {
        RefreshToken initialToken = refreshTokenService.createRefreshToken(testUser);
        String oldTokenStr = initialToken.getToken();

        TokenRefreshRequest refreshReq = new TokenRefreshRequest(oldTokenStr);

        MvcResult result = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        String newTokenStr = objectMapper.readTree(responseBody).get("refreshToken").asText();

        // New token must differ from old token
        assertNotEquals(oldTokenStr, newTokenStr);

        // Old token must be marked revoked in DB
        Optional<RefreshToken> oldRecord = refreshTokenRepository.findByToken(oldTokenStr);
        assertTrue(oldRecord.isPresent());
        assertTrue(oldRecord.get().isRevoked(), "Old refresh token must be marked revoked upon rotation");

        // New token must exist and NOT be revoked
        Optional<RefreshToken> newRecord = refreshTokenRepository.findByToken(newTokenStr);
        assertTrue(newRecord.isPresent());
        assertFalse(newRecord.get().isRevoked(), "New refresh token must be active and not revoked");
    }

    @Test
    void refresh_concurrentRequests_consumeTokenOnlyOnce() throws Exception {
        String token = refreshTokenService.createRefreshToken(testUser).getToken();
        String body = objectMapper.writeValueAsString(new TokenRefreshRequest(token));

        List<Integer> statuses = runConcurrently(2, () -> mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn()
                .getResponse()
                .getStatus());

        assertEquals(1, statuses.stream().filter(status -> status == 200).count());
        assertEquals(1, statuses.stream().filter(status -> status == 400).count());
        assertTrue(refreshTokenRepository.findAll().isEmpty(),
                "The losing reuse attempt must revoke the token family, including the rotated token");
    }

    // =========================================================================
    // 5. Expired Refresh Token
    // =========================================================================
    @Test
    void refresh_expiredToken_returns400AndRevokesTokens() throws Exception {
        RefreshToken expiredToken = new RefreshToken();
        expiredToken.setToken(jwtService.generateRefreshToken(testUser.getUsername()));
        expiredToken.setUser(testUser);
        expiredToken.setExpiresAt(Instant.now().minusSeconds(3600)); // expired 1 hour ago
        expiredToken.setRevoked(false);
        refreshTokenRepository.save(expiredToken);

        TokenRefreshRequest refreshReq = new TokenRefreshRequest(expiredToken.getToken());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("expired or revoked")));
    }

    // =========================================================================
    // 6. Reuse of an Old (Revoked) Refresh Token
    // =========================================================================
    @Test
    void refresh_reusedOldToken_detectsAttack_revokesAllUserTokensAndReturns400() throws Exception {
        // Step 1: Initial token
        RefreshToken initialToken = refreshTokenService.createRefreshToken(testUser);
        String tokenV1 = initialToken.getToken();

        // Step 2: First rotation -> produces tokenV2, revokes tokenV1
        TokenRefreshRequest req1 = new TokenRefreshRequest(tokenV1);
        MvcResult res1 = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isOk())
                .andReturn();

        String tokenV2 = objectMapper.readTree(res1.getResponse().getContentAsString()).get("refreshToken").asText();

        // Step 3: Attacker tries to use tokenV1 again (REUSE ATTACK)
        TokenRefreshRequest attackerReq = new TokenRefreshRequest(tokenV1);
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(attackerReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("expired or revoked")));

        // Step 4: Verify that reuse detection revoked ALL user tokens (including tokenV2)
        assertEquals(0, refreshTokenRepository.findAll().size(),
                "All refresh tokens for user must be revoked upon detecting a reuse attack");
    }

    // =========================================================================
    // 7. Logout / Revocation
    // =========================================================================
    @Test
    void logout_withRefreshToken_revokesToken() throws Exception {
        RefreshToken token = refreshTokenService.createRefreshToken(testUser);
        String tokenStr = token.getToken();

        TokenRefreshRequest logoutReq = new TokenRefreshRequest(tokenStr);

        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(logoutReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Logged out successfully"));

        // Verify token is revoked
        Optional<RefreshToken> updated = refreshTokenRepository.findByToken(tokenStr);
        assertTrue(updated.isPresent());
        assertTrue(updated.get().isRevoked());

        // Subsequent refresh with logged-out token must fail
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(logoutReq)))
                .andExpect(status().isBadRequest());
    }

    // =========================================================================
    // 8. CORS Allowed and Rejected Origins
    // =========================================================================
    @Test
    void cors_allowedOrigin_includesAccessControlAllowOrigin() throws Exception {
        mockMvc.perform(options("/api/trains/search")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void cors_rejectedOrigin_doesNotIncludeAccessControlAllowOrigin() throws Exception {
        mockMvc.perform(options("/api/trains/search")
                        .header(HttpHeaders.ORIGIN, "http://evil-attacker.com")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // 9. Security Headers
    // =========================================================================
    @Test
    void securityHeaders_verifiedOnResponses() throws Exception {
        mockMvc.perform(get("/api/trains/search?from=NDLS&to=BCT")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"));
    }

    // =========================================================================
    // 10. Unauthorized Booking Access
    // =========================================================================
    @Test
    void bookings_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/bookings")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    private int performLogin(String username, String password, String remoteAddress, String forwardedFor)
            throws Exception {
        MockHttpServletRequestBuilder request = post("/api/auth/login")
                .with(mockRequest -> {
                    mockRequest.setRemoteAddr(remoteAddress);
                    return mockRequest;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest(username, password)));
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    private List<Integer> runConcurrently(int requestCount, Callable<Integer> request) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < requestCount; i++) {
                results.add(executor.submit(() -> {
                    startGate.await();
                    return request.call();
                }));
            }
            startGate.countDown();

            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get(30, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            executor.shutdownNow();
        }
    }
}
