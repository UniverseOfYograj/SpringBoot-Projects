package in.yograj.learnspringboot.railway_booking.security;

import in.yograj.learnspringboot.railway_booking.entity.User;
import in.yograj.learnspringboot.railway_booking.integration.githubdata.GitHubRailwayDataImportService;
import in.yograj.learnspringboot.railway_booking.integration.railradar.RailRadarClient;
import in.yograj.learnspringboot.railway_booking.repository.RefreshTokenRepository;
import in.yograj.learnspringboot.railway_booking.repository.UserRepository;
import in.yograj.learnspringboot.railway_booking.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "DB_URL=jdbc:h2:mem:user-security-tests;DB_CLOSE_DELAY=-1",
        "DB_USERNAME=sa",
        "DB_PASSWORD=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.url=jdbc:h2:mem:user-security-tests;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "jwt.access-secret=test-access-signing-secret-with-minimum-32-bytes-length",
        "jwt.refresh-secret=test-refresh-signing-secret-with-minimum-32-bytes-length"
})
class UserSecurityAuthorizationTests {

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
    private UserService userService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private MockMvc mockMvc;

    private User regularUser1;
    private User regularUser2;
    private User adminUser;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();

        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();

        regularUser1 = new User();
        regularUser1.setUsername("user1");
        regularUser1.setFullName("User One");
        regularUser1.setEmail("user1@example.com");
        regularUser1.setPassword(passwordEncoder.encode("Password@123"));
        regularUser1.setRole("ROLE_USER");
        regularUser1.setStatus("ACTIVE");
        regularUser1 = userRepository.save(regularUser1);

        regularUser2 = new User();
        regularUser2.setUsername("user2");
        regularUser2.setFullName("User Two");
        regularUser2.setEmail("user2@example.com");
        regularUser2.setPassword(passwordEncoder.encode("Password@123"));
        regularUser2.setRole("ROLE_USER");
        regularUser2.setStatus("ACTIVE");
        regularUser2 = userRepository.save(regularUser2);

        adminUser = new User();
        adminUser.setUsername("admin1");
        adminUser.setFullName("Admin One");
        adminUser.setEmail("admin1@example.com");
        adminUser.setPassword(passwordEncoder.encode("AdminPass@123"));
        adminUser.setRole("ROLE_ADMIN");
        adminUser.setStatus("ACTIVE");
        adminUser = userRepository.save(adminUser);
    }

    @Test
    void unauthenticatedRequestsCannotAccessProtectedEndpoints() throws Exception {
        mockMvc.perform(get("/api/users"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/users/" + regularUser1.getUserId()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(delete("/api/users/" + regularUser1.getUserId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "user1", roles = {"USER"})
    void regularUserCannotListAllUsers() throws Exception {
        mockMvc.perform(get("/api/users"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "user1", roles = {"USER"})
    void regularUserCannotDeleteAnotherUser() throws Exception {
        mockMvc.perform(delete("/api/users/" + regularUser2.getUserId()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "user1", roles = {"USER"})
    void regularUserCannotReadAnotherUsersProfile() throws Exception {
        mockMvc.perform(get("/api/users/" + regularUser2.getUserId()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Access denied: You are not authorized to view this profile"));
    }

    @Test
    @WithMockUser(username = "user1", roles = {"USER"})
    void regularUserCanReadOwnProfileById() throws Exception {
        mockMvc.perform(get("/api/users/" + regularUser1.getUserId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(regularUser1.getUserId()))
                .andExpect(jsonPath("$.username").value("user1"))
                .andExpect(jsonPath("$.email").value("user1@example.com"));
    }

    @Test
    @WithMockUser(username = "user1", roles = {"USER"})
    void regularUserCanReadOwnProfileViaMeEndpoint() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(regularUser1.getUserId()))
                .andExpect(jsonPath("$.username").value("user1"))
                .andExpect(jsonPath("$.email").value("user1@example.com"));
    }

    @Test
    @WithMockUser(username = "admin1", roles = {"ADMIN"})
    void adminCanListAllUsers() throws Exception {
        mockMvc.perform(get("/api/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    @WithMockUser(username = "admin1", roles = {"ADMIN"})
    void adminCanReadAnyUsersProfile() throws Exception {
        mockMvc.perform(get("/api/users/" + regularUser1.getUserId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(regularUser1.getUserId()))
                .andExpect(jsonPath("$.username").value("user1"));
    }

    @Test
    @WithMockUser(username = "admin1", roles = {"ADMIN"})
    void adminCanDeleteUser() throws Exception {
        mockMvc.perform(delete("/api/users/" + regularUser2.getUserId()))
                .andExpect(status().isOk());
    }

    @Test
    void unauthenticatedUserCanRegisterViaBothEndpoints() throws Exception {
        String payloadAuth = """
                {
                    "username": "newauthuser",
                    "fullName": "New Auth User",
                    "email": "newauth@example.com",
                    "phoneNumber": "1234567890",
                    "password": "Password@123"
                }
                """;
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadAuth))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("newauthuser"));

        String payloadUsers = """
                {
                    "username": "newusersuser",
                    "fullName": "New Users User",
                    "email": "newusers@example.com",
                    "phoneNumber": "1234567890",
                    "password": "Password@123"
                }
                """;
        mockMvc.perform(post("/api/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadUsers))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("newusersuser"));
    }

    @Test
    void loginWithUsernameSucceedsAndReturnsTokens() throws Exception {
        String loginPayload = """
                {
                    "username": "user1",
                    "password": "Password@123"
                }
                """;
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.message").value("Authentication successful"));
    }

    @Test
    void loginWithEmailSucceedsAndReturnsTokens() throws Exception {
        String loginPayload = """
                {
                    "username": "user1@example.com",
                    "password": "Password@123"
                }
                """;
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.message").value("Authentication successful"));
    }

    @Test
    void loginWithInvalidPasswordReturns401() throws Exception {
        String loginPayload = """
                {
                    "username": "user1",
                    "password": "WrongPassword"
                }
                """;
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginPayload))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid username/email or password"));
    }

    @Test
    void loginWithUnknownIdentifierReturns401() throws Exception {
        String loginPayload = """
                {
                    "username": "nonexistent@example.com",
                    "password": "Password@123"
                }
                """;
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginPayload))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid username/email or password"));
    }
}
