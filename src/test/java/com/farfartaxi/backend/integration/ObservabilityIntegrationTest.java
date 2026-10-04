package com.farfartaxi.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.farfartaxi.backend.observability.AppMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:farfartaxi_obs;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false",
    "app.jwt.secret=test-only-integration-secret-key-012345678901234567890123",
    "app.admin.email=admin@test.local",
    "app.admin.password=Admin123!Test",
    "app.admin.name=Admin Test",
    "management.server.port=0",
    "app.management.prometheus-password=scrape-secret-123",
    "management.defaults.metrics.export.enabled=true",
    "management.prometheus.metrics.export.enabled=true",
    "APP_VERSION=1.2.3",
    "GIT_SHA=abc123"
})
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class ObservabilityIntegrationTest {
    @LocalServerPort
    private int port;

    @LocalManagementPort
    private int mgmtPort;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private AppMetrics metrics;

    private static HttpResponse<String> getAuth(String url, String user, String password) throws Exception {
        String basic = java.util.Base64.getEncoder().encodeToString((user + ":" + password).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Basic " + basic)
            .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(String url, String requestId) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).GET();
        if (requestId != null) {
            b.header("X-Request-Id", requestId);
        }
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void actuatorNotOnAppPort() throws Exception {
        assertThat(mgmtPort).isNotEqualTo(port);
        HttpResponse<String> r = get("http://localhost:" + port + "/actuator/prometheus", null);
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(r.body()).doesNotContain("jvm_");
        assertThat(get("http://localhost:" + port + "/actuator/info", null).statusCode()).isEqualTo(404);
    }

    @Test
    void prometheusOnManagementPort() throws Exception {
        metrics.placesSearch("SL", "ok", 1_000_000L, false);
        metrics.rideTransition("BOOKED", false);
        metrics.pushSent(true);
        HttpResponse<String> r = getAuth("http://localhost:" + mgmtPort + "/actuator/prometheus", "prometheus", "scrape-secret-123");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.body()).contains("farfartaxi_places_search_seconds_count{outcome=\"ok\",provider=\"SL\",world=\"real\"");
        assertThat(r.body()).contains("farfartaxi_ride_transitions_total{type=\"BOOKED\"");
        assertThat(r.body()).contains("farfartaxi_push_total{kind=\"unknown\",outcome=\"ok\",world=\"real\"");
        assertThat(registry.find("farfartaxi.places.search").tag("outcome", "ok").timer()).isNotNull();
    }

    @Test
    void prometheusRequiresBasicAuth() throws Exception {
        String url = "http://localhost:" + mgmtPort + "/actuator/prometheus";
        HttpResponse<String> anon = get(url, null);
        assertThat(anon.statusCode()).isEqualTo(401);
        assertThat(anon.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(h -> assertThat(h).startsWith("Basic"));
        assertThat(anon.body()).doesNotContain("jvm_");
        assertThat(getAuth(url, "prometheus", "wrong").statusCode()).isEqualTo(401);
        assertThat(getAuth(url, "admin", "scrape-secret-123").statusCode()).isEqualTo(401);
        assertThat(getAuth(url, "prometheus", "scrape-secret-123").statusCode()).isEqualTo(200);
        // a JWT is not accepted on the management port, and the other actuator endpoints stay unexposed
        assertThat(getAuth("http://localhost:" + mgmtPort + "/actuator/env", "prometheus", "scrape-secret-123").statusCode()).isIn(401, 403, 404);
    }

    @Test
    void newMetricsAreExportedInPrometheusFormat() throws Exception {
        metrics.rideTimeToAccept(java.time.Duration.ofSeconds(75), false);
        metrics.ridePickupWait(java.time.Duration.ofSeconds(200), false);
        metrics.rideNoDriver(false);
        metrics.appEvent("booking_created", false);
        String body = getAuth("http://localhost:" + mgmtPort + "/actuator/prometheus", "prometheus", "scrape-secret-123").body();
        assertThat(body)
            .contains("farfartaxi_ride_time_to_accept_seconds_bucket{kind=\"unknown\",world=\"real\",le=\"")
            .contains("farfartaxi_ride_time_to_accept_seconds_count{kind=\"unknown\",world=\"real\"}")
            .contains("farfartaxi_ride_pickup_wait_seconds_bucket{world=\"real\",le=\"")
            .contains("farfartaxi_ride_no_driver_total{world=\"real\"}")
            .contains("farfartaxi_push_subscriptions{world=\"real\"}")
            .contains("farfartaxi_push_subscriptions{world=\"test\"}")
            .contains("farfartaxi_app_events_total{name=\"booking_created\",world=\"real\"}");
    }

    @Test
    void consoleLogsAreEcsJsonWithRequestIdAndUserId(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        String url = "http://localhost:" + port + "/api/public/version";
        get(url, "ecs-check-1");
        // an authenticated request carries userId in the MDC
        HttpResponse<String> login = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"admin@test.local\",\"password\":\"Admin123!Test\"}")).build(), HttpResponse.BodyHandlers.ofString());
        String token = new com.fasterxml.jackson.databind.ObjectMapper().readTree(login.body()).get("token").asText();
        HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/rides/my"))
            .header("Authorization", "Bearer " + token).header("X-Request-Id", "ecs-check-2").GET().build(), HttpResponse.BodyHandlers.ofString());
        var om = new com.fasterxml.jackson.databind.ObjectMapper();
        int jsonLines = 0;
        boolean sawReqId = false;
        boolean sawUserId = false;
        for (String line : output.getAll().split("\n")) {
            if (line.isBlank() || !line.startsWith("{")) {
                continue;
            }
            var node = om.readTree(line); // every structured log line must parse
            jsonLines++;
            assertThat(node.has("@timestamp")).isTrue();
            assertThat(node.path("log").path("level").asText()).isNotEmpty();
            assertThat(node.has("message")).isTrue();
            sawReqId |= "ecs-check-1".equals(node.path("requestId").asText());
            sawUserId |= "ecs-check-2".equals(node.path("requestId").asText()) && node.hasNonNull("userId");
        }
        assertThat(jsonLines).isGreaterThan(0);
        assertThat(sawReqId).as("requestId field in ECS output").isTrue();
        assertThat(sawUserId).as("userId field in ECS output").isTrue();
    }

    @Test
    void healthStaysOpenOnManagementPort() throws Exception {
        assertThat(get("http://localhost:" + mgmtPort + "/actuator/health", null).statusCode()).isEqualTo(200);
        assertThat(get("http://localhost:" + mgmtPort + "/actuator/health/liveness", null).statusCode()).isEqualTo(200);
    }

    @Test
    void healthHasNoDetails() throws Exception {
        HttpResponse<String> r = get("http://localhost:" + mgmtPort + "/actuator/health", null);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).doesNotContain("components");
    }

    @Test
    void requestIdEchoedOrGenerated() throws Exception {
        String url = "http://localhost:" + port + "/api/public/version";
        assertThat(get(url, "abc-123.X_y").headers().firstValue("X-Request-Id")).contains("abc-123.X_y");
        String generated = get(url, "bad id with spaces!").headers().firstValue("X-Request-Id").orElseThrow();
        assertThat(generated).matches("[0-9a-f]{12}");
        assertThat(get(url, "a".repeat(65)).headers().firstValue("X-Request-Id").orElseThrow()).hasSize(12);
        assertThat(get(url, null).headers().firstValue("X-Request-Id")).isPresent();
    }

    @Test
    void versionEndpoint() throws Exception {
        HttpResponse<String> r = get("http://localhost:" + port + "/api/public/version", null);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("\"version\":\"1.2.3\"").contains("\"commit\":\"abc123\"");
    }
}
