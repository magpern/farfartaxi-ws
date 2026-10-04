package com.farfartaxi.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.farfartaxi.backend.model.AppEventEntity;
import com.farfartaxi.backend.repo.AppEventRepository;
import com.farfartaxi.backend.service.AppEventRetentionJob;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** M8: POST /api/telemetry/events, app_events retention, and the new ride/push/app_events metrics. */
class TelemetryIntegrationTest extends M1TestSupport {
    @Autowired private AppEventRepository eventsRepo;
    @Autowired private AppEventRetentionJob retention;
    @Autowired private MeterRegistry registry;

    private static Map<String, Object> ev(String name, Map<String, Object> props) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("name", name);
        m.put("props", props);
        m.put("ts", 1_800_000_000_000L);
        return m;
    }

    private Resp post(String token, List<Map<String, Object>> events, Integer expected) throws Exception {
        return call("POST", "/api/telemetry/events", token, Map.of("sessionId", "sess-1", "events", events), expected);
    }

    private double counter(String name, String... tags) {
        var c = registry.find(name).tags(tags).counter();
        return c == null ? 0 : c.count();
    }

    private List<AppEventEntity> rowsOf(long userId, String name) {
        return eventsRepo.findAll().stream().filter(e -> e.getUserId() != null && e.getUserId() == userId && name.equals(e.getName())).toList();
    }

    @Test
    void storesAllowlistedPropsOnlyAndCountsMetric() throws Exception {
        double before = counter("farfartaxi.app_events", "name", "booking_created", "world", "real");
        Resp r = post(p1, List.of(
            ev("booking_created", Map.of("kind", "NOW", "source", "home", "extra", "x", "lat", "ignored-by-reject")),
            ev("booking_created", Map.of("kind", "SCHEDULED", "source", "search", "bogus", 5, "ride", Map.of("a", 1)))), 200);
        // first event has key "lat" -> rejected entirely; second stored without unknown keys
        assertThat(r.body().get("accepted").asInt()).isEqualTo(1);
        assertThat(r.body().get("dropped").asInt()).isEqualTo(1);
        List<AppEventEntity> rows = rowsOf(p1Id, "booking_created");
        assertThat(rows).hasSize(1);
        assertThat(om.readTree(rows.get(0).getProps())).isEqualTo(om.readTree("{\"kind\":\"SCHEDULED\",\"source\":\"search\"}"));
        assertThat(rows.get(0).isTest()).isFalse();
        assertThat(rows.get(0).getSessionId()).isEqualTo("sess-1");
        assertThat(rows.get(0).getClientTs()).isEqualTo(Instant.ofEpochMilli(1_800_000_000_000L));
        assertThat(counter("farfartaxi.app_events", "name", "booking_created", "world", "real")).isEqualTo(before + 1);
    }

    @Test
    void unknownKeysAreDroppedAndBadTypesOrValuesAreSkipped() throws Exception {
        post(p2, List.of(
            ev("search_result_selected", Map.of("queryLength", 5, "provider", "SL", "kind", "STOP", "rank", "first", "latencyMs", 120, "foo", "bar")),
            ev("push_permission", Map.of("state", "maybe")),
            ev("frontend_error", Map.of("message", "boom at https://farfartaxi.pernemark.se/app/x?token=SECRET#frag", "type", "TypeError",
                "source", "https://farfartaxi.pernemark.se/app/x?token=SECRET", "code", "RENDER_ERROR", "line", 12,
                "fingerprint", "0123456789abcdef"))), 200);
        JsonNode sel = om.readTree(rowsOf(p2Id, "search_result_selected").get(0).getProps());
        assertThat(sel.fieldNames()).toIterable().containsExactlyInAnyOrder("queryLength", "provider", "kind", "latencyMs");
        assertThat(rowsOf(p2Id, "push_permission").get(0).getProps()).isNull();
        String err = rowsOf(p2Id, "frontend_error").get(0).getProps();
        assertThat(om.readTree(err)).isEqualTo(om.readTree(
            "{\"type\":\"TypeError\",\"code\":\"RENDER_ERROR\",\"line\":12,\"fingerprint\":\"0123456789abcdef\"}"));
        assertThat(err).doesNotContain("boom").doesNotContain("SECRET").doesNotContain("farfartaxi.pernemark.se");
    }

    @Test
    void addressCoordinateAndContactLookingPropsRejectTheEvent() throws Exception {
        long before = eventsRepo.count();
        double dropped = counter("farfartaxi.app_events.dropped", "reason", "rejected", "world", "real");
        Resp r = post(p1, List.of(
            ev("search_started", Map.of("lat", 59.3)),
            ev("search_started", Map.of("queryLength", 59.3293)),
            ev("search_started", Map.of("queryLength", 59.32930)),
            ev("search_started", Map.of("fullName", "Anna")),
            ev("search_started", Map.of("homeAddress", "x")),
            ev("search_started", Map.of("userEmail", "a")),
            ev("search_started", Map.of("provider", "59.33291,18.06860")),
            ev("search_started", Map.of("provider", "59.332912")),
            ev("frontend_error", Map.of("source", "59.42351")),
            ev("search_started", Map.of("phone", "070")),
            ev("search_started", Map.of("latencyMs", 80, "queryLength", 3.5))), 200); // latencyMs is not "lat"; 3.5 has 1 decimal -> ok (not int: skipped)
        assertThat(r.body().get("accepted").asInt()).isEqualTo(1);
        assertThat(r.body().get("dropped").asInt()).isEqualTo(10);
        assertThat(eventsRepo.count()).isEqualTo(before + 1);
        assertThat(counter("farfartaxi.app_events.dropped", "reason", "rejected", "world", "real")).isEqualTo(dropped + 10);
        for (AppEventEntity e : eventsRepo.findAll()) {
            if (e.getProps() != null) {
                assertThat(e.getProps()).doesNotContain("59.3").doesNotContain("Anna").doesNotContain("@");
            }
        }
    }

    @Test
    void embeddedCoordinatesAreNeverStoredAndEnumsAreEnforced() throws Exception {
        post(p1, List.of(
            ev("frontend_error", Map.of("message", "GET /api/places/reverse?lat=59.42351&lon=17.91234 failed")),
            ev("frontend_error", Map.of("message", "Invalid LatLng object: (59.42351, NaN)", "source", "LiveRideMap")),
            ev("frontend_error", Map.of("message", "pos 59.42351 anna@example.com")),
            ev("frontend_error", Map.of("fingerprint", "NOT-HEX", "source", "a b", "type", "Boom", "code", "x")),
            ev("search_started", Map.of("provider", "Google", "kind", "STOP", "queryLength", 3)),
            ev("ride_cancelled", Map.of("kind", "NOW", "status", "because I said so")),
            ev("push_opened", Map.of("kind", "NOT_A_KIND"))), 200);
        for (AppEventEntity e : eventsRepo.findAll()) {
            if (e.getProps() != null) {
                assertThat(e.getProps()).doesNotContain("59.4").doesNotContain("59,4").doesNotContain("17.91").doesNotContain("lat=");
                assertThat(e.getProps()).doesNotContain("Google").doesNotContain("because").doesNotContain("NOT_A_KIND");
            }
        }
        assertThat(eventsRepo.findAll()).noneMatch(e -> e.getProps() != null && (e.getProps().contains("LatLng") || e.getProps().contains("message")));
    }

    @Test
    void unknownNamesAreDroppedAndCountedNot400() throws Exception {
        double before = counter("farfartaxi.app_events.dropped", "reason", "unknown_name", "world", "real");
        Resp r = post(p1, List.of(ev("page_view", Map.of()), ev("DROP TABLE", Map.of()), ev("push_opened", Map.of("kind", "ACCEPTED"))), 200);
        assertThat(r.body().get("accepted").asInt()).isEqualTo(1);
        assertThat(r.body().get("dropped").asInt()).isEqualTo(2);
        assertThat(counter("farfartaxi.app_events.dropped", "reason", "unknown_name", "world", "real")).isEqualTo(before + 2);
        assertThat(eventsRepo.findAll()).noneMatch(e -> e.getName().equals("page_view"));
    }

    @Test
    void limitsAndAuth() throws Exception {
        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            many.add(ev("push_opened", Map.of()));
        }
        assertThat(post(p2, many, 400).code()).isEqualTo("TOO_MANY_EVENTS");
        // > 32 KB
        Resp big = call("POST", "/api/telemetry/events", p2, Map.of("events", List.of(ev("frontend_error", Map.of("message", "x".repeat(40_000)))), "sessionId", "s"), 413);
        assertThat(big.code()).isEqualTo("PAYLOAD_TOO_LARGE");
        call("POST", "/api/telemetry/events", p2, "not json{", 400);
        call("POST", "/api/telemetry/events", p2, Map.of("nope", 1), 400);
        // unauthenticated
        assertThat(post(null, List.of(ev("push_opened", Map.of())), null).status()).isIn(401, 403);
        // a pending (unapproved) account is refused
        call("POST", "/api/auth/register", null, Map.of("email", "tel-pending@test.local", "password", PW, "fullName", "P"), 200);
        String pending = login("tel-pending@test.local", PW);
        call("POST", "/api/telemetry/events", pending, Map.of("events", List.of(ev("push_opened", Map.of()))), 403);
    }

    @Test
    void testUsersAreFlaggedAndTaggedAsTestWorld() throws Exception {
        double before = counter("farfartaxi.app_events", "name", "search_empty", "world", "test");
        post(tp, List.of(ev("search_empty", Map.of("queryLength", 4))), 200);
        assertThat(rowsOf(tpId, "search_empty").get(0).isTest()).isTrue();
        assertThat(counter("farfartaxi.app_events", "name", "search_empty", "world", "test")).isEqualTo(before + 1);
    }

    @Test
    void rateLimitIs600PerUserPerHour() throws Exception {
        String t = account("tel-rate@test.local", false);
        long uid = idOf("tel-rate@test.local");
        List<Map<String, Object>> batch = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            batch.add(ev("push_opened", Map.of()));
        }
        for (int i = 0; i < 12; i++) {
            assertThat(post(t, batch, 200).body().get("accepted").asInt()).isEqualTo(50);
        }
        Resp over = post(t, batch, 200);
        assertThat(over.body().get("accepted").asInt()).isZero();
        assertThat(over.body().get("dropped").asInt()).isEqualTo(50);
        assertThat(rowsOf(uid, "push_opened")).hasSize(600);
        clock.advance(Duration.ofMinutes(61));
        assertThat(post(t, batch, 200).body().get("accepted").asInt()).isEqualTo(50);
    }

    @Test
    void invalidEventsDoNotConsumeQuotaAndValidOnesAreStillCapped() throws Exception {
        String t = account("tel-quota@test.local", false);
        long uid = idOf("tel-quota@test.local");
        List<Map<String, Object>> invalid = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            invalid.add(i % 2 == 0 ? ev("not_an_event", Map.of()) : ev("search_started", Map.of("lat", 59.3)));
        }
        for (int i = 0; i < 12; i++) { // 600 invalid events
            Resp r = post(t, invalid, 200);
            assertThat(r.body().get("accepted").asInt()).isZero();
            assertThat(r.body().get("dropped").asInt()).isEqualTo(50);
        }
        List<Map<String, Object>> valid = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            valid.add(ev("push_opened", Map.of()));
        }
        // 12 requests used so far; 12 more valid batches fill the 600 quota exactly
        for (int i = 0; i < 12; i++) {
            assertThat(post(t, valid, 200).body().get("accepted").asInt()).isEqualTo(50);
        }
        assertThat(post(t, valid, 200).body().get("accepted").asInt()).isZero();
        assertThat(rowsOf(uid, "push_opened")).hasSize(600);
    }

    @Test
    void telemetryRequestsArePerUserLimited() throws Exception {
        String t = account("tel-req@test.local", false);
        for (int i = 0; i < 120; i++) {
            post(t, List.of(), 200);
        }
        Resp r = post(t, List.of(), 429);
        assertThat(r.code()).isEqualTo("RATE_LIMITED");
        clock.advance(Duration.ofMinutes(61));
        post(t, List.of(), 200);
    }

    @Test
    void retentionDeletesRowsOlderThan180Days() {
        Instant now = clock.instant();
        AppEventEntity old = row("retention_old", now.minus(Duration.ofDays(181)));
        AppEventEntity fresh = row("retention_new", now.minus(Duration.ofDays(179)));
        eventsRepo.saveAll(List.of(old, fresh));
        assertThat(retention.purge()).isGreaterThanOrEqualTo(1);
        assertThat(eventsRepo.findById(old.getId())).isEmpty();
        assertThat(eventsRepo.findById(fresh.getId())).isPresent();
    }

    private static AppEventEntity row(String name, Instant createdAt) {
        AppEventEntity e = new AppEventEntity();
        e.setName(name);
        e.setCreatedAt(createdAt);
        return e;
    }

    @Test
    void rideTimersAndNoDriverCounter() throws Exception {
        long tta = timerCount("farfartaxi.ride.time_to_accept");
        long pw = timerCount("farfartaxi.ride.pickup_wait");
        long id = bookNow(p1);
        clock.advance(Duration.ofSeconds(90));
        acceptOk(d1, id);
        drive(d1, id, "start");
        drive(d1, id, "arrive");
        clock.advance(Duration.ofSeconds(240));
        drive(d1, id, "pickup");
        assertThat(timerCount("farfartaxi.ride.time_to_accept")).isEqualTo(tta + 1);
        assertThat(timerCount("farfartaxi.ride.pickup_wait")).isEqualTo(pw + 1);
        Timer t = registry.find("farfartaxi.ride.pickup_wait").tag("world", "real").timer();
        assertThat(t.totalTime(TimeUnit.SECONDS)).isGreaterThanOrEqualTo(240.0);
        assertThat(t.takeSnapshot().histogramCounts()).isNotEmpty(); // percentile histogram buckets published

        double nd = counter("farfartaxi.ride.no_driver", "world", "real");
        long id2 = bookAt(at("2027-05-04", "10:00"));
        declineAllReal(id2);
        assertThat(status(id2)).isEqualTo("NO_DRIVER");
        assertThat(counter("farfartaxi.ride.no_driver", "world", "real")).isEqualTo(nd + 1);
    }

    private long timerCount(String name) {
        return registry.find(name).tag("world", "real").timers().stream().mapToLong(Timer::count).sum();
    }

    @Test
    void pushSubscriptionGaugeIsRegisteredPerWorld() {
        assertThat(registry.find("farfartaxi.push.subscriptions").tag("world", "real").gauge()).isNotNull();
        assertThat(registry.find("farfartaxi.push.subscriptions").tag("world", "test").gauge()).isNotNull();
    }
}
