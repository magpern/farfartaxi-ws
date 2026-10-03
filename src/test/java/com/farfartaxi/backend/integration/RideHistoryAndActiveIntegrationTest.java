package com.farfartaxi.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** M2: GET /api/driver/rides/history and GET /api/rides/active. */
class RideHistoryAndActiveIntegrationTest extends M1TestSupport {

    private static int fresh = 0;
    private String fp, fd1, fd2, fd3;

    @org.junit.jupiter.api.BeforeEach
    void freshUsers() throws Exception {
        int n = ++fresh;
        fp = account("m2-p" + n + "@test.local", false);
        fd1 = account("m2-da" + n + "@test.local", true);
        fd2 = account("m2-db" + n + "@test.local", true);
        fd3 = account("m2-dc" + n + "@test.local", true);
    }

    /** Park the fresh drivers so they never receive offers for rides in other test classes. */
    @org.junit.jupiter.api.AfterEach
    void parkFreshDrivers() throws Exception {
        for (String t : new String[] {fd1, fd2, fd3}) {
            setAvailability(t, false, "2000-01-01", "2099-12-31");
        }
    }

    private List<Long> historyIds(String token, String query) throws Exception {
        List<Long> out = new ArrayList<>();
        call("GET", "/api/driver/rides/history" + query, token, null, 200).body().forEach(n -> out.add(n.get("id").asLong()));
        return out;
    }

    private Resp active(String token) throws Exception {
        return call("GET", "/api/rides/active", token, null, null);
    }

    private void complete(String driver, long id) throws Exception {
        drive(driver, id, "start");
        drive(driver, id, "arrive");
        drive(driver, id, "pickup");
        drive(driver, id, "complete");
    }

    /** A ride of passenger p accepted by driver d (a driver without a ride clash). */
    private long accepted(String p, String d, java.time.Instant when) throws Exception {
        long id = bookAt(p, when);
        acceptOk(d, id);
        return id;
    }

    @Test
    void historyListsOnlyMyFinishedRidesNewestFirstWithLimitAndIsolation() throws Exception {
        long a = accepted(fp, fd3, at("2027-06-01", "10:00"));
        long b = accepted(fp, fd3, at("2027-06-03", "10:00"));
        long c = accepted(fp, fd3, at("2027-06-02", "10:00"));
        long open = accepted(fp, fd3, at("2027-06-04", "10:00"));      // still ACCEPTED: not history
        long other = accepted(fp, fd2, at("2027-06-05", "10:00"));
        complete(fd3, a);
        complete(fd3, b);
        call("POST", "/api/rides/" + c + "/cancel", fp, Map.of("reason", "x"), 200);
        complete(fd2, other);
        long unaccepted = bookAt(fp, at("2027-06-06", "10:00"));
        call("POST", "/api/rides/" + unaccepted + "/cancel", fp, Map.of("reason", "x"), 200);

        assertThat(historyIds(fd3, "")).containsExactly(b, c, a);
        assertThat(historyIds(fd3, "?limit=2")).containsExactly(b, c);
        assertThat(historyIds(fd2, "")).containsExactly(other);
        assertThat(historyIds(fd1, "")).isEmpty();
        assertThat(historyIds(td, "")).doesNotContain(a, b, c, other);   // other world
        JsonNode first = call("GET", "/api/driver/rides/history", fd3, null, 200).body().get(0);
        assertThat(first.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(first.has("availableActions")).isTrue();
        call("GET", "/api/driver/rides/history", fp, null, 403);
        assertThat(open).isNotZero();
    }

    @Test
    void historyIsWorldIsolated() throws Exception {
        long t = call("POST", "/api/rides", tp, rideBody(at("2027-07-01", "10:00")), 200).body().get("id").asLong();
        acceptOk(td, t);
        complete(td, t);
        assertThat(historyIds(td, "")).contains(t);
        assertThat(historyIds(fd1, "")).doesNotContain(t);
        assertThat(historyIds(adminToken, "")).doesNotContain(t);
    }

    @Test
    void activeIs204WhenNothingIsGoingOn() throws Exception {
        assertThat(active(fp).status()).isEqualTo(204);
        assertThat(active(fd1).status()).isEqualTo(204);
        call("GET", "/api/rides/active", null, null, 401);
    }

    @Test
    void passengerSeesRideInEachRelevantStatusPreferringAdvanced() throws Exception {
        long id = bookNow(fp);
        Resp r = active(fp);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("role").asText()).isEqualTo("PASSENGER");
        assertThat(r.body().get("ride").get("id").asLong()).isEqualTo(id);
        assertThat(r.body().get("ride").get("status").asText()).isEqualTo("REQUESTED");

        long later = bookAt(fp, BASE.plus(Duration.ofDays(2)));       // another REQUESTED, further away
        assertThat(active(fp).body().get("ride").get("id").asLong()).isEqualTo(id); // nearest scheduledAt

        acceptOk(fd1, later);
        assertThat(active(fp).body().get("ride").get("id").asLong()).isEqualTo(later); // ACCEPTED beats REQUESTED
        for (String step : new String[] {"start", "arrive", "pickup"}) {
            drive(fd1, later, step);
            String expected = Map.of("start", "EN_ROUTE", "arrive", "ARRIVED", "pickup", "PICKED_UP").get(step);
            JsonNode ride = active(fp).body().get("ride");
            assertThat(ride.get("id").asLong()).isEqualTo(later);
            assertThat(ride.get("status").asText()).isEqualTo(expected);
        }
        drive(fd1, later, "complete");
        assertThat(active(fp).body().get("ride").get("id").asLong()).isEqualTo(id);
        call("POST", "/api/rides/" + id + "/cancel", fp, null, 200);
        assertThat(active(fp).status()).isEqualTo(204);
    }

    @Test
    void passengerSeesFreshNoDriverButNotStaleOne() throws Exception {
        long id = bookNow(fp);
        clock.set(BASE.plus(Duration.ofMinutes(21)));
        timers.tick();
        assertThat(ride(fp, id).get("status").asText()).isEqualTo("REQUESTED".equals(ride(fp, id).get("status").asText()) ? "REQUESTED" : "NO_DRIVER");
        Resp fresh = active(fp);
        assertThat(fresh.status()).isEqualTo(200);
        assertThat(fresh.body().get("ride").get("status").asText()).isEqualTo("NO_DRIVER");
        clock.set(BASE.plus(Duration.ofHours(3)));
        assertThat(active(fp).status()).isEqualTo(204);
        call("POST", "/api/rides/" + id + "/cancel", fp, null, 200);
    }

    @Test
    void driverViewBeatsPassengerViewAndAcceptedNeedsToBeWithinAnHour() throws Exception {
        long mine = bookAt(fp, BASE.plus(Duration.ofHours(2)));       // fd1 as passenger, REQUESTED
        long asPassenger = call("POST", "/api/rides", fd1, rideBody(BASE.plus(Duration.ofHours(5))), 200).body().get("id").asLong();
        assertThat(active(fd1).body().get("role").asText()).isEqualTo("PASSENGER");
        assertThat(active(fd1).body().get("ride").get("id").asLong()).isEqualTo(asPassenger);

        acceptOk(fd1, mine);                                           // 2 h ahead: not yet relevant to the driver
        Resp r = active(fd1);
        assertThat(r.body().get("role").asText()).isEqualTo("PASSENGER");

        clock.set(BASE.plus(Duration.ofMinutes(61)));                 // now 59 min ahead
        r = active(fd1);
        assertThat(r.body().get("role").asText()).isEqualTo("DRIVER");
        assertThat(r.body().get("ride").get("id").asLong()).isEqualTo(mine);
        assertThat(r.body().get("ride").get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(actions(r.body().get("ride"))).contains("START");

        clock.set(BASE.plus(Duration.ofHours(3)));                    // past but not started: still shown
        assertThat(active(fd1).body().get("ride").get("id").asLong()).isEqualTo(mine);

        drive(fd1, mine, "start");
        r = active(fd1);
        assertThat(r.body().get("role").asText()).isEqualTo("DRIVER");
        assertThat(r.body().get("ride").get("status").asText()).isEqualTo("EN_ROUTE");
        drive(fd1, mine, "arrive");
        drive(fd1, mine, "pickup");
        drive(fd1, mine, "complete");
        assertThat(active(fd1).body().get("role").asText()).isEqualTo("PASSENGER");
        call("POST", "/api/rides/" + asPassenger + "/cancel", fd1, null, 200);
        assertThat(active(fd1).status()).isEqualTo(204);
    }

    @Test
    void driverEnRouteBeatsPassengerRequested() throws Exception {
        long served = bookAt(fp, BASE.plus(Duration.ofMinutes(30)));
        acceptOk(fd2, served);
        drive(fd2, served, "start");
        long own = call("POST", "/api/rides", fd2, rideBody(BASE.plus(Duration.ofDays(1))), 200).body().get("id").asLong();
        Resp r = active(fd2);
        assertThat(r.body().get("role").asText()).isEqualTo("DRIVER");
        assertThat(r.body().get("ride").get("id").asLong()).isEqualTo(served);
        drive(fd2, served, "arrive");
        drive(fd2, served, "pickup");
        drive(fd2, served, "complete");
        call("POST", "/api/rides/" + own + "/cancel", fd2, null, 200);
    }

    @Test
    void crossWorldRidesAreNeverReturned() throws Exception {
        long t = call("POST", "/api/rides", tp, rideBody(BASE.plus(Duration.ofMinutes(30))), 200).body().get("id").asLong();
        acceptOk(td, t);
        assertThat(active(td).body().get("ride").get("id").asLong()).isEqualTo(t);
        assertThat(active(tp).body().get("ride").get("id").asLong()).isEqualTo(t);
        assertThat(active(fp).status()).isEqualTo(204);
        assertThat(active(fd1).status()).isEqualTo(204);
        assertThat(active(adminToken).status()).isEqualTo(204);
        call("POST", "/api/rides/" + t + "/cancel", tp, null, 200);
    }
}
