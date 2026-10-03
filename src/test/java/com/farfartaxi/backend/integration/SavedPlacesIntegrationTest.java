package com.farfartaxi.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** M4: saved places CRUD/PATCH/reorder, on-behalf management, recents. */
class SavedPlacesIntegrationTest extends M1TestSupport {
    private static int fresh = 0;
    private String u, other, drv;
    private long uId, otherId;

    @BeforeEach
    void users_() throws Exception {
        int n = ++fresh;
        u = account("m4-u" + n + "@test.local", false);
        other = account("m4-o" + n + "@test.local", false);
        drv = account("m4-d" + n + "@test.local", true);
        setAvailability(drv, false, "2000-01-01", "2099-12-31");
        uId = idOf("m4-u" + n + "@test.local");
        otherId = idOf("m4-o" + n + "@test.local");
    }

    private Map<String, Object> place(String label, String kind, double lat) {
        Map<String, Object> m = new HashMap<>();
        m.put("label", label);
        m.put("address", label + "vägen 1, Stad");
        m.put("lat", lat);
        m.put("lon", 18.0);
        if (kind != null) {
            m.put("kind", kind);
        }
        return m;
    }

    private JsonNode add(String token, String suffix, Map<String, Object> body) throws Exception {
        return call("POST", "/api/saved-places" + suffix, token, body, 200).body();
    }

    private List<String> labels(String token, String suffix) throws Exception {
        List<String> out = new ArrayList<>();
        call("GET", "/api/saved-places" + suffix, token, null, 200).body().forEach(n -> out.add(n.get("label").asText()));
        return out;
    }

    @Test
    void crudPatchAndReorder() throws Exception {
        Map<String, Object> full = place("Skolan", "SCHOOL", 59.1);
        full.put("icon", "school");
        full.put("provider", "SL");
        full.put("providerPlaceId", "123");
        full.put("formattedAddress", "Skolvägen 1, Stad");
        JsonNode a = add(u, "", full);
        assertThat(a.get("kind").asText()).isEqualTo("SCHOOL");
        assertThat(a.get("icon").asText()).isEqualTo("school");
        assertThat(a.get("provider").asText()).isEqualTo("SL");
        assertThat(a.get("providerPlaceId").asText()).isEqualTo("123");
        assertThat(a.get("formattedAddress").asText()).isEqualTo("Skolvägen 1, Stad");
        JsonNode b = add(u, "", place("Mormor", null, 59.2));
        assertThat(b.get("kind").asText()).isEqualTo("OTHER");
        long aId = a.get("id").asLong();
        long bId = b.get("id").asLong();

        JsonNode patched = call("PATCH", "/api/saved-places/" + aId, u, Map.of("label", "Skolan 2", "kind", "SPORTS", "sortOrder", 5), 200).body();
        assertThat(patched.get("label").asText()).isEqualTo("Skolan 2");
        assertThat(patched.get("kind").asText()).isEqualTo("SPORTS");
        assertThat(patched.get("address").asText()).isEqualTo("Skolanvägen 1, Stad");
        call("PATCH", "/api/saved-places/" + aId, u, Map.of("lat", 59.5, "lon", 18.5, "address", "Ny 1"), 200);

        call("PUT", "/api/saved-places/order", u, Map.of("ids", List.of(aId, bId)), 200);
        assertThat(labels(u, "")).containsExactly("Skolan 2", "Mormor");
        call("PUT", "/api/saved-places/order", u, Map.of("ids", List.of(bId, aId)), 200);
        assertThat(labels(u, "")).containsExactly("Mormor", "Skolan 2");
        call("PUT", "/api/saved-places/order", u, Map.of("ids", List.of(bId)), 400);
        call("PUT", "/api/saved-places/order", u, Map.of("ids", List.of(bId, aId, 999999L)), 400);

        call("DELETE", "/api/saved-places/" + aId, u, null, 200);
        assertThat(labels(u, "")).containsExactly("Mormor");
        call("DELETE", "/api/saved-places/" + aId, u, null, 404);
    }

    @Test
    void validatesLabelAndAddress() throws Exception {
        call("POST", "/api/saved-places", u, place("x".repeat(61), null, 59.1), 400);
        Map<String, Object> blank = place("ok", null, 59.1);
        blank.put("address", "y".repeat(513));
        call("POST", "/api/saved-places", u, blank, 400);
        call("POST", "/api/saved-places", u, place("x".repeat(60), null, 59.1), 200);
        long id = add(u, "", place("p", null, 59.3)).get("id").asLong();
        call("PATCH", "/api/saved-places/" + id, u, Map.of("label", ""), 400);
        call("PATCH", "/api/saved-places/" + id, u, Map.of("label", "z".repeat(61)), 400);
        call("PATCH", "/api/saved-places/" + id, u, Map.of("label", "z".repeat(60)), 200);
    }

    @Test
    void onlyOneHomePerUser() throws Exception {
        long h1 = add(u, "", place("Hemma", "HOME", 59.1)).get("id").asLong();
        long h2 = add(u, "", place("Nya hemmet", "HOME", 59.2)).get("id").asLong();
        assertThat(kindOf(u, h1)).isEqualTo("OTHER");
        assertThat(kindOf(u, h2)).isEqualTo("HOME");
        call("PATCH", "/api/saved-places/" + h1, u, Map.of("kind", "HOME"), 200);
        assertThat(kindOf(u, h1)).isEqualTo("HOME");
        assertThat(kindOf(u, h2)).isEqualTo("OTHER");
        // another user's HOME is unaffected
        long o = add(other, "", place("Hem", "HOME", 59.3)).get("id").asLong();
        assertThat(kindOf(other, o)).isEqualTo("HOME");
        assertThat(kindOf(u, h1)).isEqualTo("HOME");
    }

    private String kindOf(String token, long id) throws Exception {
        for (JsonNode n : call("GET", "/api/saved-places", token, null, 200).body()) {
            if (n.get("id").asLong() == id) {
                return n.get("kind").asText();
            }
        }
        throw new IllegalStateException();
    }

    @Test
    void driverManagesPassengerPlacesButPlainUserCannot() throws Exception {
        JsonNode created = add(drv, "?userId=" + uId, place("Skolan", "SCHOOL", 59.1));
        assertThat(labels(u, "")).containsExactly("Skolan");
        assertThat(labels(drv, "")).isEmpty();
        assertThat(labels(drv, "?userId=" + uId)).containsExactly("Skolan");
        long id = created.get("id").asLong();
        call("PATCH", "/api/saved-places/" + id, drv, Map.of("label", "Skolan nya"), 200);
        call("PUT", "/api/saved-places/order?userId=" + uId, drv, Map.of("ids", List.of(id)), 200);
        // admin too
        call("GET", "/api/saved-places?userId=" + uId, adminToken, null, 200);

        // plain users: 403 with userId, 404 by id (no existence leak)
        call("GET", "/api/saved-places?userId=" + uId, other, null, 403);
        call("POST", "/api/saved-places?userId=" + uId, other, place("X", null, 59.1), 403);
        call("PUT", "/api/saved-places/order?userId=" + uId, other, Map.of("ids", List.of(id)), 403);
        call("PATCH", "/api/saved-places/" + id, other, Map.of("label", "hack"), 404);
        call("DELETE", "/api/saved-places/" + id, other, null, 404);
        // own id as userId is fine
        call("GET", "/api/saved-places?userId=" + otherId, other, null, 200);

        call("DELETE", "/api/saved-places/" + id, drv, null, 200);
        assertThat(labels(u, "")).isEmpty();
    }

    private void setFlags(boolean approved, boolean enabled) {
        var user = users.findById(uId).orElseThrow();
        user.setApproved(approved);
        user.setEnabled(enabled);
        users.save(user);
    }

    @Test
    void onBehalfPatchDeleteRequireEnabledApprovedTarget() throws Exception {
        long id = add(u, "", place("Skolan", null, 59.1)).get("id").asLong();
        setFlags(false, true);
        call("PATCH", "/api/saved-places/" + id, drv, Map.of("label", "x"), 404);
        call("DELETE", "/api/saved-places/" + id, drv, null, 404);
        setFlags(true, false);
        call("PATCH", "/api/saved-places/" + id, drv, Map.of("label", "x"), 404);
        call("DELETE", "/api/saved-places/" + id, drv, null, 404);
        setFlags(true, true);
        call("PATCH", "/api/saved-places/" + id, drv, Map.of("label", "x"), 200);
    }

    @Test
    void crossWorldIsRejected() throws Exception {
        long id = add(u, "", place("Skolan", null, 59.1)).get("id").asLong();
        long tpPlace = add(tp, "", place("Testplats", null, 59.1)).get("id").asLong();
        // test driver vs real passenger
        call("GET", "/api/saved-places?userId=" + uId, td, null, 404);
        call("POST", "/api/saved-places?userId=" + uId, td, place("X", null, 59.1), 404);
        call("PATCH", "/api/saved-places/" + id, td, Map.of("label", "x"), 404);
        call("DELETE", "/api/saved-places/" + id, td, null, 404);
        // real driver vs test passenger
        call("GET", "/api/saved-places?userId=" + tpId, drv, null, 404);
        call("DELETE", "/api/saved-places/" + tpPlace, drv, null, 404);
        // same world still works for test world
        call("GET", "/api/saved-places?userId=" + tpId, td, null, 200);
        call("DELETE", "/api/saved-places/" + tpPlace, td, null, 200);
    }

    @Test
    void recentsOnBehalfAuthorization() throws Exception {
        clock.set(java.time.Instant.now());
        ride(u, "A-gatan 1, Stad", 59.1000, 18.0, "B-gatan 2, Stad", 59.2000, 18.0, Duration.ZERO);
        assertThat(call("GET", "/api/places/recent?userId=" + uId, drv, null, 200).body()).isNotEmpty();
        assertThat(call("GET", "/api/places/recent?userId=" + uId, adminToken, null, 200).body()).isNotEmpty();
        assertThat(call("GET", "/api/places/recent?userId=" + uId, u, null, 200).body()).isNotEmpty();
        assertThat(call("GET", "/api/places/recent", drv, null, 200).body()).isEmpty();
        call("GET", "/api/places/recent?userId=" + uId, other, null, 404);
        call("GET", "/api/places/recent?userId=" + uId, td, null, 404);
        call("GET", "/api/places/recent?userId=" + tpId, drv, null, 404);
        call("GET", "/api/places/recent?userId=99999999", drv, null, 404);
        setFlags(false, true);
        call("GET", "/api/places/recent?userId=" + uId, drv, null, 404);
        setFlags(true, false);
        call("GET", "/api/places/recent?userId=" + uId, drv, null, 404);
        setFlags(true, true);
    }

    @Test
    void recentsContentOrderDedupeAndExclusion() throws Exception {
        clock.set(java.time.Instant.now()); // rides.createdAt is stamped by the real clock
        assertThat(call("GET", "/api/places/recent", u, null, 200).body()).isEmpty();
        ride(u, "A-gatan 1, Stad", 59.1000, 18.0, "B-gatan 2, Stad", 59.2000, 18.0, Duration.ZERO);
        Thread.sleep(20);
        // same destination as before (within 50 m, different text) + new pickup
        ride(u, "C-gatan 3, Stad", 59.3000, 18.0, "B-gatan 2B, Stad", 59.20010, 18.0, Duration.ZERO);
        Thread.sleep(20);
        ride(u, "Hem 1, Stad", 59.4000, 18.0, "D-gatan 4, Stad", 59.5000, 18.0, Duration.ZERO);
        // other user's ride must not leak
        ride(other, "Annans 1", 58.0, 18.0, "Annans 2", 58.1, 18.0, Duration.ZERO);
        // saved place equal to the home pickup excludes it
        add(u, "", place("Hem", "HOME", 59.4000));

        List<String> addrs = recent(u, "");
        assertThat(addrs).containsExactly("D-gatan 4, Stad", "B-gatan 2B, Stad", "C-gatan 3, Stad", "A-gatan 1, Stad");
        JsonNode first = call("GET", "/api/places/recent", u, null, 200).body().get(0);
        assertThat(first.get("kind").asText()).isEqualTo("RECENT");
        assertThat(first.get("provider").asText()).isEqualTo("RECENT");
        assertThat(first.get("name").asText()).isEqualTo("D-gatan 4");
        assertThat(first.get("area").asText()).isEqualTo("Stad");
        assertThat(recent(u, "?limit=2")).hasSize(2);
        assertThat(recent(u, "?limit=999")).hasSize(4);
        assertThat(recent(other, "")).containsExactly("Annans 2", "Annans 1");

        // older than 90 days drops out
        clock.advance(Duration.ofDays(91));
        assertThat(recent(u, "")).isEmpty();
    }

    @Test
    void favoritesStillLeadSearchWithNewFields() throws Exception {
        Map<String, Object> p = place("Zzfavoritplats", "FAMILY", 59.1);
        p.put("icon", "heart");
        p.put("provider", "SL");
        p.put("providerPlaceId", "999");
        add(u, "", p);
        JsonNode res = call("GET", "/api/places/search?q=zzfavorit", u, null, 200).body().get("results");
        assertThat(res.size()).isGreaterThanOrEqualTo(1);
        assertThat(res.get(0).get("kind").asText()).isEqualTo("FAVORITE");
        assertThat(res.get(0).get("name").asText()).isEqualTo("Zzfavoritplats");
    }

    private List<String> recent(String token, String q) throws Exception {
        List<String> out = new ArrayList<>();
        call("GET", "/api/places/recent" + q, token, null, 200).body().forEach(n -> out.add(n.get("formattedAddress").asText()));
        return out;
    }

    private void ride(String token, String fa, double flat, double flon, String ta, double tlat, double tlon, Duration d) throws Exception {
        Map<String, Object> m = rideBody(BASE.plus(Duration.ofDays(30)));
        m.put("fromAddress", fa);
        m.put("fromLat", flat);
        m.put("fromLon", flon);
        m.put("toAddress", ta);
        m.put("toLat", tlat);
        m.put("toLon", tlon);
        call("POST", "/api/rides", token, m, 200);
    }
}
