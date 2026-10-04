package com.farfartaxi.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.test.context.TestPropertySource;

/** Fail closed: without MANAGEMENT_PROMETHEUS_PASSWORD nobody can read /actuator/prometheus; health stays open. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:farfartaxi_mgmt_nopw;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false",
    "app.jwt.secret=test-only-integration-secret-key-012345678901234567890123",
    "app.admin.email=admin@test.local",
    "app.admin.password=Admin123!Test",
    "app.admin.name=Admin Test",
    "management.server.port=0",
    "app.management.prometheus-password="
})
class ManagementPasswordUnsetIntegrationTest {
    @LocalManagementPort
    private int mgmtPort;

    private int status(String path, String basic) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + mgmtPort + path)).GET();
        if (basic != null) {
            b.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(basic.getBytes()));
        }
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test
    void prometheusDeniedForEveryone() throws Exception {
        assertThat(status("/actuator/prometheus", null)).isEqualTo(401);
        assertThat(status("/actuator/prometheus", "prometheus:")).isEqualTo(401);
        assertThat(status("/actuator/prometheus", "prometheus:anything")).isEqualTo(401);
        assertThat(status("/actuator/health", null)).isEqualTo(200);
    }
}
