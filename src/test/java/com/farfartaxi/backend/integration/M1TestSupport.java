package com.farfartaxi.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.farfartaxi.backend.model.UserEntity;
import com.farfartaxi.backend.model.RideEventEntity;
import com.farfartaxi.backend.repo.RideEventRepository;
import com.farfartaxi.backend.repo.RideNotificationSentRepository;
import com.farfartaxi.backend.repo.RideOfferRepository;
import com.farfartaxi.backend.repo.RideRepository;
import com.farfartaxi.backend.repo.UserRepository;
import com.farfartaxi.backend.service.OsrmRouteProxyService;
import com.farfartaxi.backend.service.RideTimerService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Shared fixture for the M1 integration tests: real HTTP against a random port, H2, a controllable Clock
 * (the scheduler is switched off and driven by calling {@link RideTimerService#tick()}), a stubbed OSRM.
 * One context/DB is shared by all subclasses; users are created once, availability and the clock reset per test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:farfartaxi_m1;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false",
    "app.jwt.secret=test-only-integration-secret-key-012345678901234567890123",
    "app.admin.email=admin@test.local",
    "app.admin.password=Admin123!Test",
    "app.admin.name=Admin Test",
    "app.rides.scheduler-enabled=false",
    "app.vapid.public-key=BHhhj76Yn11MB9kpuMOv1t17mpRZzGY4BgVQz1NwdX8mmPoxuDojE5X1IsLiFWuuc4kgAmp_IQTdY5rbDGsbcRg",
    "app.vapid.private-key=A79KOH8Vt1XZY8NOYni8EMPf2G-FLEH1nEBThKshSI0",
    "app.vapid.subject=https://farfartaxi.test",
    "farfartaxi.test.passenger-password=TestPass123!",
    "farfartaxi.test.driver-password=TestDrive123!"
})
@Import(M1TestSupport.ClockConfig.class)
abstract class M1TestSupport {
    static final ZoneId STOCKHOLM = ZoneId.of("Europe/Stockholm");
    /** Monday 2027-03-01 09:00 Stockholm. */
    static final Instant BASE = Instant.parse("2027-03-01T08:00:00Z");
    static final String PW = "Password123!";

    static class MutableClock extends Clock {
        private volatile Instant now = Instant.now();

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void set(Instant i) { now = i; }
        void advance(Duration d) { now = now.plus(d); }
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    protected record Resp(int status, JsonNode body) {
        String code() {
            return body.hasNonNull("code") ? body.get("code").asText() : null;
        }
    }

    protected final ObjectMapper om = new ObjectMapper();

    @LocalServerPort protected int port;
    @Autowired protected MutableClock clock;
    @Autowired protected UserRepository users;
    @Autowired protected RideRepository rideRepo;
    @Autowired protected RideOfferRepository offerRepo;
    @Autowired protected RideNotificationSentRepository sentRepo;
    @Autowired protected RideTimerService timers;
    @Autowired protected RideEventRepository eventRepo;
    @MockitoBean protected OsrmRouteProxyService osrm;

    // fixture (static: shared by all subclasses of this context)
    private static int initializedPort = -1;
    private static int seq = 0;
    protected static String adminToken, p1, p2, d1, d2, d3, tp, td;
    protected static long p1Id, p2Id, d1Id, d2Id, d3Id, tpId, tdId, adminId;
    protected static final List<String> realDrivers = new ArrayList<>();

    @BeforeEach
    void m1Setup() throws Exception {
        if (initializedPort != port) {
            clock.set(Instant.now());
            realDrivers.clear();
            adminToken = login("admin@test.local", "Admin123!Test");
            adminId = idOf("admin@test.local");
            p1 = account("m1-p1@test.local", false);
            p2 = account("m1-p2@test.local", false);
            d1 = account("m1-d1@test.local", true);
            d2 = account("m1-d2@test.local", true);
            d3 = account("m1-d3@test.local", true);
            p1Id = idOf("m1-p1@test.local");
            p2Id = idOf("m1-p2@test.local");
            d1Id = idOf("m1-d1@test.local");
            d2Id = idOf("m1-d2@test.local");
            d3Id = idOf("m1-d3@test.local");
            realDrivers.addAll(List.of(d1, d2, d3));
            tp = login("test-passenger@farfartaxi.invalid", "TestPass123!");
            td = login("test-driver@farfartaxi.invalid", "TestDrive123!");
            tpId = idOf("test-passenger@farfartaxi.invalid");
            tdId = idOf("test-driver@farfartaxi.invalid");
            // The real admin also drives; keep admin out of every 2027 offer so tests control the driver pool.
            call("PUT", "/api/driver/availability", adminToken,
                Map.of("availableNow", false, "awayFrom", "2027-01-01", "awayUntil", "2027-12-31"), 200);
            initializedPort = port;
        }
        clock.set(BASE);
        for (String t : new String[] {d1, d2, d3, td}) {
            setAvailability(t, true, null, null);
        }
        for (String t : realDrivers) {
            setAvailability(t, true, null, null);
        }
        org.mockito.Mockito.reset(osrm);
    }

    // ------------------------------------------------------------------ accounts

    protected String account(String email, boolean driver) throws Exception {
        call("POST", "/api/auth/register", null, Map.of("email", email, "password", PW, "fullName", email), 200);
        long id = idOf(email);
        call("POST", "/api/admin/users/" + id + "/approve", adminToken, null, 200);
        if (driver) {
            call("POST", "/api/admin/users/" + id + "/promote-driver", adminToken, null, 200);
        }
        return login(email, PW);
    }

    /** A fresh real driver (it has no offer for rides booked before it existed). Joins the driver pool for later tests. */
    protected String newDriver() throws Exception {
        String tok = account("m1-late" + (++seq) + "@test.local", true);
        realDrivers.add(tok);
        return tok;
    }

    protected String login(String email, String password) throws Exception {
        return call("POST", "/api/auth/login", null, Map.of("email", email, "password", password), 200).body().get("token").asText();
    }

    protected long idOf(String email) throws Exception {
        for (JsonNode u : call("GET", "/api/admin/users", adminToken, null, 200).body()) {
            if (email.equalsIgnoreCase(u.get("email").asText())) {
                return u.get("id").asLong();
            }
        }
        throw new IllegalStateException(email);
    }

    protected void setPhone(long userId, String phone) {
        UserEntity u = users.findById(userId).orElseThrow();
        u.setPhone(phone);
        users.save(u);
    }

    protected void setAvailability(String token, boolean now, String from, String until) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("availableNow", now);
        body.put("awayFrom", from);
        body.put("awayUntil", until);
        call("PUT", "/api/driver/availability", token, body, 200);
    }

    // ------------------------------------------------------------------ rides

    static Instant at(String date, String time) {
        return ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), STOCKHOLM).toInstant();
    }

    protected Map<String, Object> rideBody(Instant scheduledAt) {
        Map<String, Object> m = new HashMap<>();
        m.put("fromAddress", "Start");
        m.put("fromLat", 59.3293);
        m.put("fromLon", 18.0686);
        m.put("toAddress", "Goal");
        m.put("toLat", 59.3340);
        m.put("toLon", 18.0700);
        m.put("kind", "SCHEDULED");
        m.put("scheduledAt", scheduledAt.toString());
        return m;
    }

    /** Books a SCHEDULED ride for the real passenger p1 (many days after BASE unless given). */
    protected long bookAt(Instant scheduledAt) throws Exception {
        return call("POST", "/api/rides", p1, rideBody(scheduledAt), 200).body().get("id").asLong();
    }

    protected long bookAt(String token, Instant scheduledAt) throws Exception {
        return call("POST", "/api/rides", token, rideBody(scheduledAt), 200).body().get("id").asLong();
    }

    protected long bookNow(String token) throws Exception {
        Map<String, Object> m = rideBody(BASE);
        m.put("kind", "NOW");
        m.remove("scheduledAt");
        return call("POST", "/api/rides", token, m, 200).body().get("id").asLong();
    }

    protected JsonNode ride(String token, long id) throws Exception {
        return call("GET", "/api/rides/" + id, token, null, 200).body();
    }

    protected String status(long id) throws Exception {
        return ride(p1, id).get("status").asText();
    }

    protected Resp accept(String token, long id) throws Exception {
        return call("POST", "/api/driver/rides/" + id + "/accept", token, Map.of("confirmProximity", true), null);
    }

    protected JsonNode acceptOk(String token, long id) throws Exception {
        return call("POST", "/api/driver/rides/" + id + "/accept", token, Map.of("confirmProximity", true), 200).body();
    }

    protected JsonNode drive(String token, long id, String step) throws Exception {
        return call("POST", "/api/driver/rides/" + id + "/" + step, token, null, 200).body();
    }

    protected Set<Long> openIds(String token) throws Exception {
        Set<Long> out = new LinkedHashSet<>();
        call("GET", "/api/driver/rides/open", token, null, 200).body().forEach(n -> out.add(n.get("id").asLong()));
        return out;
    }

    protected List<Long> openIdList(String token) throws Exception {
        List<Long> out = new ArrayList<>();
        call("GET", "/api/driver/rides/open", token, null, 200).body().forEach(n -> out.add(n.get("id").asLong()));
        return out;
    }

    /** Every real driver that holds an open offer declines (so the ride ends up NO_DRIVER). */
    protected void declineAllReal(long id) throws Exception {
        for (String t : realDrivers) {
            if (openIds(t).contains(id)) {
                call("POST", "/api/driver/rides/" + id + "/decline", t, Map.of("comment", "nej"), 200);
            }
        }
    }

    protected List<String> timeline(long rideId) {
        return eventRepo.findByRideIdOrderByIdAsc(rideId).stream().map(RideEventEntity::getEventType).toList();
    }

    protected Set<String> actions(JsonNode ride) {
        Set<String> s = new LinkedHashSet<>();
        ride.get("availableActions").forEach(n -> s.add(n.asText()));
        return s;
    }

    protected void assertConflict(Resp r, String code) {
        assertThat(r.status()).as(r.body().toString()).isEqualTo(409);
        assertThat(r.code()).as(r.body().toString()).isEqualTo(code);
    }

    // ------------------------------------------------------------------ http

    protected Resp call(String method, String path, String token, Object payload, Integer expected, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json");
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        for (int i = 0; i + 1 < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        HttpRequest.BodyPublisher body = payload == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(om.writeValueAsString(payload));
        HttpResponse<String> res = HttpClient.newHttpClient().send(b.method(method, body).build(), HttpResponse.BodyHandlers.ofString());
        if (expected != null) {
            assertThat(res.statusCode()).as(method + " " + path + " -> " + res.body()).isEqualTo(expected);
        }
        JsonNode json = res.body() == null || res.body().isBlank() ? om.createObjectNode() : om.readTree(res.body());
        return new Resp(res.statusCode(), json);
    }
}
