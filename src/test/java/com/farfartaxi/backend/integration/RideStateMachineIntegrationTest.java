package com.farfartaxi.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** M1 gate: every transition, role guards, concurrency, stale codes, cancel rules, proximity, availableActions. */
class RideStateMachineIntegrationTest extends M1TestSupport {
    private static final Instant LATER = at("2027-04-12", "10:00");

    @Test
    void happyPathWalksEveryForwardTransitionAndComputesActions() throws Exception {
        long id = bookAt(LATER);
        JsonNode booked = ride(p1, id);
        assertThat(booked.get("status").asText()).isEqualTo("REQUESTED");
        assertThat(booked.get("kind").asText()).isEqualTo("SCHEDULED");
        assertThat(actions(booked)).containsExactlyInAnyOrder("CANCEL", "EDIT");
        // an offered driver sees ACCEPT/DECLINE and the offer is marked VIEWED by opening it
        assertThat(actions(ride(d1, id))).containsExactlyInAnyOrder("ACCEPT", "DECLINE");
        assertThat(ride(d1, id).get("myOfferStatus").asText()).isEqualTo("VIEWED");

        JsonNode accepted = acceptOk(d1, id);
        assertThat(accepted.get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(actions(accepted)).containsExactlyInAnyOrder("START", "RETURN", "MESSAGE");
        assertThat(actions(ride(p1, id))).containsExactlyInAnyOrder("CANCEL", "EDIT", "MESSAGE");
        assertConflict(call("GET", "/api/rides/" + id, d2, null, null), "RIDE_TAKEN"); // d2's offer was withdrawn

        JsonNode enRoute = drive(d1, id, "start");
        assertThat(enRoute.get("status").asText()).isEqualTo("EN_ROUTE");
        assertThat(actions(enRoute)).containsExactlyInAnyOrder("ARRIVE", "RETURN", "MESSAGE");
        assertThat(actions(ride(p1, id))).containsExactlyInAnyOrder("CANCEL_CONFIRM", "MESSAGE");

        JsonNode arrived = drive(d1, id, "arrive");
        assertThat(arrived.get("status").asText()).isEqualTo("ARRIVED");
        assertThat(arrived.get("arrivedAt").isNull()).isFalse();
        assertThat(actions(arrived)).containsExactlyInAnyOrder("PICKUP", "MESSAGE");
        assertThat(actions(ride(p1, id))).containsExactlyInAnyOrder("CANCEL_CONFIRM", "MESSAGE");

        JsonNode picked = drive(d1, id, "pickup");
        assertThat(picked.get("status").asText()).isEqualTo("PICKED_UP");
        assertThat(picked.get("pickedUpAt").isNull()).isFalse();
        assertThat(actions(picked)).containsExactlyInAnyOrder("COMPLETE", "MESSAGE");
        assertThat(actions(ride(p1, id))).containsExactlyInAnyOrder("MESSAGE");

        JsonNode done = drive(d1, id, "complete");
        assertThat(done.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(actions(done)).isEmpty();
        assertThat(actions(ride(p1, id))).isEmpty();

        assertThat(call("GET", "/api/driver/stats", d1, null, 200).body().get("completedRides").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(timeline(id)).containsExactly("BOOKED", "ACCEPTED", "STARTED", "ARRIVED", "PICKED_UP", "COMPLETED");
    }

    @Test
    void availableActionsForRequestedAndNoDriverAndNonOfferedDriver() throws Exception {
        long id = bookAt(LATER.plusSeconds(86400));
        assertThat(actions(ride(p1, id))).containsExactlyInAnyOrder("CANCEL", "EDIT");
        String late = newDriver(); // no offer for this ride
        assertThat(actions(call("GET", "/api/rides/" + id, adminToken, null, 200).body())).isEmpty(); // admin: away, no offer
        assertThat(call("GET", "/api/rides/" + id, late, null, null).status()).isEqualTo(403);

        call("POST", "/api/driver/rides/" + id + "/decline", d1, Map.of("comment", "x"), 200);
        assertConflict(call("GET", "/api/rides/" + id, d1, null, null), "OFFER_CLOSED");
        declineAllReal(id);
        JsonNode noDriver = ride(p1, id);
        assertThat(noDriver.get("status").asText()).isEqualTo("NO_DRIVER");
        // every offered driver declined, but the late driver was never offered: keep-waiting would offer it
        assertThat(actions(noDriver)).containsExactlyInAnyOrder("CANCEL", "EDIT", "KEEP_WAITING");
    }

    @Test
    void returnFromAcceptedAndEnRouteReoffersOthersAndKeepsDecliners() throws Exception {
        long id = bookAt(LATER.plusSeconds(2 * 86400));
        call("POST", "/api/driver/rides/" + id + "/decline", d3, Map.of("comment", "busy"), 200);
        acceptOk(d1, id);
        // return from ACCEPTED: no reason needed
        JsonNode back = call("POST", "/api/driver/rides/" + id + "/return", d1, null, 200).body();
        assertThat(back.get("status").asText()).isEqualTo("REQUESTED");
        assertThat(back.get("myOfferStatus").asText()).isEqualTo("WITHDRAWN");
        assertThat(ride(p1, id).get("acceptedByDriverId").isNull()).isTrue();
        assertThat(openIds(d2)).contains(id);       // others re-offered
        assertThat(openIds(d1)).doesNotContain(id); // returner stays withdrawn
        assertThat(openIds(d3)).doesNotContain(id); // decliner stays declined
        assertConflict(accept(d1, id), "OFFER_CLOSED");

        acceptOk(d2, id);
        drive(d2, id, "start");
        // return from EN_ROUTE requires a reason
        assertThat(call("POST", "/api/driver/rides/" + id + "/return", d2, Map.of("reason", " "), null).status()).isEqualTo(400);
        assertThat(call("POST", "/api/driver/rides/" + id + "/unaccept", d2, Map.of("reason", "punktering"), 200)
            .body().get("status").asText()).isEqualTo("REQUESTED"); // alias works
        // d2's offer is withdrawn; d1 (who returned earlier) is one of "the others" and is offered again, d3 stays declined
        assertThat(status(id)).isEqualTo("REQUESTED");
        assertThat(openIds(d1)).contains(id);
        assertThat(openIds(d2)).doesNotContain(id);
        assertThat(openIds(d3)).doesNotContain(id);
        // last open offer declined -> NO_DRIVER (drivers created by other tests hold offers too)
        declineAllReal(id);
        assertThat(status(id)).isEqualTo("NO_DRIVER");
        assertThat(timeline(id)).startsWith("BOOKED", "DECLINED", "ACCEPTED", "RETURNED", "ACCEPTED", "STARTED", "RETURNED", "DECLINED").endsWith("NO_DRIVER");
    }

    @Test
    void disallowedTransitionsAreInvalidTransition() throws Exception {
        long id = bookAt(LATER.plusSeconds(3 * 86400));
        // REQUESTED: the passenger cannot keep waiting; nobody is assigned yet
        assertConflict(call("POST", "/api/rides/" + id + "/keep-waiting", p1, null, null), "INVALID_TRANSITION");
        acceptOk(d1, id);
        assertConflict(call("POST", "/api/driver/rides/" + id + "/accept", d1, null, null), "INVALID_TRANSITION"); // twice
        // ACCEPTED: arrive/pickup/complete are illegal
        for (String step : List.of("arrive", "pickup", "complete")) {
            assertConflict(call("POST", "/api/driver/rides/" + id + "/" + step, d1, null, null), "INVALID_TRANSITION");
        }
        assertConflict(call("POST", "/api/rides/" + id + "/keep-waiting", p1, null, null), "INVALID_TRANSITION");
        drive(d1, id, "start");
        for (String step : List.of("start", "pickup", "complete")) {
            assertConflict(call("POST", "/api/driver/rides/" + id + "/" + step, d1, null, null), "INVALID_TRANSITION");
        }
        drive(d1, id, "arrive");
        for (String step : List.of("start", "arrive", "complete")) {
            assertConflict(call("POST", "/api/driver/rides/" + id + "/" + step, d1, null, null), "INVALID_TRANSITION");
        }
        assertConflict(call("POST", "/api/driver/rides/" + id + "/return", d1, Map.of("reason", "x"), null), "INVALID_TRANSITION"); // not from ARRIVED
        drive(d1, id, "pickup");
        for (String step : List.of("start", "arrive", "pickup")) {
            assertConflict(call("POST", "/api/driver/rides/" + id + "/" + step, d1, null, null), "INVALID_TRANSITION");
        }
        assertConflict(call("POST", "/api/driver/rides/" + id + "/return", d1, Map.of("reason", "x"), null), "INVALID_TRANSITION");
        assertConflict(call("POST", "/api/rides/" + id + "/cancel", p1, Map.of("confirm", true), null), "INVALID_TRANSITION"); // no cancel from PICKED_UP
        drive(d1, id, "complete");
        for (String step : List.of("start", "arrive", "pickup", "complete")) {
            assertConflict(call("POST", "/api/driver/rides/" + id + "/" + step, d1, null, null), "INVALID_TRANSITION");
        }
        assertConflict(call("POST", "/api/driver/rides/" + id + "/return", d1, Map.of("reason", "x"), null), "INVALID_TRANSITION");
        assertConflict(call("POST", "/api/driver/rides/" + id + "/location", d1, Map.of("lat", 59.33, "lon", 18.07), null), "INVALID_TRANSITION");
        assertConflict(call("POST", "/api/rides/" + id + "/cancel", p1, Map.of("confirm", true), null), "INVALID_TRANSITION");
        assertConflict(call("POST", "/api/rides/" + id + "/keep-waiting", p1, null, null), "INVALID_TRANSITION");
        assertThat(status(id)).isEqualTo("COMPLETED");
    }

    @Test
    void roleGuardsPassengerDriverOtherDriverAndAdmin() throws Exception {
        long id = bookAt(LATER.plusSeconds(4 * 86400));
        String late = newDriver(); // joined after booking: holds no offer
        // a passenger (role USER) cannot use driver endpoints
        for (String path : List.of("accept", "decline", "return", "start", "arrive", "pickup", "complete")) {
            assertThat(call("POST", "/api/driver/rides/" + id + "/" + path, p1, null, null).status()).as(path).isEqualTo(403);
        }
        assertThat(call("GET", "/api/driver/rides/open", p1, null, null).status()).isEqualTo(403);
        assertThat(call("GET", "/api/driver/availability", p1, null, null).status()).isEqualTo(403);
        // drivers cannot act as the passenger of somebody else's ride
        assertThat(call("POST", "/api/rides/" + id + "/cancel", d1, null, null).status()).isEqualTo(403);
        assertThat(call("PATCH", "/api/rides/" + id, d1, Map.of("pickupNote", "x"), null).status()).isEqualTo(403);
        assertThat(call("POST", "/api/rides/" + id + "/keep-waiting", d1, null, null).status()).isEqualTo(403);
        // another passenger cannot touch it either
        assertThat(call("POST", "/api/rides/" + id + "/cancel", p2, null, null).status()).isEqualTo(403);
        assertThat(call("PATCH", "/api/rides/" + id, p2, Map.of("pickupNote", "x"), null).status()).isEqualTo(403);
        assertThat(call("POST", "/api/rides/" + id + "/keep-waiting", p2, null, null).status()).isEqualTo(403);
        assertThat(call("GET", "/api/rides/" + id, p2, null, null).status()).isEqualTo(403);

        acceptOk(d1, id);
        // other offered driver: stale 409; driver without an offer: 403; admin (no offer, not assigned): 403
        assertConflict(call("POST", "/api/driver/rides/" + id + "/start", d2, null, null), "RIDE_TAKEN");
        assertThat(call("POST", "/api/driver/rides/" + id + "/start", late, null, null).status()).isEqualTo(403);
        assertThat(call("POST", "/api/driver/rides/" + id + "/start", adminToken, null, null).status()).isEqualTo(403);
        assertThat(call("POST", "/api/driver/rides/" + id + "/return", late, null, null).status()).isEqualTo(403);
        assertThat(call("POST", "/api/rides/" + id + "/cancel", adminToken, null, null).status()).isEqualTo(403);
        // the assigned driver is not the passenger either
        assertThat(call("POST", "/api/rides/" + id + "/cancel", d1, null, null).status()).isEqualTo(403);
        // admin has read access for oversight
        assertThat(call("GET", "/api/rides/" + id, adminToken, null, 200).body().get("id").asLong()).isEqualTo(id);
        // the passenger cannot start their own ride, the real driver can
        assertThat(drive(d1, id, "start").get("status").asText()).isEqualTo("EN_ROUTE");
    }

    @Test
    void twoConcurrentAcceptsExactlyOneWins() throws Exception {
        for (int round = 0; round < 6; round++) {
            long id = bookAt(LATER.plusSeconds((10 + round) * 86400L));
            List<String> tokens = List.of(d1, d2, d3);
            CyclicBarrier barrier = new CyclicBarrier(tokens.size());
            ExecutorService pool = Executors.newFixedThreadPool(tokens.size());
            List<Future<Resp>> futures = new ArrayList<>();
            for (String t : tokens) {
                Callable<Resp> task = () -> {
                    barrier.await();
                    return call("POST", "/api/driver/rides/" + id + "/accept", t, Map.of("confirmProximity", true), null);
                };
                futures.add(pool.submit(task));
            }
            List<Resp> results = new ArrayList<>();
            for (Future<Resp> f : futures) {
                results.add(f.get());
            }
            pool.shutdown();
            long ok = results.stream().filter(r -> r.status() == 200).count();
            assertThat(ok).as("round " + round + ": " + results).isEqualTo(1);
            for (Resp r : results) {
                if (r.status() != 200) {
                    // losers see RIDE_TAKEN, or OFFER_CLOSED when the winner's commit already closed their offer
                    assertThat(r.status()).as(r.body().toString()).isEqualTo(409);
                    assertThat(r.code()).as(r.body().toString()).isIn("RIDE_TAKEN", "OFFER_CLOSED");
                }
            }
            JsonNode r = ride(p1, id);
            assertThat(r.get("status").asText()).isEqualTo("ACCEPTED");
            assertThat(timeline(id).stream().filter("ACCEPTED"::equals).count()).isEqualTo(1);
        }
    }

    @Test
    void staleCodes() throws Exception {
        long id = bookAt(LATER.plusSeconds(20 * 86400L));
        call("POST", "/api/driver/rides/" + id + "/decline", d3, null, 200);
        assertConflict(accept(d3, id), "OFFER_CLOSED");
        assertConflict(call("POST", "/api/driver/rides/" + id + "/decline", d3, null, null), "OFFER_CLOSED");
        acceptOk(d1, id);
        assertConflict(accept(d2, id), "RIDE_TAKEN");
        assertConflict(call("POST", "/api/driver/rides/" + id + "/decline", d2, null, null), "RIDE_TAKEN");
        call("POST", "/api/rides/" + id + "/cancel", p1, null, 200);
        assertConflict(accept(d2, id), "RIDE_CANCELLED");
        assertConflict(call("POST", "/api/driver/rides/" + id + "/start", d1, null, null), "RIDE_CANCELLED");
        assertConflict(call("GET", "/api/rides/" + id, d2, null, null), "RIDE_CANCELLED");

        // ride changed under the driver: material edit hands the ride back to REQUESTED
        long id2 = bookAt(LATER.plusSeconds(21 * 86400L));
        acceptOk(d1, id2);
        call("PATCH", "/api/rides/" + id2, p1, Map.of("scheduledAt", LATER.plusSeconds(21 * 86400L + 7200).toString()), 200);
        assertConflict(call("POST", "/api/driver/rides/" + id2 + "/start", d1, null, null), "RIDE_CHANGED");
        // an unknown ride id and the 409 body shape
        Resp r = call("POST", "/api/driver/rides/" + id + "/accept", d2, null, null);
        assertThat(r.body().get("error").asText()).isNotBlank();
        assertThat(call("POST", "/api/driver/rides/999999/accept", d1, null, null).status()).isEqualTo(404);
    }

    @Test
    void cancelRulesIncludingConfirmRequired() throws Exception {
        // from REQUESTED
        long a = bookAt(LATER.plusSeconds(30 * 86400L));
        assertThat(call("POST", "/api/rides/" + a + "/cancel", p1, Map.of("reason", "plans"), 200).body().get("status").asText()).isEqualTo("CANCELLED");
        assertThat(openIds(d1)).doesNotContain(a); // offers withdrawn
        assertThat(call("POST", "/api/rides/" + a + "/cancel", p1, null, 200).body().get("status").asText()).isEqualTo("CANCELLED"); // idempotent
        // from NO_DRIVER
        long b = bookAt(LATER.plusSeconds(31 * 86400L));
        declineAllReal(b);
        assertThat(status(b)).isEqualTo("NO_DRIVER");
        assertThat(call("POST", "/api/rides/" + b + "/cancel", p1, null, 200).body().get("status").asText()).isEqualTo("CANCELLED");
        // from ACCEPTED: no confirm needed
        long c = bookAt(LATER.plusSeconds(32 * 86400L));
        acceptOk(d1, c);
        assertThat(call("POST", "/api/rides/" + c + "/cancel", p1, null, 200).body().get("status").asText()).isEqualTo("CANCELLED");
        // from EN_ROUTE: confirm required
        long d = bookAt(LATER.plusSeconds(33 * 86400L));
        acceptOk(d1, d);
        drive(d1, d, "start");
        assertConflict(call("POST", "/api/rides/" + d + "/cancel", p1, null, null), "CONFIRM_REQUIRED");
        assertConflict(call("POST", "/api/rides/" + d + "/cancel", p1, Map.of("confirm", false), null), "CONFIRM_REQUIRED");
        assertThat(status(d)).isEqualTo("EN_ROUTE");
        assertThat(call("POST", "/api/rides/" + d + "/cancel", p1, Map.of("confirm", true, "reason", "klarar mig"), 200).body().get("status").asText()).isEqualTo("CANCELLED");
        // from ARRIVED: confirm required
        long e = bookAt(LATER.plusSeconds(34 * 86400L));
        acceptOk(d1, e);
        drive(d1, e, "start");
        drive(d1, e, "arrive");
        assertConflict(call("POST", "/api/rides/" + e + "/cancel", p1, null, null), "CONFIRM_REQUIRED");
        call("POST", "/api/rides/" + e + "/cancel", p1, Map.of("confirm", true), 200);
        // PICKED_UP and COMPLETED: never
        long f = bookAt(LATER.plusSeconds(35 * 86400L));
        acceptOk(d1, f);
        drive(d1, f, "start");
        drive(d1, f, "arrive");
        drive(d1, f, "pickup");
        assertConflict(call("POST", "/api/rides/" + f + "/cancel", p1, Map.of("confirm", true), null), "INVALID_TRANSITION");
        assertThat(status(f)).isEqualTo("PICKED_UP");
        assertThat(timeline(d)).contains("CANCELLED");
    }

    @Test
    void proximityWarningAndConfirmPath() throws Exception {
        String day = "2027-09-14";
        long a = bookAt(at(day, "10:00"));
        long b = bookAt(at(day, "10:45"));  // exactly 45 min later: inside the window
        long c = bookAt(at(day, "11:31"));  // 46 min after b, 91 after a: outside
        long other = bookAt(at(day, "10:20"));
        call("POST", "/api/driver/rides/" + a + "/accept", d1, null, 200);
        Resp warn = call("POST", "/api/driver/rides/" + b + "/accept", d1, null, null);
        assertConflict(warn, "PROXIMITY_WARNING");
        JsonNode conflicting = warn.body().get("conflictingRide");
        assertThat(conflicting.get("id").asLong()).isEqualTo(a);
        assertThat(conflicting.get("fromAddress").asText()).isEqualTo("Start");
        assertThat(Instant.parse(conflicting.get("scheduledAt").asText())).isEqualTo(at(day, "10:00"));
        assertThat(status(b)).isEqualTo("REQUESTED"); // nothing changed
        assertThat(call("POST", "/api/driver/rides/" + b + "/accept", d1, Map.of("confirmProximity", false), null).code()).isEqualTo("PROXIMITY_WARNING");
        assertThat(call("POST", "/api/driver/rides/" + b + "/accept", d1, Map.of("confirmProximity", true), 200).body().get("status").asText()).isEqualTo("ACCEPTED");
        call("POST", "/api/driver/rides/" + c + "/accept", d1, null, 200); // 46 min after b: no warning
        // the warning concerns the driver's own rides only
        call("POST", "/api/driver/rides/" + other + "/accept", d2, null, 200);
        // cancelled rides do not count
        long z = bookAt(at("2027-09-15", "10:00"));
        long y = bookAt(at("2027-09-15", "10:10"));
        call("POST", "/api/driver/rides/" + z + "/accept", d3, null, 200);
        call("POST", "/api/rides/" + z + "/cancel", p1, null, 200);
        call("POST", "/api/driver/rides/" + y + "/accept", d3, null, 200);
    }
}
