package com.farfartaxi.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.farfartaxi.backend.config.TestAccountBootstrapConfig;
import com.farfartaxi.backend.model.Role;
import com.farfartaxi.backend.model.UserEntity;
import com.farfartaxi.backend.repo.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:farfartaxi_fence;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false",
    "app.jwt.secret=test-only-integration-secret-key-012345678901234567890123",
    "app.admin.email=admin@test.local",
    "app.admin.password=Admin123!Test",
    "app.admin.name=Admin Test",
    "farfartaxi.test.passenger-password=TestPass123!",
    "farfartaxi.test.driver-password=TestDrive123!"
})
class TestAccountAdminFenceIntegrationTest {
    private final ObjectMapper om = new ObjectMapper();
    @LocalServerPort private int port;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder encoder;

    @Test
    void roleChangeOnTestAccountIsRejected() throws Exception {
        String admin = login("admin@test.local", "Admin123!Test");
        long id = userRepository.findByEmailIgnoreCase(TestAccountBootstrapConfig.PASSENGER_EMAIL).orElseThrow().getId();
        for (String action : new String[] {"promote-admin", "promote-driver", "demote-user"}) {
            JsonNode res = send("POST", "/api/admin/users/" + id + "/" + action, admin, 400);
            assertThat(res.toString()).contains("Test accounts cannot change role");
        }
        assertThat(userRepository.findById(id).orElseThrow().getRole()).isEqualTo(Role.USER);
    }

    @Test
    void testAccountThatIsAdminCannotUseAdminEndpoints() throws Exception {
        UserEntity u = new UserEntity();
        u.setEmail("rogue-test-admin@farfartaxi.invalid");
        u.setFullName("Rogue");
        u.setRole(Role.ADMIN);
        u.setTest(true);
        u.setEnabled(true);
        u.setApproved(true);
        u.setMustChangePassword(false);
        u.setPasswordHash(encoder.encode("Rogue123!Pw"));
        userRepository.save(u);
        String token = login("rogue-test-admin@farfartaxi.invalid", "Rogue123!Pw");
        send("GET", "/api/admin/users", token, 403);
        send("POST", "/api/admin/users/" + u.getId() + "/approve", token, 403);
        send("DELETE", "/api/admin/rides/1", token, 403);
        // a real admin still works
        send("GET", "/api/admin/users", login("admin@test.local", "Admin123!Test"), 200);
    }

    private String login(String email, String password) throws Exception {
        return send("POST", "/api/auth/login", null, 200, Map.of("email", email, "password", password)).get("token").asText();
    }

    private JsonNode send(String method, String path, String token, int expected) throws Exception {
        return send(method, path, token, expected, null);
    }

    private JsonNode send(String method, String path, String token, int expected, Object payload) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json");
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        HttpRequest.BodyPublisher body = payload == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(om.writeValueAsString(payload));
        HttpResponse<String> res = HttpClient.newHttpClient().send(b.method(method, body).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).as(method + " " + path + " -> " + res.body()).isEqualTo(expected);
        return res.body() == null || res.body().isBlank() ? om.createObjectNode() : om.readTree(res.body());
    }
}
