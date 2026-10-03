package com.farfartaxi.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.farfartaxi.backend.model.UserEntity;
import com.farfartaxi.backend.service.Geo;
import com.farfartaxi.backend.service.PositionRetentionJob;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** M7 live tracking: ETA recompute rule, stale flag, 1 h retention, share links. All time via the controllable Clock. */
class LiveTrackingIntegrationTest extends M1TestSupport {
    @Autowired PositionRetentionJob retention;

    private static final String OSRM_10_MIN = "{\"routes\":[{\"duration\":600.0,\"distance\":5000.0}]}";
    private static final String OSRM_20_MIN = "{\"routes\":[{\"duration\":1200.0,\"distance\":9000.0}]}";
    private static int ipSeq = 0;

    private static String freshIp() {
        return "10.77." + (++ipSeq / 250) + "." + (ipSeq % 250 + 1);
    }

    private long rideInProgress(String step) throws Exception {
        long id = bookAt(BASE.plus(Duration.ofDays(5)));
        acceptOk(d1, id);
        drive(d1, id, "start");
        if (!step.equals("start")) {
            drive(d1, id, "arrive");
        }
        if (step.equals("pickup")) {
            drive(d1, id, "pickup");
        }
        return id;
    }

    private JsonNode loc(long id, double lat, double lon) throws Exception {
        return call("POST", "/api/driver/rides/" + id + "/location", d1,
            Map.of("lat", lat, "lon", lon, "accuracy", 12.5), 200).body();
    }

    private JsonNode pub(String token, int expected, String ip) throws Exception {
        return call("GET", "/api/public/share/" + token, null, null, expected, "X-Forwarded-For", ip).body();
    }

    private void verifyOsrmCalls(int n) {
        verify(osrm, times(n)).drivingRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any());
    }

    // ------------------------------------------------------------------ ETA

    @Test
    void etaRecomputeRuleAndTargetSwitch() throws Exception {
        when(osrm.drivingRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any())).thenReturn(OSRM_10_MIN);
        long id = rideInProgress("start");
        double lat = 59.3000, lon = 18.0000;

        JsonNode first = loc(id, lat, lon);
        verifyOsrmCalls(1);
        assertThat(first.get("etaMinutes").asInt()).isEqualTo(10);
        assertThat(first.get("etaTarget").asText()).isEqualTo("PICKUP");
        assertThat(first.get("lastLocationAccuracyM").asDouble()).isEqualTo(12.5);

        // < 30 s, moved far: kept
        when(osrm.drivingRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any())).thenReturn(OSRM_20_MIN);
        clock.advance(Duration.ofSeconds(10));
        JsonNode quick = loc(id, lat + 0.01, lon);
        verifyOsrmCalls(1);
        assertThat(quick.get("etaMinutes").asInt()).isEqualTo(10);
        assertThat(quick.get("lastDriverLat").asDouble()).isEqualTo(lat + 0.01); // position itself is always stored

        // > 30 s but moved < 100 m since the ETA position: kept
        clock.advance(Duration.ofSeconds(35));
        assertThat(loc(id, lat + 0.0005, lon).get("etaMinutes").asInt()).isEqualTo(10);
        verifyOsrmCalls(1);

        // > 30 s AND > 100 m: recomputed
        assertThat(loc(id, lat + 0.005, lon).get("etaMinutes").asInt()).isEqualTo(20);
        verifyOsrmCalls(2);

        // status change recomputes immediately (no time/distance passed)
        when(osrm.drivingRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any())).thenReturn(OSRM_10_MIN);
        clock.advance(Duration.ofSeconds(1));
        drive(d1, id, "arrive");
        assertThat(loc(id, lat + 0.005, lon).get("etaMinutes").asInt()).isEqualTo(10);
        verifyOsrmCalls(3);

        // after pickup the target is the destination
        clock.advance(Duration.ofSeconds(1));
        drive(d1, id, "pickup");
        JsonNode toDest = loc(id, lat + 0.005, lon);
        verifyOsrmCalls(4);
        assertThat(toDest.get("etaTarget").asText()).isEqualTo("DESTINATION");
        assertThat(ride(p1, id).get("etaTarget").asText()).isEqualTo("DESTINATION");
    }

    @Test
    void osrmFailureFallsBackToHaversine() throws Exception {
        // the OSRM mock returns null (failure) by default
        long id = rideInProgress("start");
        JsonNode r = loc(id, 59.2800, 18.0686);
        double km = Geo.haversineMeters(59.2800, 18.0686, 59.3293, 18.0686) / 1000.0;
        int expected = Math.max(1, (int) Math.round(km / 35.0 * 60));
        assertThat(expected).isGreaterThan(3);
        assertThat(r.get("etaMinutes").asInt()).isEqualTo(expected);
        assertThat(r.get("etaTarget").asText()).isEqualTo("PICKUP");
    }

    @Test
    void etaFivePushUsesStoredEta() throws Exception {
        // 20 min stored; a nearby fix within 30 s must not recompute, so no ETA_5MIN despite being at the pickup
        when(osrm.drivingRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any())).thenReturn(OSRM_20_MIN);
        long id = rideInProgress("start");
        loc(id, 59.2800, 18.0686);
        clock.advance(Duration.ofSeconds(5));
        JsonNode r = loc(id, 59.3293, 18.0686);
        assertThat(r.get("etaMinutes").asInt()).isEqualTo(20);
        assertThat(sentRepo.findAll().stream().anyMatch(s -> s.getRideId().equals(id) && "ETA_5MIN".equals(s.getKind()))).isFalse();
    }

    // ------------------------------------------------------------------ stale

    @Test
    void locationStaleAfterTwoMinutes() throws Exception {
        long id = bookAt(BASE.plus(Duration.ofDays(5)));
        acceptOk(d1, id);
        assertThat(ride(p1, id).get("locationStale").asBoolean()).isFalse(); // ACCEPTED: not expected to send yet
        drive(d1, id, "start");
        assertThat(ride(p1, id).get("locationStale").asBoolean()).isTrue(); // EN_ROUTE, nothing received
        loc(id, 59.30, 18.00);
        assertThat(ride(p1, id).get("locationStale").asBoolean()).isFalse();
        clock.advance(Duration.ofSeconds(119));
        assertThat(ride(p1, id).get("locationStale").asBoolean()).isFalse();
        clock.advance(Duration.ofSeconds(2));
        assertThat(ride(p1, id).get("locationStale").asBoolean()).isTrue();
        loc(id, 59.30, 18.00);
        assertThat(ride(p1, id).get("locationStale").asBoolean()).isFalse();
    }

    // ------------------------------------------------------------------ retention

    @Test
    void positionClearedExactlyOneHourAfterCompletion() throws Exception {
        long id = rideInProgress("pickup");
        loc(id, 59.31, 18.05);
        drive(d1, id, "complete");
        Instant ended = clock.instant();
        assertThat(ride(p1, id).get("lastDriverLat").isNull()).isFalse();

        clock.set(ended.plus(Duration.ofHours(1)).minusSeconds(1));
        retention.clearExpiredPositions();
        JsonNode before = ride(p1, id);
        assertThat(before.get("lastDriverLat").isNull()).isFalse();
        assertThat(before.get("lastLocationAccuracyM").isNull()).isFalse();
        assertThat(rideRepo.findById(id).orElseThrow().getEtaLat()).isNotNull();

        clock.set(ended.plus(Duration.ofHours(1)));
        retention.clearExpiredPositions();
        JsonNode after = ride(p1, id);
        assertThat(after.get("lastDriverLat").isNull()).isTrue();
        assertThat(after.get("lastDriverLon").isNull()).isTrue();
        assertThat(after.get("lastLocationAccuracyM").isNull()).isTrue();
        assertThat(rideRepo.findById(id).orElseThrow().getEtaLat()).isNull();
        assertThat(rideRepo.findById(id).orElseThrow().getEtaLon()).isNull();
    }

    @Test
    void positionClearedOneHourAfterCancellation() throws Exception {
        long id = rideInProgress("start");
        loc(id, 59.31, 18.05);
        call("POST", "/api/rides/" + id + "/cancel", p1, Map.of("confirm", true), 200);
        Instant ended = clock.instant();
        assertThat(rideRepo.findById(id).orElseThrow().getCancelledAt()).isEqualTo(ended);

        clock.set(ended.plus(Duration.ofHours(1)).minusSeconds(1));
        retention.clearExpiredPositions();
        assertThat(rideRepo.findById(id).orElseThrow().getLastDriverLat()).isNotNull();
        clock.set(ended.plus(Duration.ofHours(1)));
        retention.clearExpiredPositions();
        assertThat(rideRepo.findById(id).orElseThrow().getLastDriverLat()).isNull();
        assertThat(rideRepo.findById(id).orElseThrow().getEtaLat()).isNull();
    }

    @Test
    void activeRideKeepsPositionRegardlessOfAge() throws Exception {
        long id = rideInProgress("start");
        loc(id, 59.31, 18.05);
        clock.advance(Duration.ofHours(6));
        retention.clearExpiredPositions();
        assertThat(rideRepo.findById(id).orElseThrow().getLastDriverLat()).isNotNull();
    }

    // ------------------------------------------------------------------ share

    @Test
    void shareViewIsAnonymizedAndWorksWithoutAuth() throws Exception {
        String pTok = account("m7-lisa@test.local", false);
        long pId = idOf("m7-lisa@test.local");
        setName(pId, "Lisa Andersson");
        setPhone(pId, "070-555 66 77");
        String dTok = newDriver();
        long dId = users.findAll().stream().filter(u -> u.getEmail().startsWith("m1-late")).mapToLong(UserEntity::getId).max().orElseThrow();
        setName(dId, "Farfar Svensson");
        setPhone(dId, "070-888 99 00");

        Map<String, Object> body = rideBody(BASE.plus(Duration.ofDays(5)));
        body.put("pickupNote", "SECRETNOTE-ring-dorrklockan");
        long id = call("POST", "/api/rides", pTok, body, 200).body().get("id").asLong();
        call("POST", "/api/driver/rides/" + id + "/accept", dTok, Map.of("confirmProximity", true), 200);
        call("POST", "/api/driver/rides/" + id + "/start", dTok, null, 200);
        call("POST", "/api/driver/rides/" + id + "/location", dTok, Map.of("lat", 59.30, "lon", 18.0, "accuracy", 8.0), 200);

        JsonNode created = call("POST", "/api/rides/" + id + "/share", pTok, null, 200).body();
        String token = created.get("token").asText();
        assertThat(token).matches("[A-Za-z0-9_-]{22}"); // 128 bit, base64url, no padding
        assertThat(created.get("url").asText()).isEqualTo("https://farfartaxi.pernemark.se/dela/" + token);
        assertThat(created.has("expiresAt")).isTrue();
        // creating again returns the same live link
        assertThat(call("POST", "/api/rides/" + id + "/share", pTok, null, 200).body().get("token").asText()).isEqualTo(token);

        JsonNode v = pub(token, 200, freshIp());
        assertThat(v.get("passengerFirstName").asText()).isEqualTo("Lisa");
        assertThat(v.get("driverFirstName").asText()).isEqualTo("Farfar");
        assertThat(v.get("status").asText()).isEqualTo("EN_ROUTE");
        assertThat(v.get("statusLabelKey").asText()).isNotBlank();
        assertThat(v.get("pickup").get("label").asText()).isEqualTo("Start");
        assertThat(v.get("destination").get("label").asText()).isEqualTo("Goal");
        assertThat(v.get("driver").get("lat").asDouble()).isEqualTo(59.30);
        assertThat(v.get("driver").get("accuracyM").asDouble()).isEqualTo(8.0);
        assertThat(v.get("locationStale").asBoolean()).isFalse();
        assertThat(v.get("etaTarget").asText()).isEqualTo("PICKUP");

        Set<String> keys = new TreeSet<>();
        v.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactlyInAnyOrder("passengerFirstName", "driverFirstName", "status", "statusLabelKey",
            "scheduledAt", "pickup", "destination", "driver", "etaMinutes", "etaTarget", "locationStale");
        String raw = v.toString();
        assertThat(raw).doesNotContain("070-", "SECRETNOTE", "Andersson", "Svensson", "m7-lisa", "m1-late",
            "Id\"", "\"id\"", "\"rideId\"");
        assertThat(raw).doesNotContain("\"" + id + "\"").doesNotContain(":" + id + ",").doesNotContain(":" + id + "}");
    }

    @Test
    void shareStaysValidWhileActiveAndExpiresOneHourAfterRideEnd() throws Exception {
        long id = rideInProgress("pickup");
        String token = call("POST", "/api/rides/" + id + "/share", p1, null, 200).body().get("token").asText();
        clock.advance(Duration.ofHours(9)); // well past the old 8 h TTL
        pub(token, 200, freshIp());

        drive(d1, id, "complete");
        Instant ended = clock.instant();
        clock.set(ended.plus(Duration.ofHours(1)).minusSeconds(1));
        pub(token, 200, freshIp());
        clock.set(ended.plus(Duration.ofHours(1)));
        pub(token, 410, freshIp());
        // and no new link for a finished ride
        call("POST", "/api/rides/" + id + "/share", p1, null, 409);
    }

    @Test
    void shareExpiresOneHourAfterCancellation() throws Exception {
        long id = bookAt(BASE.plus(Duration.ofDays(5)));
        String token = call("POST", "/api/rides/" + id + "/share", p1, null, 200).body().get("token").asText();
        pub(token, 200, freshIp());
        call("POST", "/api/rides/" + id + "/cancel", p1, null, 200);
        Instant ended = clock.instant();
        clock.set(ended.plus(Duration.ofHours(1)).minusSeconds(1));
        pub(token, 200, freshIp());
        clock.set(ended.plus(Duration.ofHours(1)));
        pub(token, 410, freshIp());
    }

    @Test
    void revokeGives410AndUnknownGives404() throws Exception {
        long id = bookAt(BASE.plus(Duration.ofDays(5)));
        String token = call("POST", "/api/rides/" + id + "/share", p1, null, 200).body().get("token").asText();
        pub(token, 200, freshIp());
        call("DELETE", "/api/rides/" + id + "/share", p1, null, 200);
        pub(token, 410, freshIp());
        pub("does-not-exist-0123456789", 404, freshIp());
        // sharing again yields a different, working token
        String again = call("POST", "/api/rides/" + id + "/share", p1, null, 200).body().get("token").asText();
        assertThat(again).isNotEqualTo(token);
        pub(again, 200, freshIp());
    }

    @Test
    void onlyThePassengerInTheSameWorldCanCreateOrRevoke() throws Exception {
        long id = bookAt(BASE.plus(Duration.ofDays(5)));
        assertThat(call("POST", "/api/rides/" + id + "/share", p2, null, null).status()).isEqualTo(403);
        assertThat(call("DELETE", "/api/rides/" + id + "/share", p2, null, null).status()).isEqualTo(403);
        assertThat(call("POST", "/api/rides/" + id + "/share", tp, null, null).status()).isEqualTo(404); // other world
        assertThat(call("DELETE", "/api/rides/" + id + "/share", tp, null, null).status()).isEqualTo(404);
        assertThat(call("POST", "/api/rides/" + id + "/share", d1, null, null).status()).isEqualTo(403);
        assertThat(call("POST", "/api/rides/" + id + "/share", null, null, null).status()).isEqualTo(401);
    }

    @Test
    void publicEndpointIsRateLimitedPerIp() throws Exception {
        long id = bookAt(BASE.plus(Duration.ofDays(5)));
        String token = call("POST", "/api/rides/" + id + "/share", p1, null, 200).body().get("token").asText();
        String ip = freshIp();
        for (int i = 0; i < 60; i++) {
            pub(token, 200, ip);
        }
        pub(token, 429, ip);
        pub(token, 200, freshIp()); // another client is unaffected
        clock.advance(Duration.ofMinutes(1)); // next window
        pub(token, 200, ip);
    }

    @Test
    void oldShareEndpointIsGone() throws Exception {
        long id = bookAt(BASE.plus(Duration.ofDays(5)));
        String token = call("POST", "/api/rides/" + id + "/share", p1, null, 200).body().get("token").asText();
        call("GET", "/api/rides/share/" + token, p1, null, 404);
        assertThat(call("GET", "/api/rides/share/" + token, null, null, null).status()).isIn(401, 403, 404);
    }

    private void setName(long userId, String name) {
        UserEntity u = users.findById(userId).orElseThrow();
        u.setFullName(name);
        users.save(u);
    }
}
