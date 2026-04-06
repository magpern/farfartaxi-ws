package com.farfartaxi.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:farfartaxi;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false",
    "app.jwt.secret=integration-test-secret-key-012345678901234567890123",
    "app.admin.email=admin@test.local",
    "app.admin.password=Admin123!Test",
    "app.admin.name=Admin Test"
})
class RideFlowIntegrationTest {
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
        new com.fasterxml.jackson.databind.ObjectMapper();

    @LocalServerPort
    private int port;

    @Test
    void fullRideFlowWorks() throws Exception {
        register("user@test.local", "Password123!", "Regular User");
        register("driver@test.local", "Password123!", "Driver User");

        String adminToken = login("admin@test.local", "Admin123!Test");
        Long driverId = getUserIdByEmail(adminToken, "driver@test.local");

        JsonNode promote = postWithAuth("/api/admin/users/" + driverId + "/promote-driver", adminToken, null, 200);
        assertThat(promote.get("role").asText()).isEqualTo("DRIVER");

        String userToken = login("user@test.local", "Password123!");
        String driverToken = login("driver@test.local", "Password123!");

        JsonNode booked = postWithAuth("/api/rides", userToken, Map.of(
            "fromAddress", "Start",
            "fromLat", 59.3293,
            "fromLon", 18.0686,
            "toAddress", "Goal",
            "toLat", 59.3340,
            "toLon", 18.0700,
            "scheduledAt", "2030-01-01T10:00:00Z"
        ), 200);
        Long rideId = booked.get("id").asLong();

        JsonNode accept = postWithAuth("/api/driver/rides/" + rideId + "/accept", driverToken, null, 200);
        assertThat(accept.get("status").asText()).isEqualTo("ACCEPTED");

        JsonNode start = postWithAuth("/api/driver/rides/" + rideId + "/start", driverToken, null, 200);
        assertThat(start.get("status").asText()).isEqualTo("IN_PROGRESS");

        JsonNode locationNode = postWithAuth("/api/driver/rides/" + rideId + "/location", driverToken, Map.of("lat", 59.33, "lon", 18.07), 200);
        assertThat(locationNode.get("etaMinutes").asInt()).isGreaterThan(0);

        JsonNode complete = postWithAuth("/api/driver/rides/" + rideId + "/complete", driverToken, null, 200);
        assertThat(complete.get("status").asText()).isEqualTo("COMPLETED");

        postWithAuth("/api/rides/" + rideId + "/feedback", userToken, Map.of("stars", 5, "comment", "Great ride"), 200);
    }

    @Test
    void passengerCanDeleteCancelledOrRejectedRides() throws Exception {
        register("passenger-del@test.local", "Password123!", "Passenger Del");
        register("driver-del@test.local", "Password123!", "Driver Del");

        String adminToken = login("admin@test.local", "Admin123!Test");
        Long driverId = getUserIdByEmail(adminToken, "driver-del@test.local");
        postWithAuth("/api/admin/users/" + driverId + "/promote-driver", adminToken, null, 200);

        String userToken = login("passenger-del@test.local", "Password123!");
        String driverToken = login("driver-del@test.local", "Password123!");

        JsonNode rejectedBooked = postWithAuth("/api/rides", userToken, Map.of(
            "fromAddress", "A",
            "fromLat", 59.3293,
            "fromLon", 18.0686,
            "toAddress", "B",
            "toLat", 59.3340,
            "toLon", 18.0700,
            "scheduledAt", "2031-06-01T12:00:00Z"
        ), 200);
        long rejectedRideId = rejectedBooked.get("id").asLong();

        JsonNode refused = postWithAuth("/api/driver/rides/" + rejectedRideId + "/refuse", driverToken, Map.of("comment", "busy"), 200);
        assertThat(refused.get("status").asText()).isEqualTo("REJECTED");

        deleteWithAuth("/api/rides/" + rejectedRideId, userToken, 200);
        assertThat(listMyRideIds(userToken, false)).doesNotContain(rejectedRideId);

        JsonNode cancelledBooked = postWithAuth("/api/rides", userToken, Map.of(
            "fromAddress", "C",
            "fromLat", 59.3293,
            "fromLon", 18.0686,
            "toAddress", "D",
            "toLat", 59.3340,
            "toLon", 18.0700,
            "scheduledAt", "2031-06-02T12:00:00Z"
        ), 200);
        long cancelledRideId = cancelledBooked.get("id").asLong();

        JsonNode cancelled = postWithAuth("/api/rides/" + cancelledRideId + "/cancel", userToken, Map.of("reason", "changed plans"), 200);
        assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");

        deleteWithAuth("/api/rides/" + cancelledRideId, userToken, 200);
        assertThat(listMyRideIds(userToken, false)).doesNotContain(cancelledRideId);
    }

    private void register(String email, String password, String fullName) throws Exception {
        postJson("/api/auth/register", null, Map.of(
            "email", email,
            "password", password,
            "fullName", fullName
        ), 200);
    }

    private String login(String email, String password) throws Exception {
        JsonNode response = postJson("/api/auth/login", null, Map.of(
            "email", email,
            "password", password
        ), 200);
        return response.get("token").asText();
    }

    private Long getUserIdByEmail(String adminToken, String email) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + "/api/admin/users"))
            .header("Authorization", "Bearer " + adminToken)
            .GET()
            .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode users = objectMapper.readTree(response.body());
        for (JsonNode user : users) {
            if (email.equalsIgnoreCase(user.get("email").asText())) {
                return user.get("id").asLong();
            }
        }
        throw new IllegalStateException("User not found in admin list: " + email);
    }

    private JsonNode postWithAuth(String path, String token, Object payload, int expectedStatus) throws Exception {
        return postJson(path, token, payload, expectedStatus);
    }

    private JsonNode postJson(String path, String bearerToken, Object payload, int expectedStatus) throws Exception {
        String json = payload == null ? "" : objectMapper.writeValueAsString(payload);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + path))
            .header("Content-Type", "application/json");
        if (bearerToken != null && !bearerToken.isBlank()) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        HttpRequest request = payload == null
            ? builder.POST(HttpRequest.BodyPublishers.noBody()).build()
            : builder.POST(HttpRequest.BodyPublishers.ofString(json)).build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(expectedStatus);
        if (response.body() == null || response.body().isBlank()) {
            return objectMapper.createObjectNode();
        }
        return objectMapper.readTree(response.body());
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private void deleteWithAuth(String path, String token, int expectedStatus) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + path))
            .header("Authorization", "Bearer " + token)
            .DELETE()
            .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(expectedStatus);
    }

    private List<Long> listMyRideIds(String userToken, boolean history) throws Exception {
        String q = history ? "history=true" : "history=false";
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl() + "/api/rides/my?" + q))
            .header("Authorization", "Bearer " + userToken)
            .GET()
            .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode arr = objectMapper.readTree(response.body());
        List<Long> ids = new ArrayList<>();
        for (JsonNode n : arr) {
            ids.add(n.get("id").asLong());
        }
        return ids;
    }
}
