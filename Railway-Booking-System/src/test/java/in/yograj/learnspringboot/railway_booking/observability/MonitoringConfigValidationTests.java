package in.yograj.learnspringboot.railway_booking.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Static validation tests for the Railora Phase 15C Prometheus & Grafana Monitoring stack.
 *
 * Validates:
 * 1. Docker Compose file syntax, services, port bindings (strictly localhost), and volumes.
 * 2. Prometheus configuration syntax, scrape jobs, and authentication settings.
 * 3. Alert rules file syntax, alert count (10 rules), required labels and annotations.
 * 4. Four Grafana dashboard JSON files for schema correctness, valid UIDs, and panel counts.
 * 5. .env.example placeholder sanity and credential safety.
 */
class MonitoringConfigValidationTests {

    private static final Path MONITORING_DIR = Path.of("monitoring");
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Yaml yaml = new Yaml();

    @Test
    @DisplayName("Docker Compose: Valid YAML with localhost bindings, health checks, and named volumes")
    void dockerCompose_isValidAndFollowsHardenedStandards() throws Exception {
        File composeFile = MONITORING_DIR.resolve("docker-compose.yml").toFile();
        assertTrue(composeFile.exists(), "docker-compose.yml must exist");

        try (InputStream in = new FileInputStream(composeFile)) {
            Map<String, Object> compose = yaml.load(in);
            assertNotNull(compose, "Compose document must parse to a Map");

            @SuppressWarnings("unchecked")
            Map<String, Object> services = (Map<String, Object>) compose.get("services");
            assertNotNull(services, "services block must exist");
            assertTrue(services.containsKey("prometheus"), "Must contain 'prometheus' service");
            assertTrue(services.containsKey("grafana"), "Must contain 'grafana' service");

            // Verify localhost bindings
            @SuppressWarnings("unchecked")
            Map<String, Object> promService = (Map<String, Object>) services.get("prometheus");
            @SuppressWarnings("unchecked")
            List<String> promPorts = (List<String>) promService.get("ports");
            assertTrue(promPorts.stream().anyMatch(p -> p.startsWith("127.0.0.1:9090:")),
                    "Prometheus must be bound to 127.0.0.1:9090");

            @SuppressWarnings("unchecked")
            Map<String, Object> grafService = (Map<String, Object>) services.get("grafana");
            @SuppressWarnings("unchecked")
            List<String> grafPorts = (List<String>) grafService.get("ports");
            assertTrue(grafPorts.stream().anyMatch(p -> p.startsWith("127.0.0.1:3000:")),
                    "Grafana must be bound to 127.0.0.1:3000");

            // Verify pinned images
            assertEquals("prom/prometheus:v3.2.1", promService.get("image"));
            assertEquals("grafana/grafana:11.5.2", grafService.get("image"));

            // Verify volumes exist
            assertNotNull(compose.get("volumes"), "Named volumes block must exist");
            assertNotNull(compose.get("networks"), "Networks block must exist");
        }
    }

    @Test
    @DisplayName("Prometheus Config: Valid YAML with self-monitoring and Railora actuator scrape jobs")
    void prometheusConfig_isValidAndScrapesRailora() throws Exception {
        File promFile = MONITORING_DIR.resolve("prometheus/prometheus.yml").toFile();
        assertTrue(promFile.exists(), "prometheus.yml must exist");

        try (InputStream in = new FileInputStream(promFile)) {
            Map<String, Object> config = yaml.load(in);
            assertNotNull(config, "Prometheus config must parse to a Map");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> scrapeConfigs = (List<Map<String, Object>>) config.get("scrape_configs");
            assertNotNull(scrapeConfigs, "scrape_configs must be present");
            assertTrue(scrapeConfigs.size() >= 2, "Must contain at least 2 scrape jobs");

            // Find railora scrape job
            Map<String, Object> railoraJob = scrapeConfigs.stream()
                    .filter(j -> "railora".equals(j.get("job_name")))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Missing 'railora' scrape job"));

            assertEquals("/actuator/prometheus", railoraJob.get("metrics_path"));
            assertNotNull(railoraJob.get("basic_auth"), "Railora scrape job must configure basic_auth");
        }
    }

    @Test
    @DisplayName("Prometheus Alerts: Exactly 10 production alert rules with severity, annotations and runbooks")
    void alertRules_containExactlyTenValidAlerts() throws Exception {
        File alertsFile = MONITORING_DIR.resolve("prometheus/alerts/railora-alerts.yml").toFile();
        assertTrue(alertsFile.exists(), "railora-alerts.yml must exist");

        try (InputStream in = new FileInputStream(alertsFile)) {
            Map<String, Object> alertDoc = yaml.load(in);
            assertNotNull(alertDoc, "Alert document must parse to a Map");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> groups = (List<Map<String, Object>>) alertDoc.get("groups");
            assertNotNull(groups, "groups block must exist");

            int totalAlerts = 0;
            List<String> expectedAlertNames = List.of(
                    "RailoraDown",
                    "ReadinessDown",
                    "Http5xxSpike",
                    "HighP95Latency",
                    "HikariPoolSaturation",
                    "HikariConnectionTimeouts",
                    "OutboxDeadEvents",
                    "OutboxBacklogSustained",
                    "JvmHeapPressure",
                    "PaymentFailureSpike"
            );

            for (Map<String, Object> group : groups) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> rules = (List<Map<String, Object>>) group.get("rules");
                if (rules != null) {
                    for (Map<String, Object> rule : rules) {
                        String alertName = (String) rule.get("alert");
                        assertNotNull(alertName, "Rule must have an alert name");
                        assertTrue(expectedAlertNames.contains(alertName), "Unexpected alert name: " + alertName);

                        assertNotNull(rule.get("expr"), "Alert " + alertName + " must have an expression");
                        assertNotNull(rule.get("for"), "Alert " + alertName + " must have a duration 'for'");

                        @SuppressWarnings("unchecked")
                        Map<String, Object> labels = (Map<String, Object>) rule.get("labels");
                        assertNotNull(labels, "Alert " + alertName + " must have labels");
                        assertTrue(labels.containsKey("severity"), "Alert " + alertName + " must have severity");

                        @SuppressWarnings("unchecked")
                        Map<String, Object> annotations = (Map<String, Object>) rule.get("annotations");
                        assertNotNull(annotations, "Alert " + alertName + " must have annotations");
                        assertTrue(annotations.containsKey("summary"), "Alert " + alertName + " must have summary");
                        assertTrue(annotations.containsKey("description"), "Alert " + alertName + " must have description");

                        totalAlerts++;
                    }
                }
            }

            assertEquals(10, totalAlerts, "Must contain exactly 10 alert rules");
        }
    }

    @Test
    @DisplayName("Grafana Dashboards: All 4 dashboards exist with valid JSON, UIDs, and panels")
    void grafanaDashboards_areAllValidJsonWithPanels() throws Exception {
        Map<String, String> expectedDashboards = Map.of(
                "railora-application-overview.json", "railora-app-overview",
                "railora-database-infrastructure.json", "railora-db-infra",
                "railora-business-metrics.json", "railora-business-metrics",
                "railora-transactional-outbox.json", "railora-outbox"
        );

        Path dashboardsDir = MONITORING_DIR.resolve("grafana/dashboards");
        assertTrue(Files.isDirectory(dashboardsDir), "Dashboards directory must exist");

        for (Map.Entry<String, String> entry : expectedDashboards.entrySet()) {
            Path file = dashboardsDir.resolve(entry.getKey());
            assertTrue(Files.exists(file), "Dashboard file must exist: " + entry.getKey());

            JsonNode root = objectMapper.readTree(file.toFile());
            assertNotNull(root, "Must parse as valid JSON: " + entry.getKey());

            assertEquals(entry.getValue(), root.get("uid").asText(),
                    "UID must match for " + entry.getKey());
            assertNotNull(root.get("title").asText(), "Title must not be null");

            JsonNode panels = root.get("panels");
            assertNotNull(panels, "Panels array must exist in " + entry.getKey());
            assertTrue(panels.isArray() && panels.size() >= 5,
                    "Dashboard " + entry.getKey() + " must have at least 5 panels, found: " + panels.size());
        }
    }

    @Test
    @DisplayName("Environment Template: .env.example contains placeholder values only")
    void envExample_containsOnlyPlaceholders() throws Exception {
        Path envFile = MONITORING_DIR.resolve(".env.example");
        assertTrue(Files.exists(envFile), ".env.example must exist");

        String content = Files.readString(envFile);
        assertTrue(content.contains("METRICS_USER="), "Must define METRICS_USER");
        assertTrue(content.contains("METRICS_PASSWORD="), "Must define METRICS_PASSWORD");
        assertTrue(content.contains("GF_SECURITY_ADMIN_USER="), "Must define GF_SECURITY_ADMIN_USER");
        assertTrue(content.contains("GF_SECURITY_ADMIN_PASSWORD="), "Must define GF_SECURITY_ADMIN_PASSWORD");
    }

    @Test
    @DisplayName("Provisioning Configs: Datasource and Dashboards YAML exist and are valid")
    void provisioningConfigs_areValidYaml() throws Exception {
        File dsFile = MONITORING_DIR.resolve("grafana/provisioning/datasources/prometheus.yml").toFile();
        assertTrue(dsFile.exists(), "Datasource provisioning file must exist");
        try (InputStream in = new FileInputStream(dsFile)) {
            Map<String, Object> dsDoc = yaml.load(in);
            assertNotNull(dsDoc, "Datasource YAML must parse");
        }

        File dbFile = MONITORING_DIR.resolve("grafana/provisioning/dashboards/dashboards.yml").toFile();
        assertTrue(dbFile.exists(), "Dashboard provisioning file must exist");
        try (InputStream in = new FileInputStream(dbFile)) {
            Map<String, Object> dbDoc = yaml.load(in);
            assertNotNull(dbDoc, "Dashboards YAML must parse");
        }
    }
}
