package com.farfartaxi.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.farfartaxi.backend.service.GoogleIdTokenService;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:farfartaxi_approval;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false",
    "app.jwt.secret=test-only-integration-secret-key-012345678901234567890123",
    "app.admin.email=admin@test.local",
    "app.admin.password=Admin123!Test",
    "app.admin.name=Admin Test"
})
class ApprovalSecurityIntegrationTest {
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
        new com.fasterxml.jackson.databind.ObjectMapper();

    @LocalServerPort
    private int port;

    @MockitoBean
    private GoogleIdTokenService googleIdTokenService;

    private static final Map<String, Object> RIDE = Map.of(
        "fromAddress", "Start", "fromLat", 59.3293, "fromLon", 18.0686,
        "toAddress", "Goal", "toLat", 59.3340, "toLon", 18.0700,
        "scheduledAt", "2030-01-01T10:00:00Z"
    );

    @Test
    void pendingUserIsBlockedUntilApprovedAndNeverGetsDriverEndpoints() throws Exception {
        JsonNode reg = send("POST", "/api/auth/register", null, Map.of(
            "email", "pending@test.local", "password", "Password123!", "fullName", "Pending"), 200);
        assertThat(reg.get("user").get("approved").asBoolean()).isFalse();

        JsonNode login = send("POST", "/api/auth/login", null, Map.of(
            "email", "pending@test.local", "password", "Password123!"), 200);
        assertThat(login.get("user").get("approved").asBoolean()).isFalse();
        String token = login.get("token").asText();

        JsonNode me = send("GET", "/api/auth/me", token, null, 200);
        assertThat(me.get("approved").asBoolean()).isFalse();

        assertPending(send("GET", "/api/rides/my", token, null, 403));
        assertPending(send("POST", "/api/rides", token, RIDE, 403));
        assertPending(send("GET", "/api/driver/rides/open", token, null, 403));
        assertPending(send("GET", "/api/saved-places", token, null, 403));

        String adminToken = loginToken("admin@test.local", "Admin123!Test");
        JsonNode approved = send("POST", "/api/admin/users/" + userId(adminToken, "pending@test.local") + "/approve",
            adminToken, null, 200);
        assertThat(approved.get("approved").asBoolean()).isTrue();

        send("GET", "/api/rides/my", token, null, 200);
        send("GET", "/api/driver/rides/open", token, null, 403);
        send("GET", "/api/admin/users", token, null, 403);
        send("GET", "/api/driver/stats", token, null, 403);
        send("GET", "/api/driver/rides/open", adminToken, null, 200);
        send("GET", "/api/driver/stats", adminToken, null, 200);
    }

    @Test
    void rideStreamDeniedForNonParticipant() throws Exception {
        String adminToken = loginToken("admin@test.local", "Admin123!Test");
        String a = approvedUserToken(adminToken, "owner@test.local");
        String b = approvedUserToken(adminToken, "stranger@test.local");
        long rideId = send("POST", "/api/rides", a, RIDE, 200).get("id").asLong();

        send("GET", "/api/rides/" + rideId + "/stream", b, null, 403);
        send("GET", "/api/rides/" + rideId, b, null, 403);
    }

    @Test
    void firstGoogleLoginCreatesPendingUser() throws Exception {
        when(googleIdTokenService.isConfigured()).thenReturn(true);
        when(googleIdTokenService.verify("cred")).thenReturn(Optional.of(
            new GoogleIdTokenService.GoogleProfile("sub-123", "g-user@test.local", "G User", true)));
        JsonNode res = send("POST", "/api/auth/google", null, Map.of("credential", "cred"), 200);
        assertThat(res.get("user").get("approved").asBoolean()).isFalse();
        assertPending(send("GET", "/api/rides/my", res.get("token").asText(), null, 403));
    }

    @Test
    void bookingForPendingPassengerIsRejectedAndPendingUsersNotListed() throws Exception {
        String adminToken = loginToken("admin@test.local", "Admin123!Test");
        send("POST", "/api/auth/register", null, Map.of(
            "email", "pax-pending@test.local", "password", "Password123!", "fullName", "Pax Pending"), 200);
        long pendingId = userId(adminToken, "pax-pending@test.local");

        String driverToken = approvedUserToken(adminToken, "drv@test.local");
        send("POST", "/api/admin/users/" + userId(adminToken, "drv@test.local") + "/promote-driver", adminToken, null, 200);

        Map<String, Object> body = new java.util.HashMap<>(RIDE);
        body.put("passengerUserId", pendingId);
        send("POST", "/api/rides", adminToken, body, 400);
        send("POST", "/api/rides", driverToken, body, 400);

        for (String token : new String[] {adminToken, driverToken}) {
            for (JsonNode u : send("GET", "/api/users/for-booking", token, null, 200)) {
                assertThat(u.get("email").asText()).isNotEqualToIgnoringCase("pax-pending@test.local");
            }
        }
    }

    @Test
    void googleSignInOnPendingLocalAccountStaysPendingAndClearsPassword() throws Exception {
        send("POST", "/api/auth/register", null, Map.of(
            "email", "hijack@test.local", "password", "Password123!", "fullName", "Attacker"), 200);
        when(googleIdTokenService.isConfigured()).thenReturn(true);
        when(googleIdTokenService.verify("cred-h")).thenReturn(Optional.of(
            new GoogleIdTokenService.GoogleProfile("sub-hijack", "hijack@test.local", "Real Person", true)));
        JsonNode res = send("POST", "/api/auth/google", null, Map.of("credential", "cred-h"), 200);
        assertThat(res.get("user").get("approved").asBoolean()).isFalse();
        assertThat(res.get("user").get("hasLocalPassword").asBoolean()).isFalse();
        assertPending(send("GET", "/api/rides/my", res.get("token").asText(), null, 403));

        send("POST", "/api/auth/login", null, Map.of("email", "hijack@test.local", "password", "Password123!"), 401);

        String adminToken = loginToken("admin@test.local", "Admin123!Test");
        send("POST", "/api/admin/users/" + userId(adminToken, "hijack@test.local") + "/approve", adminToken, null, 200);
        send("POST", "/api/auth/login", null, Map.of("email", "hijack@test.local", "password", "Password123!"), 401);
        send("GET", "/api/rides/my", res.get("token").asText(), null, 200);
    }

    @Test
    void preLinkAttackerTokenIsRevokedWhenVictimLinksGoogle() throws Exception {
        String attackerToken = send("POST", "/api/auth/register", null, Map.of(
            "email", "victim@test.local", "password", "Password123!", "fullName", "Attacker"), 200)
            .get("token").asText();
        send("GET", "/api/auth/me", attackerToken, null, 200);
        Thread.sleep(1100); // JWT iat has second precision

        when(googleIdTokenService.isConfigured()).thenReturn(true);
        when(googleIdTokenService.verify("cred-v")).thenReturn(Optional.of(
            new GoogleIdTokenService.GoogleProfile("sub-victim", "victim@test.local", "Victim", true)));
        String victimToken = send("POST", "/api/auth/google", null, Map.of("credential", "cred-v"), 200)
            .get("token").asText();

        send("POST", "/api/auth/set-password", attackerToken, Map.of("newPassword", "Attacker123!"), 401);
        send("GET", "/api/auth/me", attackerToken, null, 401);
        send("GET", "/api/auth/me", victimToken, null, 200);

        String adminToken = loginToken("admin@test.local", "Admin123!Test");
        send("POST", "/api/admin/users/" + userId(adminToken, "victim@test.local") + "/approve", adminToken, null, 200);
        send("GET", "/api/rides/my", attackerToken, null, 401);
        send("GET", "/api/rides/my", victimToken, null, 200);
        send("POST", "/api/auth/login", null, Map.of("email", "victim@test.local", "password", "Attacker123!"), 401);
    }

    @Test
    void changePasswordRevokesOldTokenAndReturnsWorkingNewOne() throws Exception {
        String adminToken = loginToken("admin@test.local", "Admin123!Test");
        String oldToken = approvedUserToken(adminToken, "changer@test.local");
        Thread.sleep(1100);
        JsonNode res = send("POST", "/api/auth/change-password", oldToken, Map.of(
            "oldPassword", "Password123!", "newPassword", "NewPassword123!"), 200);
        String newToken = res.get("token").asText();
        send("GET", "/api/rides/my", oldToken, null, 401);
        send("GET", "/api/rides/my", newToken, null, 200);
        send("POST", "/api/auth/login", null, Map.of("email", "changer@test.local", "password", "NewPassword123!"), 200);
    }

    @Test
    void adminCanRejectPendingUserWhoThenCannotLogIn() throws Exception {
        send("POST", "/api/auth/register", null, Map.of(
            "email", "reject-me@test.local", "password", "Password123!", "fullName", "Reject Me"), 200);
        String adminToken = loginToken("admin@test.local", "Admin123!Test");
        send("DELETE", "/api/admin/users/" + userId(adminToken, "reject-me@test.local"), adminToken, null, 200);
        send("POST", "/api/auth/login", null, Map.of("email", "reject-me@test.local", "password", "Password123!"), 401);
    }

    private String approvedUserToken(String adminToken, String email) throws Exception {
        send("POST", "/api/auth/register", null, Map.of(
            "email", email, "password", "Password123!", "fullName", email), 200);
        send("POST", "/api/admin/users/" + userId(adminToken, email) + "/approve", adminToken, null, 200);
        return loginToken(email, "Password123!");
    }

    private void assertPending(JsonNode body) {
        assertThat(body.get("code").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(body.get("error").asText()).isEqualTo("Account awaiting approval");
    }

    private String loginToken(String email, String password) throws Exception {
        return send("POST", "/api/auth/login", null, Map.of("email", email, "password", password), 200)
            .get("token").asText();
    }

    private long userId(String adminToken, String email) throws Exception {
        for (JsonNode u : send("GET", "/api/admin/users", adminToken, null, 200)) {
            if (email.equalsIgnoreCase(u.get("email").asText())) {
                return u.get("id").asLong();
            }
        }
        throw new IllegalStateException("User not found: " + email);
    }

    private JsonNode send(String method, String path, String token, Object payload, int expectedStatus) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json");
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        HttpRequest.BodyPublisher body = payload == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload));
        HttpResponse<String> response = HttpClient.newHttpClient().send(b.method(method, body).build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(method + " " + path + " -> " + response.body()).isEqualTo(expectedStatus);
        if (response.body() == null || response.body().isBlank()) {
            return objectMapper.createObjectNode();
        }
        return objectMapper.readTree(response.body());
    }
}
