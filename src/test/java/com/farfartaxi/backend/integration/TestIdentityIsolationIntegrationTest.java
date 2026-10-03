package com.farfartaxi.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.farfartaxi.backend.config.TestAccountBootstrapConfig;
import com.farfartaxi.backend.model.RideEventEntity;
import com.farfartaxi.backend.model.UserEntity;
import com.farfartaxi.backend.repo.RideEventRepository;
import com.farfartaxi.backend.repo.RideRepository;
import com.farfartaxi.backend.repo.UserRepository;
import com.farfartaxi.backend.service.TestRideCleanupJob;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:farfartaxi_isolation;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
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
class TestIdentityIsolationIntegrationTest {
    private final com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
    private static final Map<String, Object> RIDE = Map.of(
        "fromAddress", "Start", "fromLat", 59.3293, "fromLon", 18.0686,
        "toAddress", "Goal", "toLat", 59.3340, "toLon", 18.0700,
        "scheduledAt", "2030-01-01T10:00:00Z");

    @LocalServerPort private int port;
    @Autowired private UserRepository userRepository;
    @Autowired private RideRepository rideRepository;
    @Autowired private RideEventRepository eventRepository;
    @Autowired private TestAccountBootstrapConfig bootstrap;
    @Autowired private TestRideCleanupJob cleanupJob;

    private static String adminToken, realUser, realDriver, testUser, testDriver;
    private static long realUserId, realDriverId, testUserId, testDriverId;
    private static boolean initialized;

    @BeforeAll
    static void reset() {
        initialized = false;
    }

    private void init() throws Exception {
        if (initialized) {
            return;
        }
        adminToken = login("admin@test.local", "Admin123!Test");
        realUser = realAccount("ru@test.local", false);
        realDriver = realAccount("rd@test.local", true);
        testUser = login(TestAccountBootstrapConfig.PASSENGER_EMAIL, "TestPass123!");
        testDriver = login(TestAccountBootstrapConfig.DRIVER_EMAIL, "TestDrive123!");
        realUserId = idOf("ru@test.local");
        realDriverId = idOf("rd@test.local");
        testUserId = idOf(TestAccountBootstrapConfig.PASSENGER_EMAIL);
        testDriverId = idOf(TestAccountBootstrapConfig.DRIVER_EMAIL);
        initialized = true;
    }

    // ---- bootstrap ----

    @Test
    void bootstrapCreatesBothAccountsAndIsIdempotent() throws Exception {
        init();
        UserEntity p = userRepository.findByEmailIgnoreCase(TestAccountBootstrapConfig.PASSENGER_EMAIL).orElseThrow();
        UserEntity d = userRepository.findByEmailIgnoreCase(TestAccountBootstrapConfig.DRIVER_EMAIL).orElseThrow();
        assertThat(p.isTest()).isTrue();
        assertThat(p.getFullName()).isEqualTo("Test Passagerare");
        assertThat(p.getRole().name()).isEqualTo("USER");
        assertThat(p.isApproved() && p.isEnabled()).isTrue();
        assertThat(d.isTest()).isTrue();
        assertThat(d.getFullName()).isEqualTo("Test Förare");
        assertThat(d.getRole().name()).isEqualTo("DRIVER");

        long count = userRepository.count();
        String hash = p.getPasswordHash();
        var changedAt = p.getCredentialsChangedAt();
        bootstrap.bootstrap();
        assertThat(userRepository.count()).isEqualTo(count);
        UserEntity again = userRepository.findByEmailIgnoreCase(TestAccountBootstrapConfig.PASSENGER_EMAIL).orElseThrow();
        assertThat(again.getPasswordHash()).isEqualTo(hash);
        assertThat(again.getCredentialsChangedAt()).isEqualTo(changedAt);
        // Tokens stay valid after an idempotent re-run
        send("GET", "/api/rides/my", testUser, null, 200);

        boolean flagged = false;
        for (JsonNode u : send("GET", "/api/admin/users", adminToken, null, 200)) {
            if (u.get("email").asText().equals(TestAccountBootstrapConfig.DRIVER_EMAIL)) {
                flagged = u.get("isTest").asBoolean();
            } else if (u.get("email").asText().equals("rd@test.local")) {
                assertThat(u.get("isTest").asBoolean()).isFalse();
            }
        }
        assertThat(flagged).isTrue();
    }

    // ---- isolation: every ride endpoint, both directions ----

    @Test
    void realWorldCannotSeeOrActOnTestRides() throws Exception {
        init();
        long testRide = book(testUser, null);
        assertNoAccess(testRide, realUser, realDriver);
        assertNoAccess(testRide, realUser, adminToken); // real admin acts as driver on a test ride
        assertStillPending(testRide, testUser);
        // test world itself is fine
        send("GET", "/api/rides/" + testRide, testUser, null, 200);
    }

    @Test
    void testWorldCannotSeeOrActOnRealRides() throws Exception {
        init();
        long realRide = book(realUser, null);
        assertNoAccess(realRide, testUser, testDriver);
        assertStillPending(realRide, realUser);
        send("GET", "/api/rides/" + realRide, realUser, null, 200);
    }

    @Test
    void adminCanOnlyDeleteTestRidesAsAnException() throws Exception {
        init();
        long testRide = book(testUser, null);
        send("GET", "/api/rides/" + testRide, adminToken, null, 404);
        send("POST", "/api/rides/" + testRide + "/cancel", adminToken, null, 404);
        send("POST", "/api/driver/rides/" + testRide + "/accept", adminToken, null, 404);
        send("DELETE", "/api/admin/rides/" + testRide, adminToken, null, 200);
        assertThat(rideRepository.findById(testRide)).isEmpty();
    }

    private void assertNoAccess(long rideId, String passengerSide, String driverSide) throws Exception {
        String r = "/api/rides/" + rideId;
        send("GET", r, passengerSide, null, 404);
        send("GET", r + "/stream", passengerSide, null, 404);
        send("POST", r + "/cancel", passengerSide, Map.of("reason", "x"), 404);
        send("DELETE", r, passengerSide, null, 404);
        send("PATCH", r, passengerSide, Map.of("pickupNote", "x"), 404);
        send("POST", r + "/keep-waiting", passengerSide, null, 404);
        send("GET", r + "/messages", passengerSide, null, 404);
        send("POST", r + "/messages", passengerSide, Map.of("code", "PASSENGER_OUTSIDE"), 404);
        send("GET", r + "/messages", driverSide, null, 404);
        send("POST", r + "/messages", driverSide, Map.of("code", "DRIVER_HERE"), 404);
        send("POST", r + "/share", passengerSide, null, 404);
        send("DELETE", r + "/share", passengerSide, null, 404);
        send("POST", r + "/feedback", passengerSide, Map.of("stars", 5, "comment", "x"), 404);
        send("GET", r, driverSide, null, 404);
        send("GET", r + "/stream", driverSide, null, 404);
        String d = "/api/driver/rides/" + rideId;
        send("POST", d + "/accept", driverSide, null, 404);
        send("POST", d + "/refuse", driverSide, Map.of("comment", "no"), 404);
        send("POST", d + "/unaccept", driverSide, null, 404);
        send("POST", d + "/decline", driverSide, Map.of("comment", "no"), 404);
        send("POST", d + "/return", driverSide, Map.of("reason", "no"), 404);
        send("POST", d + "/start", driverSide, null, 404);
        send("POST", d + "/arrive", driverSide, null, 404);
        send("POST", d + "/pickup", driverSide, null, 404);
        send("POST", d + "/location", driverSide, Map.of("lat", 59.33, "lon", 18.07), 404);
        send("POST", d + "/complete", driverSide, null, 404);
    }

    private void assertStillPending(long rideId, String ownerToken) throws Exception {
        JsonNode ride = send("GET", "/api/rides/" + rideId, ownerToken, null, 200);
        assertThat(ride.get("status").asText()).isEqualTo("REQUESTED");
        assertThat(ride.get("acceptedByDriverId").isNull()).isTrue();
    }

    // ---- listings ----

    @Test
    void listingsAndStatsAreWorldFiltered() throws Exception {
        init();
        long realRide = book(realUser, null);
        long testRide = book(testUser, null);

        assertThat(ids(send("GET", "/api/driver/rides/open", realDriver, null, 200))).contains(realRide).doesNotContain(testRide);
        assertThat(ids(send("GET", "/api/driver/rides/open", adminToken, null, 200))).contains(realRide).doesNotContain(testRide);
        assertThat(ids(send("GET", "/api/driver/rides/open", testDriver, null, 200))).contains(testRide).doesNotContain(realRide);
        assertThat(ids(send("GET", "/api/rides/my", realUser, null, 200))).contains(realRide).doesNotContain(testRide);
        assertThat(ids(send("GET", "/api/rides/my", testUser, null, 200))).contains(testRide).doesNotContain(realRide);

        // assigned + stats (deltas: other tests share the DB)
        long realBefore = send("GET", "/api/driver/stats", realDriver, null, 200).get("acceptedRides").asLong();
        long testBefore = send("GET", "/api/driver/stats", testDriver, null, 200).get("acceptedRides").asLong();
        send("POST", "/api/driver/rides/" + realRide + "/accept", realDriver, Map.of("confirmProximity", true), 200);
        send("POST", "/api/driver/rides/" + testRide + "/accept", testDriver, Map.of("confirmProximity", true), 200);
        assertThat(ids(send("GET", "/api/driver/rides/mine", realDriver, null, 200))).contains(realRide).doesNotContain(testRide);
        assertThat(ids(send("GET", "/api/driver/rides/mine", testDriver, null, 200))).contains(testRide).doesNotContain(realRide);
        assertThat(send("GET", "/api/driver/stats", realDriver, null, 200).get("acceptedRides").asLong()).isEqualTo(realBefore + 1);
        assertThat(send("GET", "/api/driver/stats", testDriver, null, 200).get("acceptedRides").asLong()).isEqualTo(testBefore + 1);

        // for-booking
        List<Long> forReal = ids(send("GET", "/api/users/for-booking", realDriver, null, 200));
        assertThat(forReal).contains(realUserId).doesNotContain(testUserId, testDriverId);
        List<Long> forAdmin = ids(send("GET", "/api/users/for-booking", adminToken, null, 200));
        assertThat(forAdmin).contains(realUserId).doesNotContain(testUserId, testDriverId);
        List<Long> forTest = ids(send("GET", "/api/users/for-booking", testDriver, null, 200));
        assertThat(forTest).contains(testUserId, testDriverId).doesNotContain(realUserId, realDriverId);
    }

    @Test
    void bookingOnBehalfAcrossWorldsIsRejected() throws Exception {
        init();
        send("POST", "/api/rides", realDriver, withPassenger(testUserId), 400);
        send("POST", "/api/rides", adminToken, withPassenger(testUserId), 400);
        send("POST", "/api/rides", testDriver, withPassenger(realUserId), 400);
        // same world works and the ride inherits the passenger's world
        JsonNode ok = send("POST", "/api/rides", testDriver, withPassenger(testUserId), 200);
        assertThat(rideRepository.findById(ok.get("id").asLong()).orElseThrow().isTest()).isTrue();
        JsonNode okReal = send("POST", "/api/rides", realDriver, withPassenger(realUserId), 200);
        assertThat(rideRepository.findById(okReal.get("id").asLong()).orElseThrow().isTest()).isFalse();
    }

    @Test
    void shareLinksWorkAnonymouslyButNotAcrossWorlds() throws Exception {
        init();
        long testRide = book(testUser, null);
        String token = share(testRide, testUser);
        send("GET", "/api/public/share/" + token, null, null, 200);
        // never exposes more than the anonymized view, whoever asks
        send("GET", "/api/public/share/" + token, realUser, null, 200);

        long realRide = book(realUser, null);
        String realToken = share(realRide, realUser);
        send("GET", "/api/public/share/" + realToken, null, null, 200);
        // creating/revoking a share across worlds stays hidden
        send("POST", "/api/rides/" + testRide + "/share", realUser, null, 404);
        send("DELETE", "/api/rides/" + realRide + "/share", testUser, null, 404);
    }

    // ---- cleanup ----

    @Test
    void nightlyCleanupDeletesOnlyTestRides() throws Exception {
        init();
        long realRide = book(realUser, null);
        long testRide = book(testUser, null);
        long doneTestRide = book(testUser, null);
        send("POST", "/api/driver/rides/" + doneTestRide + "/accept", testDriver, Map.of("confirmProximity", true), 200);
        send("POST", "/api/driver/rides/" + doneTestRide + "/start", testDriver, null, 200);
        send("POST", "/api/driver/rides/" + doneTestRide + "/arrive", testDriver, null, 200);
        send("POST", "/api/driver/rides/" + doneTestRide + "/pickup", testDriver, null, 200);
        send("POST", "/api/driver/rides/" + doneTestRide + "/complete", testDriver, null, 200);
        send("POST", "/api/rides/" + doneTestRide + "/feedback", testUser, Map.of("stars", 4, "comment", "ok"), 200);

        int deleted = cleanupJob.cleanup();
        assertThat(deleted).isGreaterThanOrEqualTo(2);
        assertThat(rideRepository.findById(testRide)).isEmpty();
        assertThat(rideRepository.findById(doneTestRide)).isEmpty();
        assertThat(eventRepository.findByRideIdOrderByIdAsc(doneTestRide)).isEmpty();
        assertThat(rideRepository.findById(realRide)).isPresent();
        assertThat(eventRepository.findByRideIdOrderByIdAsc(realRide)).isNotEmpty();
        assertThat(rideRepository.findAll().stream().noneMatch(r -> r.isTest())).isTrue();
        // the accounts themselves survive
        send("GET", "/api/rides/my", testUser, null, 200);
    }

    // ---- ride_events ----

    @Test
    void rideEventsRecordedForFullFlow() throws Exception {
        init();
        long id = book(realUser, null);
        send("POST", "/api/driver/rides/" + id + "/accept", realDriver, Map.of("confirmProximity", true), 200);
        send("POST", "/api/driver/rides/" + id + "/unaccept", realDriver, null, 200);
        send("POST", "/api/driver/rides/" + id + "/accept", realDriver, null, 409); // returner's offer is WITHDRAWN
        send("POST", "/api/driver/rides/" + id + "/accept", adminToken, Map.of("confirmProximity", true), 200); // admins drive in the real world
        send("POST", "/api/driver/rides/" + id + "/start", adminToken, null, 200);
        send("POST", "/api/driver/rides/" + id + "/arrive", adminToken, null, 200);
        send("POST", "/api/driver/rides/" + id + "/pickup", adminToken, null, 200);
        send("POST", "/api/driver/rides/" + id + "/location", adminToken, Map.of("lat", 59.33, "lon", 18.07), 200);
        share(id, realUser);
        send("DELETE", "/api/rides/" + id + "/share", realUser, null, 200);
        send("POST", "/api/driver/rides/" + id + "/complete", adminToken, null, 200);
        send("POST", "/api/rides/" + id + "/feedback", realUser, Map.of("stars", 5, "comment", "ok"), 200);

        List<RideEventEntity> evs = eventRepository.findByRideIdOrderByIdAsc(id);
        assertThat(evs.stream().map(RideEventEntity::getEventType).toList()).containsExactly(
            "BOOKED", "ACCEPTED", "RETURNED", "ACCEPTED", "STARTED", "ARRIVED", "PICKED_UP", "SHARE_CREATED", "SHARE_REVOKED", "COMPLETED", "FEEDBACK");
        assertThat(evs.get(0).getActorId()).isEqualTo(realUserId);
        assertThat(evs.get(1).getActorId()).isEqualTo(realDriverId);
        assertThat(evs.get(2).getEventType()).isEqualTo("RETURNED");

        long refused = book(realUser, null);
        send("POST", "/api/driver/rides/" + refused + "/refuse", realDriver, Map.of("comment", "busy"), 200);
        List<RideEventEntity> r = eventRepository.findByRideIdOrderByIdAsc(refused);
        assertThat(r.get(1).getEventType()).isEqualTo("DECLINED");
        assertThat(r.get(1).getComment()).isEqualTo("busy");

        long cancelled = book(realUser, null);
        send("POST", "/api/rides/" + cancelled + "/cancel", realUser, Map.of("reason", "plans"), 200);
        List<RideEventEntity> c = eventRepository.findByRideIdOrderByIdAsc(cancelled);
        assertThat(c.get(1).getEventType()).isEqualTo("CANCELLED");
        assertThat(c.get(1).getComment()).isEqualTo("plans");
    }

    // ---- helpers ----

    private Map<String, Object> withPassenger(long passengerId) {
        Map<String, Object> m = new java.util.HashMap<>(RIDE);
        m.put("passengerUserId", passengerId);
        return m;
    }

    private long book(String token, Long passengerId) throws Exception {
        return send("POST", "/api/rides", token, RIDE, 200).get("id").asLong();
    }

    private String share(long rideId, String token) throws Exception {
        return send("POST", "/api/rides/" + rideId + "/share", token, null, 200).get("token").asText();
    }

    private List<Long> ids(JsonNode arr) {
        List<Long> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.get("id").asLong()));
        return out;
    }

    private String realAccount(String email, boolean driver) throws Exception {
        send("POST", "/api/auth/register", null, Map.of("email", email, "password", "Password123!", "fullName", email), 200);
        long id = idOf(email);
        send("POST", "/api/admin/users/" + id + "/approve", adminToken, null, 200);
        if (driver) {
            send("POST", "/api/admin/users/" + id + "/promote-driver", adminToken, null, 200);
        }
        return login(email, "Password123!");
    }

    private String login(String email, String password) throws Exception {
        return send("POST", "/api/auth/login", null, Map.of("email", email, "password", password), 200).get("token").asText();
    }

    private long idOf(String email) throws Exception {
        for (JsonNode u : send("GET", "/api/admin/users", adminToken, null, 200)) {
            if (email.equalsIgnoreCase(u.get("email").asText())) {
                return u.get("id").asLong();
            }
        }
        throw new IllegalStateException(email);
    }

    private JsonNode send(String method, String path, String token, Object payload, int expected) throws Exception {
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
