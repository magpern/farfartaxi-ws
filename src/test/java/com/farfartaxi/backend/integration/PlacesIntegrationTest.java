package com.farfartaxi.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.farfartaxi.backend.model.PlaceSelectionEntity;
import com.farfartaxi.backend.observability.AppMetrics;
import com.farfartaxi.backend.places.NearestStopService;
import com.farfartaxi.backend.places.PlaceSelectionService;
import com.farfartaxi.backend.repo.PlaceSelectionRepository;
import com.farfartaxi.backend.service.OsrmRouteProxyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** M3 search and places over real HTTP; SL and Nominatim are in-process stub servers. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:farfartaxi_places;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false",
    "app.jwt.secret=test-only-integration-secret-key-012345678901234567890123",
    "app.admin.email=admin@test.local",
    "app.admin.password=Admin123!Test",
    "app.admin.name=Admin Test",
    "app.rides.scheduler-enabled=false",
    "app.places.sl.timeout-ms=500",
    "app.places.nominatim.min-interval-ms=3000",
    "farfartaxi.test.passenger-password=TestPass123!",
    "farfartaxi.test.driver-password=TestDrive123!"
})
@Import(PlacesIntegrationTest.ClockConfig.class)
class PlacesIntegrationTest {
    static class MutableClock extends Clock {
        private volatile Instant now = Instant.now();
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void set(Instant i) { now = i; }
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock placesClock() {
            return new MutableClock();
        }
    }

    // ---------------------------------------------------------------- stub servers
    static final Map<String, String> SL_BODIES = new ConcurrentHashMap<>();
    static final Map<String, AtomicInteger> SL_CALLS = new ConcurrentHashMap<>();
    static final Map<String, Integer> SL_STATUS = new ConcurrentHashMap<>();
    static final Map<String, Integer> SL_DELAY_MS = new ConcurrentHashMap<>();
    static final AtomicReference<String> SITES = new AtomicReference<>("[]");
    static final AtomicInteger REVERSE_CALLS = new AtomicInteger();
    static final HttpServer SL = server();
    static final HttpServer TRANSPORT = server();
    static final HttpServer NOMINATIM = server();

    static HttpServer server() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.setExecutor(Executors.newCachedThreadPool());
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static {
        SL.createContext("/v2/stop-finder", ex -> {
            String name = null;
            for (String kv : ex.getRequestURI().getRawQuery().split("&")) {
                if (kv.startsWith("name_sf=")) {
                    name = URLDecoder.decode(kv.substring(8), StandardCharsets.UTF_8);
                }
            }
            SL_CALLS.computeIfAbsent(name, k -> new AtomicInteger()).incrementAndGet();
            int delay = SL_DELAY_MS.getOrDefault(name, 0);
            if (delay > 0) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            respond(ex, SL_STATUS.getOrDefault(name, 200), SL_BODIES.getOrDefault(name, "{\"locations\":[]}"));
        });
        TRANSPORT.createContext("/v1/sites", ex -> respond(ex, 200, SITES.get()));
        NOMINATIM.createContext("/failing/reverse", ex -> respond(ex, 500, "{}"));
        NOMINATIM.createContext("/reverse", ex -> {
            REVERSE_CALLS.incrementAndGet();
            respond(ex, 200, """
                {"place_id":4242,"lat":"59.3400","lon":"18.0600","name":"","display_name":"12, Sveavägen, Norrmalm, Stockholm, Sverige",
                 "address":{"house_number":"12","road":"Sveavägen","suburb":"Norrmalm","city":"Stockholm","postcode":"111 57"}}""");
        });
        SL.start();
        TRANSPORT.start();
        NOMINATIM.start();
    }

    static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("app.places.sl.journeyplanner-base-url", () -> "http://127.0.0.1:" + SL.getAddress().getPort());
        r.add("app.places.sl.transport-base-url", () -> "http://127.0.0.1:" + TRANSPORT.getAddress().getPort());
        r.add("app.places.nominatim.base-url", () -> "http://127.0.0.1:" + NOMINATIM.getAddress().getPort());
    }

    // ---------------------------------------------------------------- fixtures
    static final double[] JARFALLA = {59.4235, 17.8350};
    static final double[] KISTA = {59.4030, 17.9440};
    static final double[] SOLNA = {59.3600, 18.0000};
    static final double[] GOTEBORG = {57.7089, 11.9746};

    static String loc(String id, String type, String parentName, String disassembled, double[] c, int mq, String extra) {
        return "{\"id\":\"" + id + "\",\"name\":\"" + parentName + ", " + disassembled + "\",\"disassembledName\":\"" + disassembled
            + "\",\"type\":\"" + type + "\",\"coord\":[" + c[0] + "," + c[1] + "],\"matchQuality\":" + mq
            + ",\"isBest\":false,\"parent\":{\"name\":\"" + parentName + "\"}" + (extra == null ? "" : "," + extra) + "}";
    }

    static void stub(String key, String... locations) {
        SL_BODIES.put(key, "{\"locations\":[" + String.join(",", locations) + "]}");
        SL_CALLS.remove(key);
        SL_STATUS.remove(key);
        SL_DELAY_MS.remove(key);
    }

    /** Near (Järfälla) stop N and a Göteborg stop F, same match quality. */
    static void stubNearFar(String key) {
        stub(key, loc("N-" + key, "stop", "Järfälla", "Nära", JARFALLA, 1000, null),
            loc("F-" + key, "stop", "Göteborg", "Borta", GOTEBORG, 1000, null));
    }

    static int calls(String key) {
        AtomicInteger a = SL_CALLS.get(key);
        return a == null ? 0 : a.get();
    }

    @LocalServerPort int port;
    @Autowired MutableClock clock;
    @Autowired com.farfartaxi.backend.repo.UserRepository users;
    @Autowired PlaceSelectionRepository selectionRepo;
    @Autowired PlaceSelectionService selectionService;
    @Autowired AppMetrics metrics;
    @Autowired com.farfartaxi.backend.service.TestRideCleanupJob cleanupJob;
    @Autowired NearestStopService nearestStops;
    @Autowired com.farfartaxi.backend.places.SlPlaceProvider slProvider;
    @MockitoBean OsrmRouteProxyService osrm;

    final ObjectMapper om = new ObjectMapper();
    static String adminToken;
    static int seq;

    record Resp(int status, JsonNode body) {
    }

    @BeforeEach
    void setUp() throws Exception {
        clock.set(Instant.now());
        slProvider.clearCache();
        if (adminToken == null) {
            adminToken = login("admin@test.local", "Admin123!Test");
        }
    }

    // ---------------------------------------------------------------- tests: search
    @Test
    void searchNormalizesKindsAndLabels() throws Exception {
        stub("sveavägen",
            loc("addr1", "singlehouse", "Stockholm", "Sveavägen 12", new double[] {59.3400, 18.0600}, 900,
                "\"streetName\":\"Sveavägen\",\"buildingNumber\":\"12\",\"postCode\":\"11157\""),
            loc("stop1", "stop", "Järfälla", "McDonalds", JARFALLA, 800, null),
            loc("poi1", "poi", "Kista", "Kista Galleria", KISTA, 700, null),
            loc("x", "locality", "Stockholm", "Dropped", SOLNA, 999, null));
        String u = user();
        JsonNode r = search(u, "q=Sveav%C3%A4gen&lat=59.4235&lon=17.835&accuracy=20").body();
        assertThat(r.get("context").asText()).isEqualTo("GPS");
        Map<String, JsonNode> byKind = new HashMap<>();
        r.get("results").forEach(n -> byKind.put(n.get("kind").asText(), n));
        assertThat(byKind).containsOnlyKeys("STOP", "ADDRESS", "POI");
        JsonNode a = byKind.get("ADDRESS");
        assertThat(a.get("name").asText()).isEqualTo("Sveavägen 12");
        assertThat(a.get("area").asText()).isEqualTo("Stockholm");
        assertThat(a.get("formattedAddress").asText()).isEqualTo("Sveavägen 12, Stockholm");
        assertThat(a.get("provider").asText()).isEqualTo("SL");
        assertThat(a.get("providerPlaceId").asText()).isEqualTo("addr1");
        JsonNode s = byKind.get("STOP");
        assertThat(s.get("name").asText()).isEqualTo("McDonalds");
        assertThat(s.get("area").asText()).isEqualTo("Järfälla");
        assertThat(s.get("formattedAddress").asText()).isEqualTo("McDonalds — Järfälla (hållplats)");
        assertThat(s.get("distanceKm").asDouble()).isLessThan(0.1);
        assertThat(byKind.get("POI").get("name").asText()).isEqualTo("Kista Galleria");
        // short query -> empty, no provider call needed
        assertThat(search(u, "q=a").body().get("results")).isEmpty();
    }

    @Test
    void rankingNearestFirstFarOnlyInMoreAndDedupe() throws Exception {
        stub("mcdonalds",
            loc("g", "stop", "Göteborg", "McDonalds", GOTEBORG, 1000, null),
            loc("s", "stop", "Solna", "McDonalds", SOLNA, 1000, null),
            loc("k", "stop", "Kista", "McDonalds", KISTA, 1000, null),
            loc("j", "stop", "Järfälla", "McDonalds", JARFALLA, 1000, null),
            // duplicate of j: same name ~10 m away
            loc("j2", "stop", "Järfälla", "McDonalds", new double[] {59.42358, 17.8350}, 1000, null));
        String u = user();
        JsonNode r = search(u, "q=mcdonalds").body();
        assertThat(r.get("context").asText()).isEqualTo("DEFAULT");
        List<String> ids = ids(r);
        assertThat(ids).containsExactly("j", "k", "s");
        assertThat(r.get("hasMore").asBoolean()).isTrue();
        JsonNode more = search(u, "q=mcdonalds&limit=25").body();
        assertThat(ids(more)).containsExactly("j", "k", "s", "g");
        assertThat(more.get("hasMore").asBoolean()).isFalse();
    }

    @Test
    void distanceTiersBeatMatchQuality() throws Exception {
        // Solna ~ 19 km (tier 1.0, mq .5) vs a 60 km hit with perfect mq (0.4) -> nearer wins
        stub("tierq",
            loc("far60", "stop", "Norrtälje", "Tier", new double[] {59.9, 18.7}, 1000, null),
            loc("near", "stop", "Solna", "Tier", SOLNA, 500, null));
        assertThat(ids(search(user(), "q=tierq").body())).containsExactly("near", "far60");
    }

    @Test
    void favoritesAndRecentsComeFirstWithTokenMatching() throws Exception {
        stub("donken", loc("sl-mc", "stop", "Kista", "McDonalds", KISTA, 1000, null));
        stub("mcdonalds", loc("sl-mc", "stop", "Kista", "McDonalds", KISTA, 1000, null));
        String u = user();
        call("POST", "/api/saved-places", u, Map.of("label", "Donken Kista", "address", "Kistagången 1, Kista",
            "lat", 59.4031, "lon", 17.9441), 200);
        call("POST", "/api/saved-places", u, Map.of("label", "Mormor", "address", "Storgatan 5, Järfälla",
            "lat", 59.43, "lon", 17.83), 200);
        Map<String, Object> ride = new HashMap<>();
        ride.put("fromAddress", "Mcdonalds, Barkarby");
        ride.put("fromLat", 59.4010);
        ride.put("fromLon", 17.8700);
        ride.put("toAddress", "Skolgatan 3, Järfälla");
        ride.put("toLat", 59.4300);
        ride.put("toLon", 17.8400);
        ride.put("kind", "SCHEDULED");
        ride.put("scheduledAt", "2030-01-01T10:00:00Z");
        call("POST", "/api/rides", u, ride, 200);

        JsonNode r = search(u, "q=donken").body().get("results");
        assertThat(r.get(0).get("provider").asText()).isEqualTo("FAVORITE");
        assertThat(r.get(0).get("name").asText()).isEqualTo("Donken Kista");
        assertThat(r.get(0).get("kind").asText()).isEqualTo("FAVORITE");
        assertThat(r.get(1).get("provider").asText()).isEqualTo("RECENT");
        assertThat(r.get(1).get("formattedAddress").asText()).isEqualTo("Mcdonalds, Barkarby");
        assertThat(r.get(2).get("provider").asText()).isEqualTo("SL");

        // all query tokens must prefix-match label/address tokens
        assertThat(search(u, "q=mor+stor").body().get("results").get(0).get("name").asText()).isEqualTo("Mormor");
        assertThat(search(u, "q=mor+xyz").body().get("results")).isEmpty();
        JsonNode recent = search(u, "q=skolg").body().get("results");
        assertThat(recent).hasSize(1);
        assertThat(recent.get(0).get("provider").asText()).isEqualTo("RECENT");
        // another user sees neither
        assertThat(search(user(), "q=mormor").body().get("results")).isEmpty();
    }

    // ---------------------------------------------------------------- normalization
    @Test
    void synonymsAndSpellingsHitTheSameStop() throws Exception {
        stub("mcdonalds", loc("mc-1", "stop", "Järfälla", "McDonalds", JARFALLA, 1000, null));
        String u = user();
        for (String q : new String[] {"Mc+Donalds", "mcdonald%27s", "donken", "MACCEN", "mc+donald"}) {
            JsonNode r = search(u, "q=" + q).body();
            assertThat(ids(r)).as(q).contains("mc-1");
        }
        stub("ica maxi", loc("ica-1", "stop", "Barkarby", "ICA Maxi", JARFALLA, 1000, null));
        assertThat(ids(search(u, "q=icamaxi").body())).contains("ica-1");
        stub("sveavägen", loc("a", "street", "Stockholm", "Sveavägen", SOLNA, 1000, null));
        JsonNode sv = search(u, "q=SVEAV%C3%84GEN").body().get("results");
        assertThat(sv.get(0).get("name").asText()).isEqualTo("Sveavägen");
        stub("åkeri", loc("ak", "poi", "Bro", "Åkeriet", JARFALLA, 1000, null));
        assertThat(search(u, "q=%C3%85keri").body().get("results").get(0).get("name").asText()).isEqualTo("Åkeriet");
    }

    // ---------------------------------------------------------------- learned ranking
    @Test
    void selectionForDonkenRanksPlaceFirst() throws Exception {
        stub("mcdonalds",
            loc("near", "stop", "Järfälla", "McDonalds", JARFALLA, 1000, null),
            loc("kista", "stop", "Kista", "McDonalds", KISTA, 1000, null));
        String u = user();
        assertThat(ids(search(u, "q=donken").body())).containsExactly("near", "kista");
        call("POST", "/api/places/selections", u, selection("donken", "SL", "kista", "McDonalds", KISTA), 204);
        assertThat(ids(search(u, "q=donken").body())).containsExactly("kista", "near");
        // typed prefix of the learned query also benefits ("mcd")
        stub("mcd", loc("near", "stop", "Järfälla", "McDonalds", JARFALLA, 1000, null),
            loc("kista", "stop", "Kista", "McDonalds", KISTA, 1000, null));
        assertThat(ids(search(u, "q=mcd").body())).containsExactly("kista", "near");
        // malformed / unlearnable selections are ignored
        call("POST", "/api/places/selections", u, selection("donken", "FAVORITE", "1", "x", KISTA), 204);
        call("POST", "/api/places/selections", u, Map.of("query", "", "provider", "SL"), 400);
    }

    @Test
    void learnedBoostDecaysWithNinetyDayHalfLife() throws Exception {
        stubNearFar("decayq");
        String author = user();
        String viewer = user();
        call("POST", "/api/places/selections", author, selection("decayq", "SL", "F-decayq", "Borta", GOTEBORG), 204);
        // day 0: weight 1 (other user) -> 0.1 + 1.0 beats 1.0
        assertThat(ids(search(viewer, "q=decayq").body())).containsExactly("F-decayq", "N-decayq");
        // 90 days later boost is 0.5 -> 0.6 < 1.0
        clock.set(Instant.now().plus(Duration.ofDays(90)));
        assertThat(ids(search(viewer, "q=decayq").body())).containsExactly("N-decayq", "F-decayq");
        assertThat(PlaceSelectionService.decayed(8, Instant.parse("2027-01-01T00:00:00Z"), Instant.parse("2027-04-01T00:00:00Z")))
            .isCloseTo(8 * Math.pow(0.5, 90.0 / 90.0), org.assertj.core.data.Offset.offset(0.2));
        assertThat(PlaceSelectionService.decayed(4, Instant.parse("2027-01-01T00:00:00Z"), Instant.parse("2027-06-30T00:00:00Z")))
            .isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.05));
        // re-selecting decays the stored score before adding one
        call("POST", "/api/places/selections", author, selection("decayq", "SL", "F-decayq", "Borta", GOTEBORG), 204);
        PlaceSelectionEntity row = selectionRepo.findAll().stream()
            .filter(s -> s.getProviderPlaceId().equals("F-decayq")).findFirst().orElseThrow();
        assertThat(row.getScore()).isCloseTo(1.5, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void nightlyPruneDropsFullyDecayedSelections() throws Exception {
        stubNearFar("pruneq");
        String u = user();
        call("POST", "/api/places/selections", u, selection("pruneq", "SL", "N-pruneq", "Nära", JARFALLA), 204);
        selectionService.prune();
        assertThat(selectionRepo.findAll().stream().anyMatch(s -> s.getProviderPlaceId().equals("N-pruneq"))).isTrue();
        clock.set(Instant.now().plus(Duration.ofDays(400))); // 1 * 0.5^(4.4) < 0.05
        selectionService.prune();
        assertThat(selectionRepo.findAll().stream().anyMatch(s -> s.getProviderPlaceId().equals("N-pruneq"))).isFalse();
    }

    @Test
    void sameUserSelectionCountsDouble() throws Exception {
        // P (nearer) and Q equally good; X picked Q once, Y picked P once.
        stub("weightq",
            loc("P", "stop", "Järfälla", "Pe", JARFALLA, 1000, null),
            loc("Q", "stop", "Kista", "Kju", KISTA, 1000, null));
        String x = user();
        String y = user();
        call("POST", "/api/places/selections", x, selection("weightq", "SL", "Q", "Kju", KISTA), 204);
        call("POST", "/api/places/selections", y, selection("weightq", "SL", "P", "Pe", JARFALLA), 204);
        // X: Q = 1 + 2 (own), P = 1 + 1 (other) -> Q first although P is nearer
        assertThat(ids(search(x, "q=weightq").body())).containsExactly("Q", "P");
        // Y: P = 1 + 2, Q = 1 + 1
        assertThat(ids(search(y, "q=weightq").body())).containsExactly("P", "Q");
    }

    @Test
    void worldIsolationForLearnedSelections() throws Exception {
        stub("isoq",
            loc("near", "stop", "Järfälla", "Iso", JARFALLA, 1000, null),
            loc("kista", "stop", "Kista", "Iso2", KISTA, 1000, null));
        String real = user();
        String test = login("test-passenger@farfartaxi.invalid", "TestPass123!");
        call("POST", "/api/places/selections", test, selection("isoq", "SL", "kista", "Iso2", KISTA), 204);
        assertThat(ids(search(test, "q=isoq").body())).containsExactly("kista", "near");
        assertThat(ids(search(real, "q=isoq").body())).containsExactly("near", "kista");
        stub("isoq2",
            loc("near", "stop", "Järfälla", "Iso", JARFALLA, 1000, null),
            loc("kista", "stop", "Kista", "Iso2", KISTA, 1000, null));
        call("POST", "/api/places/selections", real, selection("isoq2", "SL", "kista", "Iso2", KISTA), 204);
        assertThat(ids(search(real, "q=isoq2").body())).containsExactly("kista", "near");
        assertThat(ids(search(test, "q=isoq2").body())).containsExactly("near", "kista");
    }

    // ---------------------------------------------------------------- context
    @Test
    void contextFallbackChain() throws Exception {
        stub("ctxq",
            loc("jarf", "stop", "Järfälla", "Ctx", JARFALLA, 1000, null),
            loc("sthlm", "stop", "Solna", "Ctx", SOLNA, 1000, null),
            loc("gbg", "stop", "Göteborg", "Ctx", GOTEBORG, 1000, null));
        String u = user();
        // inaccurate GPS (>= 1000 m) is ignored -> default centre
        JsonNode r = search(u, "q=ctxq&lat=57.7089&lon=11.9746&accuracy=1000").body();
        assertThat(r.get("context").asText()).isEqualTo("DEFAULT");
        assertThat(ids(r).get(0)).isEqualTo("jarf");
        // pickup is used next
        r = search(u, "q=ctxq&lat=57.7089&lon=11.9746&accuracy=2500&pickupLat=59.36&pickupLon=18.0").body();
        assertThat(r.get("context").asText()).isEqualTo("PICKUP");
        assertThat(ids(r).get(0)).isEqualTo("sthlm");
        // good GPS wins over pickup
        r = search(u, "q=ctxq&lat=57.7089&lon=11.9746&accuracy=30&pickupLat=59.36&pickupLon=18.0&limit=25").body();
        assertThat(r.get("context").asText()).isEqualTo("GPS");
        assertThat(ids(r).get(0)).isEqualTo("gbg");
        // Home saved place when neither GPS nor pickup
        call("POST", "/api/saved-places", u, Map.of("label", "Hem", "address", "Avenyn 1, Göteborg", "lat", 57.7, "lon", 11.97), 200);
        r = search(u, "q=ctxq&lat=1&lon=1&accuracy=5000&limit=25").body();
        assertThat(r.get("context").asText()).isEqualTo("HOME");
        assertThat(ids(r).get(0)).isEqualTo("gbg");
        assertThat(search(u, "q=ctxq&pickupLat=59.36&pickupLon=18.0").body().get("context").asText()).isEqualTo("PICKUP");
    }

    // ---------------------------------------------------------------- failure / cache
    @Test
    void providerFailureAndTimeoutFallBackToPersonalMatches() throws Exception {
        stub("failq");
        SL_STATUS.put("failq", 500);
        stub("slowq");
        SL_DELAY_MS.put("slowq", 1500);
        SL_STATUS.put("failq", 500);
        String u = user();
        call("POST", "/api/saved-places", u, Map.of("label", "Failq hem", "address", "Vägen 1", "lat", 59.4, "lon", 17.8), 200);
        call("POST", "/api/saved-places", u, Map.of("label", "Slowq hem", "address", "Vägen 2", "lat", 59.4, "lon", 17.8), 200);
        Resp r = search(u, "q=failq");
        assertThat(r.status()).isEqualTo(200);
        assertThat(ids(r.body())).isEmpty();
        assertThat(r.body().get("results")).hasSize(1);
        assertThat(r.body().get("results").get(0).get("provider").asText()).isEqualTo("FAVORITE");
        long t0 = System.nanoTime();
        r = search(u, "q=slowq");
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("results")).hasSize(1);
        assertThat(ms).isLessThan(1400);
        // failures are not cached: once healthy again the provider is asked again
        SL_STATUS.remove("failq");
        SL_BODIES.put("failq", "{\"locations\":[" + loc("ok", "stop", "Järfälla", "Failq", JARFALLA, 1000, null) + "]}");
        assertThat(ids(search(u, "q=failq").body())).containsExactly("ok");
    }

    @Test
    void secondIdenticalSearchIsServedFromCache() throws Exception {
        stubNearFar("cacheq");
        String u = user();
        search(u, "q=cacheq");
        search(u, "q=Cacheq");
        search(u, "q=cacheq&lat=59.4&lon=17.8&accuracy=10");
        assertThat(calls("cacheq")).isEqualTo(1);
    }

    // ---------------------------------------------------------------- nearest stop
    @Test
    void nearestStopFromStubbedSites() throws Exception {
        SITES.set("""
            [{"id":9530,"gid":1080009530,"name":"Kallhälls station","note":"Järfälla","lat":59.4510,"lon":17.8030,"abbreviation":"KHÄ"},
             {"id":1001,"gid":1080001001,"name":"Slussen","lat":59.3200,"lon":18.0720},
             {"id":9531,"gid":1080009531,"name":"Kallhäll norra","lat":59.4600,"lon":17.8100}]""");
        assertThat(nearestStops.reload()).isTrue();
        String u = user();
        Resp r = call("GET", "/api/places/nearest-stop?lat=59.4520&lon=17.8030", u, null, 200);
        assertThat(r.body().get("name").asText()).isEqualTo("Kallhälls station");
        assertThat(r.body().get("area").asText()).isEqualTo("Järfälla");
        assertThat(r.body().get("providerPlaceId").asText()).isEqualTo("1080009530");
        assertThat(r.body().get("distanceM").asInt()).isBetween(100, 130);
        assertThat(r.body().get("lat").asDouble()).isEqualTo(59.4510);
        assertThat(r.body().has("lon")).isTrue();
        // nothing within 2 km
        call("GET", "/api/places/nearest-stop?lat=58.0&lon=14.0", u, null, 204);
        // a failed reload keeps the previous list
        SITES.set("not json");
        assertThat(nearestStops.reload()).isFalse();
        call("GET", "/api/places/nearest-stop?lat=59.4520&lon=17.8030", u, null, 200);
        // never loaded -> empty
        assertThat(new NearestStopService("http://127.0.0.1:9", false, metrics).nearest(59.45, 17.80, false)).isEmpty();
    }

    // ---------------------------------------------------------------- reverse
    @Test
    void reverseMapsNominatimAndRateLimiterFailsFast() throws Exception {
        String u = user();
        Resp r = call("GET", "/api/places/reverse?lat=59.34&lon=18.06", u, null, 200);
        assertThat(r.body().get("provider").asText()).isEqualTo("NOMINATIM");
        assertThat(r.body().get("kind").asText()).isEqualTo("ADDRESS");
        assertThat(r.body().get("name").asText()).isEqualTo("Sveavägen 12");
        assertThat(r.body().get("area").asText()).isEqualTo("Stockholm");
        assertThat(r.body().get("formattedAddress").asText()).isEqualTo("Sveavägen 12, Stockholm");
        assertThat(r.body().get("lat").asDouble()).isEqualTo(59.34);
        long t0 = System.nanoTime();
        Resp second = call("GET", "/api/places/reverse?lat=59.35&lon=18.06", u, null, null);
        assertThat(second.status()).isEqualTo(429);
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(1000);
        assertThat(REVERSE_CALLS.get()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- M3 review fixes
    @Test
    void longSlIdsAreStoredAndLearned() throws Exception {
        String id = "streetID:" + "x".repeat(149) + "@" + "9";
        assertThat(id.length()).isEqualTo(160);
        String u = user();
        call("POST", "/api/places/selections", u, selection("longid", "SL", id, "Lång", KISTA), 204);
        assertThat(selectionRepo.findAll().stream().anyMatch(e -> id.equals(e.getProviderPlaceId()))).isTrue();
        call("POST", "/api/places/selections", u, Map.of("query", "longid", "provider", "SL", "providerPlaceId", "y".repeat(513),
            "name", "n", "lat", 59.0, "lon", 18.0), 400);
    }

    @Test
    void reverseUpstreamFailureIsEmptyNotBadGateway() {
        var failing = new com.farfartaxi.backend.service.NominatimProxyService(
            "http://127.0.0.1:" + NOMINATIM.getAddress().getPort() + "/failing", 0);
        var svc = new com.farfartaxi.backend.places.ReverseGeocodeService(failing, metrics);
        assertThat(svc.reverse(59.34, 18.06, false)).isEmpty();
        var down = new com.farfartaxi.backend.service.NominatimProxyService("http://127.0.0.1:9", 0);
        assertThat(new com.farfartaxi.backend.places.ReverseGeocodeService(down, metrics).reverse(59.34, 18.06, false)).isEmpty();
    }

    @Test
    void nearestStopRetriesInitialLoadUntilFirstSuccess() throws Exception {
        String before = SITES.get();
        try {
            var svc = new NearestStopService("http://127.0.0.1:" + TRANSPORT.getAddress().getPort(), true, metrics);
            SITES.set("not json");
            svc.retryUntilLoaded();
            assertThat(svc.nearest(59.4520, 17.8030, false)).isEmpty();
            SITES.set("[]"); // empty counts as failed too
            svc.retryUntilLoaded();
            assertThat(svc.nearest(59.4520, 17.8030, false)).isEmpty();
            SITES.set("[{\"id\":1,\"gid\":777,\"name\":\"Gid stop\",\"lat\":59.4510,\"lon\":17.8030}]");
            svc.retryUntilLoaded();
            var hit = svc.nearest(59.4520, 17.8030, false).orElseThrow();
            assertThat(hit.providerPlaceId()).isEqualTo("777");
            assertThat(hit.area()).isNull();
            // loaded: the retry job no longer reloads
            SITES.set("[{\"id\":2,\"gid\":888,\"name\":\"Other\",\"lat\":59.4510,\"lon\":17.8030}]");
            svc.retryUntilLoaded();
            assertThat(svc.nearest(59.4520, 17.8030, false).orElseThrow().providerPlaceId()).isEqualTo("777");
        } finally {
            SITES.set(before);
        }
    }

    @Test
    void missingAccuracyIsNotGps() throws Exception {
        stub("noacc", loc("jarf", "stop", "Järfälla", "Noacc", JARFALLA, 1000, null));
        String u = user();
        assertThat(search(u, "q=noacc&lat=57.7089&lon=11.9746").body().get("context").asText()).isEqualTo("DEFAULT");
        assertThat(search(u, "q=noacc&lat=57.7089&lon=11.9746&pickupLat=59.36&pickupLon=18.0").body().get("context").asText())
            .isEqualTo("PICKUP");
        assertThat(search(u, "q=noacc&lat=57.7089&lon=11.9746&accuracy=50").body().get("context").asText()).isEqualTo("GPS");
    }

    @Test
    void dedupeByIdAndTokenPermutation() throws Exception {
        stub("dedq",
            // same id, different names far apart
            loc("same", "stop", "Järfälla", "Dedq Ett", JARFALLA, 1000, null),
            loc("same", "stop", "Kista", "Dedq Tva", KISTA, 1000, null),
            // permutation within 15 m
            loc("p1", "street", "Stockholm", "Dedq / Sveavägen", SOLNA, 1000, null),
            loc("p2", "street", "Stockholm", "Sveavägen / Dedq", new double[] {59.36005, 18.0}, 1000, null),
            // permutation 200 m away is kept; same tokens but different name is not an exact-name dup
            loc("p3", "street", "Stockholm", "Sveavägen / Dedq", new double[] {59.3618, 18.0}, 1000, null));
        List<String> ids = ids(search(user(), "q=dedq&limit=25").body());
        assertThat(ids).hasSize(3).contains("same", "p3");
        assertThat(ids.contains("p1") ^ ids.contains("p2")).isTrue();
    }

    @Test
    void concurrentIdenticalSelectionsUpsertWithoutError() throws Exception {
        String u = user();
        var pool = Executors.newFixedThreadPool(6);
        try {
            List<java.util.concurrent.Future<Resp>> fs = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                fs.add(pool.submit(() -> call("POST", "/api/places/selections", u, selection("racey", "SL", "race-1", "Race", KISTA), null)));
            }
            for (var f : fs) {
                assertThat(f.get().status()).isEqualTo(204);
            }
        } finally {
            pool.shutdown();
        }
        var rows = selectionRepo.findAll().stream().filter(e -> "race-1".equals(e.getProviderPlaceId())).toList();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getScore()).isGreaterThan(5.9);
    }

    @Test
    void nightlyCleanupAlsoRemovesTestUserSelections() throws Exception {
        String real = user();
        String test = login("test-passenger@farfartaxi.invalid", "TestPass123!");
        call("POST", "/api/places/selections", real, selection("cleanq", "SL", "clean-real", "R", KISTA), 204);
        call("POST", "/api/places/selections", test, selection("cleanq", "SL", "clean-test", "T", KISTA), 204);
        cleanupJob.cleanup();
        var left = selectionRepo.findAll().stream().map(PlaceSelectionEntity::getProviderPlaceId).toList();
        assertThat(left).contains("clean-real").doesNotContain("clean-test");
    }

    // ---------------------------------------------------------------- auth
    @Test
    void placesRequireApprovedUserAndOldGeocodeIsGone() throws Exception {
        for (String p : new String[] {"/api/places/search?q=abc", "/api/places/nearest-stop?lat=59&lon=18", "/api/places/reverse?lat=59&lon=18"}) {
            assertThat(call("GET", p, null, null, null).status()).as(p).isIn(401, 403);
        }
        assertThat(call("POST", "/api/places/selections", null, selection("abc", "SL", "x", "x", JARFALLA), null).status()).isIn(401, 403);
        call("POST", "/api/auth/register", null, Map.of("email", "places-pending@test.local", "password", "Password123!", "fullName", "P"), 200);
        String pending = login("places-pending@test.local", "Password123!");
        for (String p : new String[] {"/api/places/search?q=abc", "/api/places/nearest-stop?lat=59&lon=18", "/api/places/reverse?lat=59&lon=18"}) {
            assertThat(call("GET", p, pending, null, null).status()).as(p).isEqualTo(403);
        }
        assertThat(call("POST", "/api/places/selections", pending, selection("abc", "SL", "x", "x", JARFALLA), null).status()).isEqualTo(403);
        assertThat(call("GET", "/api/public/geocode/search?q=abc", null, null, null).status()).isEqualTo(404);
        assertThat(call("GET", "/api/public/geocode/reverse?lat=59&lon=18", null, null, null).status()).isEqualTo(404);
        assertThat(call("GET", "/api/places/search?q=" + "x".repeat(101), user(), null, null).status()).isEqualTo(400);
    }

    // ---------------------------------------------------------------- helpers
    Resp search(String token, String query) throws Exception {
        return call("GET", "/api/places/search?" + query, token, null, 200);
    }

    static List<String> ids(JsonNode response) {
        List<String> out = new ArrayList<>();
        response.get("results").forEach(n -> {
            if ("SL".equals(n.get("provider").asText())) {
                out.add(n.get("providerPlaceId").asText());
            }
        });
        return out;
    }

    static Map<String, Object> selection(String q, String provider, String id, String name, double[] c) {
        Map<String, Object> m = new HashMap<>();
        m.put("query", q);
        m.put("provider", provider);
        m.put("providerPlaceId", id);
        m.put("name", name);
        m.put("lat", c[0]);
        m.put("lon", c[1]);
        return m;
    }

    /** A fresh approved real passenger. */
    String user() throws Exception {
        String email = "places-" + (++seq) + "-" + System.nanoTime() + "@test.local";
        call("POST", "/api/auth/register", null, Map.of("email", email, "password", "Password123!", "fullName", email), 200);
        long id = users.findByEmailIgnoreCase(email).orElseThrow().getId();
        call("POST", "/api/admin/users/" + id + "/approve", adminToken, null, 200);
        return login(email, "Password123!");
    }

    String login(String email, String password) throws Exception {
        return call("POST", "/api/auth/login", null, Map.of("email", email, "password", password), 200).body().get("token").asText();
    }

    Resp call(String method, String path, String token, Object payload, Integer expected) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json");
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
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
