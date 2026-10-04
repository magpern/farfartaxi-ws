package com.farfartaxi.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/** Production-default auth limits (the shared test properties raise them), keyed by client IP / email. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:farfartaxi_authrl;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false",
    "app.jwt.secret=test-only-integration-secret-key-012345678901234567890123",
    "app.admin.email=admin@test.local",
    "app.admin.password=Admin123!Test",
    "app.admin.name=Admin Test",
    "app.rides.scheduler-enabled=false",
    "app.ratelimit.auth.register-per-hour=5",
    "app.ratelimit.auth.login-per-15min=60",
    "app.ratelimit.auth.login-failed-per-email-15min=10",
    "app.ratelimit.auth.google-per-15min=30",
    "app.ratelimit.auth.forgot-per-hour=5",
    "farfartaxi.test.passenger-password=TestPass123!",
    "farfartaxi.test.driver-password=TestDrive123!"
})
class AuthRateLimitIntegrationTest {
    private final ObjectMapper om = new ObjectMapper();
    @LocalServerPort private int port;

    private HttpResponse<String> post(String path, Object body, String ip) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json").header("CF-Connecting-IP", ip)
            .POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(body)));
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertLimited(HttpResponse<String> r) throws Exception {
        assertThat(r.statusCode()).isEqualTo(429);
        JsonNode j = om.readTree(r.body());
        assertThat(j.get("code").asText()).isEqualTo("RATE_LIMITED");
        assertThat(j.get("error").asText()).isEqualTo("För många försök, vänta en stund");
        assertThat(Long.parseLong(r.headers().firstValue("Retry-After").orElseThrow())).isBetween(1L, 3600L);
    }

    @Test
    void registerIsLimitedTo5PerHourPerIp() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(post("/api/auth/register", Map.of("email", "rl" + i + "@test.local", "password", "Password123!", "fullName", "R"), "198.51.100.1").statusCode()).isEqualTo(200);
        }
        assertLimited(post("/api/auth/register", Map.of("email", "rl9@test.local", "password", "Password123!", "fullName", "R"), "198.51.100.1"));
        // another IP is unaffected
        assertThat(post("/api/auth/register", Map.of("email", "rl10@test.local", "password", "Password123!", "fullName", "R"), "198.51.100.2").statusCode()).isEqualTo(200);
    }

    @Test
    void loginIsLimitedPerIp() throws Exception {
        for (int i = 0; i < 60; i++) {
            assertThat(post("/api/auth/login", Map.of("email", "admin@test.local", "password", "Admin123!Test"), "198.51.100.10").statusCode()).isEqualTo(200);
        }
        assertLimited(post("/api/auth/login", Map.of("email", "admin@test.local", "password", "Admin123!Test"), "198.51.100.10"));
        assertThat(post("/api/auth/login", Map.of("email", "admin@test.local", "password", "Admin123!Test"), "198.51.100.11").statusCode()).isEqualTo(200);
    }

    @Test
    void failedLoginsAreLimitedPerEmailAcrossIpsAndSuccessResets() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertThat(post("/api/auth/login", Map.of("email", "Victim@test.local", "password", "wrong"), "198.51.100." + (20 + i)).statusCode()).isEqualTo(401);
        }
        assertLimited(post("/api/auth/login", Map.of("email", "victim@test.local", "password", "wrong"), "198.51.100.40"));
        // a different email from the same IP is fine, and a success clears that email's failures
        for (int i = 0; i < 9; i++) {
            post("/api/auth/login", Map.of("email", "test-passenger@farfartaxi.invalid", "password", "nope"), "198.51.100.50");
        }
        assertThat(post("/api/auth/login", Map.of("email", "test-passenger@farfartaxi.invalid", "password", "TestPass123!"), "198.51.100.50").statusCode()).isEqualTo(200);
        for (int i = 0; i < 9; i++) {
            assertThat(post("/api/auth/login", Map.of("email", "test-passenger@farfartaxi.invalid", "password", "nope"), "198.51.100.50").statusCode()).isEqualTo(401);
        }
    }

    @Test
    void googleAndForgotPasswordAreLimited() throws Exception {
        for (int i = 0; i < 30; i++) {
            assertThat(post("/api/auth/google", Map.of("credential", "x"), "198.51.100.60").statusCode()).isNotEqualTo(429);
        }
        assertLimited(post("/api/auth/google", Map.of("credential", "x"), "198.51.100.60"));
        for (int i = 0; i < 5; i++) {
            assertThat(post("/api/auth/forgot-password", Map.of("email", "a@test.local"), "198.51.100.61").statusCode()).isEqualTo(200);
        }
        assertLimited(post("/api/auth/forgot-password", Map.of("email", "a@test.local"), "198.51.100.61"));
    }
}
