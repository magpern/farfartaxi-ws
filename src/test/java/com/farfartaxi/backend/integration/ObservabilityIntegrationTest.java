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
    "management.defaults.metrics.export.enabled=true",
    "management.prometheus.metrics.export.enabled=true",
    "APP_VERSION=1.2.3",
    "GIT_SHA=abc123"
})
class ObservabilityIntegrationTest {
    @LocalServerPort
    private int port;

    @LocalManagementPort
    private int mgmtPort;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private AppMetrics metrics;

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
        assertThat(r.statusCode()).isNotEqualTo(200);
        assertThat(r.body()).doesNotContain("jvm_");
        assertThat(get("http://localhost:" + port + "/actuator/info", null).statusCode()).isNotEqualTo(200);
    }

    @Test
    void prometheusOnManagementPort() throws Exception {
        metrics.geocodeSearch("ok", 1_000_000L);
        metrics.rideTransition("BOOKED");
        metrics.pushSent(true);
        HttpResponse<String> r = get("http://localhost:" + mgmtPort + "/actuator/prometheus", null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.body()).contains("farfartaxi_geocode_search_seconds_count{outcome=\"ok\"");
        assertThat(r.body()).contains("farfartaxi_ride_transitions_total{type=\"BOOKED\"");
        assertThat(r.body()).contains("farfartaxi_push_total{outcome=\"ok\"");
        assertThat(registry.find("farfartaxi.geocode.search").tag("outcome", "ok").timer()).isNotNull();
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
