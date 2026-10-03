package com.farfartaxi.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.farfartaxi.backend.model.RefreshTokenEntity;
import com.farfartaxi.backend.model.Role;
import com.farfartaxi.backend.model.UserEntity;
import com.farfartaxi.backend.repo.RefreshTokenRepository;
import com.farfartaxi.backend.repo.UserRepository;
import com.farfartaxi.backend.service.RefreshTokenCleanupJob;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:farfartaxi_refresh;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
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
class RefreshTokenIntegrationTest {
    static final String PW = "Passw0rd!Test";

    static class MutableClock extends Clock {
        volatile Instant now = Instant.now();
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean @Primary MutableClock testClock() { return new MutableClock(); }
    }

    private final ObjectMapper om = new ObjectMapper();
    @LocalServerPort private int port;
    @Autowired private UserRepository userRepository;
    @Autowired private RefreshTokenRepository tokenRepository;
    @Autowired private RefreshTokenCleanupJob cleanupJob;
    @Autowired private PasswordEncoder encoder;
    @Autowired private MutableClock clock;

    record Resp(int status, String body, List<String> setCookies) {
        JsonNode json() throws Exception { return new ObjectMapper().readTree(body); }
        String refreshCookie() {
            return setCookies.stream().filter(c -> c.startsWith("ft_refresh=")).findFirst().orElse(null);
        }
        String refreshValue() {
            String c = refreshCookie();
            return c == null ? null : c.substring("ft_refresh=".length(), c.indexOf(';'));
        }
    }

    @BeforeEach
    void resetClock() {
        clock.now = Instant.now();
    }

    private String newUser(boolean test, Role role) {
        String email = "u" + UUID.randomUUID() + "@farfartaxi.invalid";
        UserEntity u = new UserEntity();
        u.setEmail(email);
        u.setFullName("U");
        u.setRole(role);
        u.setTest(test);
        u.setEnabled(true);
        u.setApproved(true);
        u.setMustChangePassword(false);
        u.setPasswordHash(encoder.encode(PW));
        userRepository.save(u);
        return email;
    }

    private Resp login(String email, String pw) throws Exception {
        return send("/api/auth/login", null, null, Map.of("email", email, "password", pw));
    }

    private Resp refresh(String cookieValue) throws Exception {
        return send("/api/auth/refresh", null, cookieValue, null);
    }

    private Resp send(String path, String token, String cookieValue, Object payload) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json");
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        if (cookieValue != null) {
            b.header("Cookie", "ft_refresh=" + cookieValue);
        }
        HttpRequest.BodyPublisher body = payload == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(om.writeValueAsString(payload));
        HttpResponse<String> res = HttpClient.newHttpClient().send(b.POST(body).build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(res.statusCode(), res.body(), res.headers().allValues("Set-Cookie"));
    }

    private int statusWithToken(String token) throws Exception {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/api/auth/me"))
            .header("Authorization", "Bearer " + token).GET().build();
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test
    void loginSetsHardenedRefreshCookieAndNoRefreshInBody() throws Exception {
        Resp r = login(newUser(false, Role.USER), PW);
        assertThat(r.status()).isEqualTo(200);
        String c = r.refreshCookie();
        assertThat(c).isNotNull().contains("HttpOnly").contains("SameSite=Strict").contains("Path=/api/auth")
            .contains("Secure").contains("Max-Age=" + Duration.ofDays(90).toSeconds());
        assertThat(r.body()).doesNotContain(r.refreshValue());
        assertThat(r.json().fieldNames()).toIterable().containsExactlyInAnyOrder("token", "user");
        // only the hash is stored
        assertThat(tokenRepository.findByTokenHash(r.refreshValue())).isEmpty();
    }

    @Test
    void accessTokenLifetimeIsOneHour() throws Exception {
        Resp r = login(newUser(false, Role.USER), PW);
        String payload = new String(Base64.getUrlDecoder().decode(r.json().get("token").asText().split("\\.")[1]));
        JsonNode claims = om.readTree(payload);
        assertThat(claims.get("exp").asLong() - claims.get("iat").asLong()).isEqualTo(3600);
    }

    @Test
    void refreshRotatesRaceThenReuseRevokesFamily() throws Exception {
        Resp login = login(newUser(false, Role.USER), PW);
        String c1 = login.refreshValue();
        Resp r2 = refresh(c1);
        assertThat(r2.status()).isEqualTo(200);
        String c2 = r2.refreshValue();
        assertThat(c2).isNotNull().isNotEqualTo(c1);
        assertThat(r2.json().get("token").asText()).isNotBlank();
        assertThat(r2.refreshCookie()).contains("HttpOnly").contains("SameSite=Strict");
        assertThat(statusWithToken(r2.json().get("token").asText())).isEqualTo(200);
        // same family, expiry renewed
        RefreshTokenEntity old = tokenRepository.findAll().stream().filter(t -> t.getReplacedAt() != null).findFirst().orElseThrow();
        assertThat(old.getReplacedAt()).isNotNull();

        // within grace: RACE, cookie untouched, nothing revoked
        Resp race = refresh(c1);
        assertThat(race.status()).isEqualTo(401);
        assertThat(race.json().get("code").asText()).isEqualTo("REFRESH_RACE");
        assertThat(race.refreshCookie()).isNull();
        assertThat(refresh(c2).status()).isEqualTo(200); // c2 still fine (rotates to c3)

        // after grace: reuse -> family revoked
        clock.now = clock.now.plusSeconds(31);
        Resp reuse = refresh(c1);
        assertThat(reuse.status()).isEqualTo(401);
        assertThat(reuse.json().get("code").asText()).isEqualTo("REFRESH_INVALID");
        assertThat(reuse.refreshCookie()).contains("Max-Age=0");
        assertThat(refresh(c2).json().get("code").asText()).isEqualTo("REFRESH_INVALID");
    }

    @Test
    void concurrentRefreshOfSameTokenYieldsExactlyOneSuccess() throws Exception {
        String c = login(newUser(false, Role.USER), PW).refreshValue();
        int n = 6;
        ExecutorService ex = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Resp>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            fs.add(ex.submit(() -> { go.await(); return refresh(c); }));
        }
        go.countDown();
        int ok = 0;
        for (Future<Resp> f : fs) {
            Resp r = f.get();
            if (r.status() == 200) {
                ok++;
            } else {
                assertThat(r.status()).isEqualTo(401);
            }
        }
        ex.shutdown();
        assertThat(ok).isEqualTo(1);
    }

    @Test
    void missingUnknownAndExpiredCookiesAreInvalid() throws Exception {
        Resp none = refresh(null);
        assertThat(none.status()).isEqualTo(401);
        assertThat(none.json().get("code").asText()).isEqualTo("REFRESH_INVALID");
        assertThat(none.refreshCookie()).contains("Max-Age=0");
        assertThat(refresh("bogus").json().get("code").asText()).isEqualTo("REFRESH_INVALID");
        String c = login(newUser(false, Role.USER), PW).refreshValue();
        clock.now = clock.now.plus(Duration.ofDays(91));
        assertThat(refresh(c).json().get("code").asText()).isEqualTo("REFRESH_INVALID");
    }

    @Test
    void logoutRevokesAndClearsCookie() throws Exception {
        String c = login(newUser(false, Role.USER), PW).refreshValue();
        Resp out = send("/api/auth/logout", null, c, null);
        assertThat(out.status()).isEqualTo(204);
        assertThat(out.refreshCookie()).contains("Max-Age=0");
        assertThat(refresh(c).status()).isEqualTo(401);
        assertThat(send("/api/auth/logout", null, null, null).status()).isEqualTo(204);
    }

    @Test
    void changePasswordRevokesOthersAndNewCookieWorks() throws Exception {
        String email = newUser(false, Role.USER);
        Resp a = login(email, PW);
        Resp b = login(email, PW);
        Resp change = send("/api/auth/change-password", a.json().get("token").asText(), null,
            Map.of("oldPassword", PW, "newPassword", "NewPassw0rd!2"));
        assertThat(change.status()).isEqualTo(200);
        String fresh = change.refreshValue();
        assertThat(fresh).isNotNull();
        assertThat(refresh(a.refreshValue()).status()).isEqualTo(401);
        assertThat(refresh(b.refreshValue()).status()).isEqualTo(401);
        assertThat(refresh(fresh).status()).isEqualTo(200);
    }

    @Test
    void adminLogoutEverywhereKillsRefreshAndAccessTokens() throws Exception {
        String email = newUser(false, Role.USER);
        Resp u = login(email, PW);
        long uid = userRepository.findByEmailIgnoreCase(email).orElseThrow().getId();
        String admin = login("admin@test.local", "Admin123!Test").json().get("token").asText();
        assertThat(statusWithToken(u.json().get("token").asText())).isEqualTo(200);
        Resp r = send("/api/admin/users/" + uid + "/logout-everywhere", admin, null, null);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().get("id").asLong()).isEqualTo(uid);
        assertThat(refresh(u.refreshValue()).status()).isEqualTo(401);
        assertThat(statusWithToken(u.json().get("token").asText())).isEqualTo(401);
        // user can log in again
        assertThat(login(email, PW).status()).isEqualTo(200);
    }

    @Test
    void testAdminCannotLogoutEverywhere() throws Exception {
        String email = newUser(true, Role.ADMIN);
        String token = login(email, PW).json().get("token").asText();
        long uid = userRepository.findByEmailIgnoreCase(email).orElseThrow().getId();
        assertThat(send("/api/admin/users/" + uid + "/logout-everywhere", token, null, null).status()).isEqualTo(403);
    }

    @Test
    void disabledUserCannotRefresh() throws Exception {
        String email = newUser(false, Role.USER);
        String c = login(email, PW).refreshValue();
        UserEntity u = userRepository.findByEmailIgnoreCase(email).orElseThrow();
        u.setEnabled(false);
        userRepository.save(u);
        Resp r = refresh(c);
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.json().get("code").asText()).isEqualTo("REFRESH_INVALID");
    }

    @Test
    void cleanupDeletesOnlyOldTokens() {
        Long uid = userRepository.findByEmailIgnoreCase("admin@test.local").orElseThrow().getId();
        Instant now = Instant.now();
        RefreshTokenEntity keepActive = tok(uid, "k1", now.plus(Duration.ofDays(30)), null, null);
        RefreshTokenEntity keepRecentExpired = tok(uid, "k2", now.minus(Duration.ofDays(2)), null, null);
        RefreshTokenEntity keepRecentRevoked = tok(uid, "k3", now.plus(Duration.ofDays(30)), now.minus(Duration.ofDays(2)), null);
        RefreshTokenEntity delExpired = tok(uid, "d1", now.minus(Duration.ofDays(8)), null, null);
        RefreshTokenEntity delRevoked = tok(uid, "d2", now.plus(Duration.ofDays(30)), now.minus(Duration.ofDays(8)), null);
        RefreshTokenEntity delReplaced = tok(uid, "d3", now.plus(Duration.ofDays(30)), null, now.minus(Duration.ofDays(8)));
        int n = cleanupJob.cleanup();
        assertThat(n).isGreaterThanOrEqualTo(3);
        for (RefreshTokenEntity k : List.of(keepActive, keepRecentExpired, keepRecentRevoked)) {
            assertThat(tokenRepository.findById(k.getId())).isPresent();
        }
        for (RefreshTokenEntity d : List.of(delExpired, delRevoked, delReplaced)) {
            assertThat(tokenRepository.findById(d.getId())).isEmpty();
        }
    }

    private RefreshTokenEntity tok(Long uid, String hash, Instant expires, Instant revoked, Instant replaced) {
        RefreshTokenEntity t = new RefreshTokenEntity();
        t.setUserId(uid);
        t.setTokenHash(hash + "-" + UUID.randomUUID());
        t.setFamilyId(UUID.randomUUID().toString());
        t.setCreatedAt(Instant.now().minus(Duration.ofDays(100)));
        t.setExpiresAt(expires);
        t.setRevokedAt(revoked);
        t.setReplacedAt(replaced);
        return tokenRepository.save(t);
    }
}
