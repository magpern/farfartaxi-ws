package com.farfartaxi.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.farfartaxi.backend.model.OfferStatus;
import com.farfartaxi.backend.model.RideOfferEntity;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** M1 gate: edit + material-change rule, messages, phone privacy, availableActions. */
class RideEditAndMessagesIntegrationTest extends M1TestSupport {
    private static final Instant S = at("2027-07-05", "10:00");

    private JsonNode patch(String token, long id, Map<String, Object> body, int expected) throws Exception {
        return call("PATCH", "/api/rides/" + id, token, body, expected).body();
    }

    private RideOfferEntity offer(long rideId, long driverId) {
        return offerRepo.findByRideIdAndDriverId(rideId, driverId).orElseThrow();
    }

    @Test
    void materialEditOfAcceptedRideReoffersSameDriverFirst() throws Exception {
        long earlier = bookAt(S.minus(Duration.ofDays(1))); // an earlier ride so ordering by time alone would put it first
        long id = bookAt(S);
        call("POST", "/api/driver/rides/" + id + "/decline", d3, null, 200);
        acceptOk(d1, id);

        JsonNode edited = patch(p1, id, Map.of("scheduledAt", S.plus(Duration.ofHours(2)).toString()), 200);
        assertThat(edited.get("status").asText()).isEqualTo("REQUESTED");
        assertThat(edited.get("lastEditMaterial").asBoolean()).isTrue();
        assertThat(edited.get("acceptedByDriverId").isNull()).isTrue();
        assertThat(actions(edited)).containsExactlyInAnyOrder("CANCEL", "EDIT");
        assertThat(ride(p1, id).get("lastEditMaterial").isNull()).isTrue(); // only on PATCH responses

        assertThat(offer(id, d1Id).getStatus()).isEqualTo(OfferStatus.OFFERED);
        assertThat(offer(id, d1Id).isPriority()).isTrue();
        assertThat(offer(id, d2Id).getStatus()).isEqualTo(OfferStatus.OFFERED);
        assertThat(offer(id, d2Id).isPriority()).isFalse();
        assertThat(offer(id, d3Id).getStatus()).isEqualTo(OfferStatus.DECLINED);
        assertThat(openIds(d2)).contains(id);
        assertThat(openIds(d3)).doesNotContain(id);
        List<Long> d1List = openIdList(d1);
        assertThat(d1List).contains(earlier, id);
        assertThat(d1List.indexOf(id)).isLessThan(d1List.indexOf(earlier)); // priority first
        assertThat(timeline(id)).endsWith("EDITED");

        JsonNode again = acceptOk(d1, id);
        assertThat(again.get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(offer(id, d2Id).getStatus()).isEqualTo(OfferStatus.WITHDRAWN);
    }

    @Test
    void minorEditKeepsAcceptedAndTimeShiftBoundaryIs30Minutes() throws Exception {
        long id = bookAt(S.plus(Duration.ofDays(1)));
        acceptOk(d1, id);
        Map<String, Object> minor = new HashMap<>();
        minor.put("pickupNote", "Ring på porten");
        minor.put("toAddress", "Goal 2");
        minor.put("toLat", 59.3345);                                  // ~55 m
        minor.put("scheduledAt", S.plus(Duration.ofDays(1)).plus(Duration.ofMinutes(30)).toString()); // exactly 30 min
        JsonNode r = patch(p1, id, minor, 200);
        assertThat(r.get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(r.get("lastEditMaterial").asBoolean()).isFalse();
        assertThat(r.get("acceptedByDriverId").asLong()).isEqualTo(d1Id);
        assertThat(r.get("pickupNote").asText()).isEqualTo("Ring på porten");
        assertThat(r.get("toAddress").asText()).isEqualTo("Goal 2");
        assertThat(actions(ride(d1, id))).contains("START"); // still the driver's ride
        // 31 minutes more is material
        JsonNode m = patch(p1, id, Map.of("scheduledAt", S.plus(Duration.ofDays(1)).plus(Duration.ofMinutes(61)).toString()), 200);
        assertThat(m.get("status").asText()).isEqualTo("REQUESTED");
        assertThat(m.get("lastEditMaterial").asBoolean()).isTrue();
    }

    @Test
    void pickupMovedMoreThan500mIsMaterial() throws Exception {
        long near = bookAt(S.plus(Duration.ofDays(2)));
        acceptOk(d1, near);
        JsonNode minor = patch(p1, near, Map.of("fromLat", 59.3329), 200); // ~400 m
        assertThat(minor.get("status").asText()).isEqualTo("ACCEPTED");
        verify(osrm, atLeastOnce()).drivingRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any());

        long far = bookAt(S.plus(Duration.ofDays(3)));
        acceptOk(d1, far);
        JsonNode material = patch(p1, far, Map.of("fromLat", 59.3350, "fromAddress", "Annat håll"), 200); // ~670 m
        assertThat(material.get("status").asText()).isEqualTo("REQUESTED");
        assertThat(material.get("lastEditMaterial").asBoolean()).isTrue();
    }

    @Test
    void routeLengthRuleUsesOsrmAndFallsBackToHaversine() throws Exception {
        AtomicReference<Double> before = new AtomicReference<>(10000.0);
        AtomicReference<Double> after = new AtomicReference<>(12000.0);
        when(osrm.drivingRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any())).thenAnswer(inv -> {
            double toLat = inv.getArgument(2);
            double d = toLat > 59.3340 ? after.get() : before.get();
            return "{\"code\":\"Ok\",\"routes\":[{\"distance\":" + d + "}]}";
        });
        // +20% and +2 km: not material (the destination only moves ~11 m in a straight line)
        long a = bookAt(S.plus(Duration.ofDays(4)));
        acceptOk(d1, a);
        assertThat(patch(p1, a, Map.of("toLat", 59.3341), 200).get("status").asText()).isEqualTo("ACCEPTED");
        verify(osrm, atLeastOnce()).drivingRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any());
        // +26%: material by the 25% rule
        after.set(12600.0);
        long b = bookAt(S.plus(Duration.ofDays(5)));
        acceptOk(d1, b);
        assertThat(patch(p1, b, Map.of("toLat", 59.3341), 200).get("lastEditMaterial").asBoolean()).isTrue();
        // +15% but +6 km: material by the 5 km rule
        before.set(40000.0);
        after.set(46000.0);
        long c = bookAt(S.plus(Duration.ofDays(6)));
        acceptOk(d1, c);
        assertThat(patch(p1, c, Map.of("toLat", 59.3341), 200).get("lastEditMaterial").asBoolean()).isTrue();
        // time-only edit never asks OSRM
        org.mockito.Mockito.clearInvocations(osrm);
        long d = bookAt(S.plus(Duration.ofDays(7)));
        acceptOk(d1, d);
        patch(p1, d, Map.of("scheduledAt", S.plus(Duration.ofDays(7)).plus(Duration.ofMinutes(10)).toString()), 200);
        verify(osrm, never()).drivingRoute(anyDouble(), anyDouble(), anyDouble(), anyDouble(), any());
        // OSRM down (null): haversine fallback, a destination ~7 km further is material
        org.mockito.Mockito.reset(osrm);
        long e = bookAt(S.plus(Duration.ofDays(8)));
        acceptOk(d1, e);
        JsonNode far = patch(p1, e, Map.of("toLat", 59.40, "toAddress", "Långt bort"), 200);
        assertThat(far.get("status").asText()).isEqualTo("REQUESTED");
        assertThat(far.get("lastEditMaterial").asBoolean()).isTrue();
        // ...while a small move with OSRM down is not
        long f = bookAt(S.plus(Duration.ofDays(9)));
        acceptOk(d1, f);
        assertThat(patch(p1, f, Map.of("toLat", 59.3350), 200).get("status").asText()).isEqualTo("ACCEPTED");
    }

    @Test
    void editRulesPerState() throws Exception {
        long id = bookAt(S.plus(Duration.ofDays(10)));
        // validation
        assertThat(patch(p1, id, Map.of("toAddress", " "), 400).get("error").asText()).isNotBlank();
        assertThat(patch(p1, id, Map.of("fromLat", 100.0), 400).get("error").asText()).isNotBlank();
        patch(p1, id, Map.of("scheduledAt", "2020-01-01T00:00:00Z"), 400);
        // REQUESTED: anything goes, status unchanged; an empty note clears it
        patch(p1, id, Map.of("pickupNote", "först"), 200);
        JsonNode cleared = patch(p1, id, Map.of("pickupNote", "", "fromAddress", "Ny gata 1"), 200);
        assertThat(cleared.get("status").asText()).isEqualTo("REQUESTED");
        assertThat(cleared.get("pickupNote").isNull()).isTrue();
        assertThat(cleared.get("fromAddress").asText()).isEqualTo("Ny gata 1");
        assertThat(cleared.get("lastEditMaterial").asBoolean()).isFalse();
        // another passenger / a driver cannot edit
        patch(p2, id, Map.of("pickupNote", "x"), 403);

        // NO_DRIVER: a non-time edit keeps it, a time edit re-offers everybody incl. decliners
        declineAllReal(id);
        assertThat(status(id)).isEqualTo("NO_DRIVER");
        assertThat(patch(p1, id, Map.of("pickupNote", "ny anteckning"), 200).get("status").asText()).isEqualTo("NO_DRIVER");
        JsonNode reopened = patch(p1, id, Map.of("scheduledAt", S.plus(Duration.ofDays(10)).plus(Duration.ofHours(1)).toString()), 200);
        assertThat(reopened.get("status").asText()).isEqualTo("REQUESTED");
        for (String t : List.of(d1, d2, d3)) {
            assertThat(openIds(t)).contains(id);
        }
        // time edit while REQUESTED does not reopen decliners
        call("POST", "/api/driver/rides/" + id + "/decline", d3, null, 200);
        patch(p1, id, Map.of("scheduledAt", S.plus(Duration.ofDays(10)).plus(Duration.ofHours(2)).toString()), 200);
        assertThat(openIds(d3)).doesNotContain(id);
        assertThat(openIds(d1)).contains(id);

        // EN_ROUTE, ARRIVED, PICKED_UP, COMPLETED, CANCELLED: EDIT_NOT_ALLOWED
        long run = bookAt(S.plus(Duration.ofDays(11)));
        acceptOk(d1, run);
        drive(d1, run, "start");
        assertConflict(call("PATCH", "/api/rides/" + run, p1, Map.of("pickupNote", "x"), null), "EDIT_NOT_ALLOWED");
        drive(d1, run, "arrive");
        assertConflict(call("PATCH", "/api/rides/" + run, p1, Map.of("pickupNote", "x"), null), "EDIT_NOT_ALLOWED");
        drive(d1, run, "pickup");
        assertConflict(call("PATCH", "/api/rides/" + run, p1, Map.of("pickupNote", "x"), null), "EDIT_NOT_ALLOWED");
        drive(d1, run, "complete");
        assertConflict(call("PATCH", "/api/rides/" + run, p1, Map.of("pickupNote", "x"), null), "EDIT_NOT_ALLOWED");
        long gone = bookAt(S.plus(Duration.ofDays(12)));
        call("POST", "/api/rides/" + gone + "/cancel", p1, null, 200);
        assertConflict(call("PATCH", "/api/rides/" + gone, p1, Map.of("pickupNote", "x"), null), "EDIT_NOT_ALLOWED");
        assertThat(status(run)).isEqualTo("COMPLETED");
    }

    @Test
    void movingARequestedRideOntoAnAwayDayWithdrawsThatDriversOffer() throws Exception {
        setAvailability(d1, true, "2027-07-24", "2027-07-24"); // Saturday
        long id = bookAt(at("2027-07-23", "10:00"));            // Friday
        assertThat(openIds(d1)).contains(id);
        patch(p1, id, Map.of("scheduledAt", at("2027-07-24", "10:00").toString()), 200);
        assertThat(openIds(d1)).doesNotContain(id);
        assertThat(openIds(d2)).contains(id);
        patch(p1, id, Map.of("scheduledAt", at("2027-07-25", "10:00").toString()), 200);
        assertThat(openIds(d1)).contains(id);
    }

    @Test
    void messagesParticipantsAllowedStatesAndCannedCodesOnly() throws Exception {
        long id = bookAt(S.plus(Duration.ofDays(13)));
        // REQUESTED: nobody to talk to
        assertConflict(call("POST", "/api/rides/" + id + "/messages", p1, Map.of("code", "PASSENGER_OUTSIDE"), null), "INVALID_TRANSITION");
        acceptOk(d1, id);
        Resp m1 = call("POST", "/api/rides/" + id + "/messages", p1, Map.of("code", "PASSENGER_OUTSIDE"), 200);
        assertThat(m1.body().get("text").asText()).isEqualTo("Jag står utanför");
        assertThat(m1.body().get("code").asText()).isEqualTo("PASSENGER_OUTSIDE");
        assertThat(m1.body().get("senderId").asLong()).isEqualTo(p1Id);
        // free text is ignored: the stored text is always the canned one
        Map<String, Object> sneaky = new HashMap<>();
        sneaky.put("code", "PASSENGER_TWO_MIN");
        sneaky.put("text", "hej hopp");
        assertThat(call("POST", "/api/rides/" + id + "/messages", p1, sneaky, 200).body().get("text").asText()).isEqualTo("Kommer om 2 min");
        assertThat(call("POST", "/api/rides/" + id + "/messages", p1, Map.of("code", "PASSENGER_CALL_ME"), 200).status()).isEqualTo(200);
        assertThat(call("POST", "/api/rides/" + id + "/messages", d1, Map.of("code", "DRIVER_HERE"), 200).body().get("text").asText()).isEqualTo("Jag är här");
        call("POST", "/api/rides/" + id + "/messages", d1, Map.of("code", "DRIVER_TWO_MIN"), 200);
        call("POST", "/api/rides/" + id + "/messages", d1, Map.of("code", "DRIVER_LATE"), 200);
        // canned codes only, and only the sender's own role's codes
        assertThat(call("POST", "/api/rides/" + id + "/messages", p1, Map.of("code", "HEJ"), null).status()).isEqualTo(400);
        assertThat(call("POST", "/api/rides/" + id + "/messages", p1, Map.of("code", ""), null).status()).isEqualTo(400);
        assertThat(call("POST", "/api/rides/" + id + "/messages", p1, Map.of("code", "DRIVER_HERE"), null).status()).isEqualTo(400);
        assertThat(call("POST", "/api/rides/" + id + "/messages", d1, Map.of("code", "PASSENGER_OUTSIDE"), null).status()).isEqualTo(400);
        // participants only
        for (String t : List.of(p2, d2, adminToken)) {
            assertThat(call("GET", "/api/rides/" + id + "/messages", t, null, null).status()).isEqualTo(403);
            assertThat(call("POST", "/api/rides/" + id + "/messages", t, Map.of("code", "DRIVER_HERE"), null).status()).isEqualTo(403);
        }
        // reading marks the other party's messages as read
        JsonNode forDriver = call("GET", "/api/rides/" + id + "/messages", d1, null, 200).body();
        assertThat(forDriver).hasSize(6);
        assertThat(forDriver.get(0).get("code").asText()).isEqualTo("PASSENGER_OUTSIDE");
        JsonNode forPassenger = call("GET", "/api/rides/" + id + "/messages", p1, null, 200).body();
        assertThat(forPassenger.get(0).get("readAt").isNull()).isFalse(); // read by the driver
        assertThat(forPassenger.get(3).get("readAt").isNull()).isFalse(); // driver's first message, read by this GET
        // allowed in EN_ROUTE, ARRIVED and PICKED_UP, not after
        drive(d1, id, "start");
        call("POST", "/api/rides/" + id + "/messages", p1, Map.of("code", "PASSENGER_OUTSIDE"), 200);
        drive(d1, id, "arrive");
        call("POST", "/api/rides/" + id + "/messages", d1, Map.of("code", "DRIVER_HERE"), 200);
        drive(d1, id, "pickup");
        call("POST", "/api/rides/" + id + "/messages", p1, Map.of("code", "PASSENGER_CALL_ME"), 200);
        drive(d1, id, "complete");
        assertConflict(call("POST", "/api/rides/" + id + "/messages", p1, Map.of("code", "PASSENGER_OUTSIDE"), null), "INVALID_TRANSITION");
        assertConflict(call("POST", "/api/rides/" + id + "/messages", d1, Map.of("code", "DRIVER_HERE"), null), "INVALID_TRANSITION");
        assertThat(call("GET", "/api/rides/" + id + "/messages", p1, null, 200).body()).hasSize(9);
        // ride_events stays a pure state timeline
        assertThat(timeline(id)).containsExactly("BOOKED", "ACCEPTED", "STARTED", "ARRIVED", "PICKED_UP", "COMPLETED");

        // a cancelled ride and a driver who returned the ride cannot message either
        long c = bookAt(S.plus(Duration.ofDays(14)));
        acceptOk(d1, c);
        call("POST", "/api/driver/rides/" + c + "/return", d1, null, 200);
        assertThat(call("GET", "/api/rides/" + c + "/messages", d1, null, null).status()).isEqualTo(403);
        acceptOk(d2, c);
        call("POST", "/api/rides/" + c + "/cancel", p1, null, 200);
        assertConflict(call("POST", "/api/rides/" + c + "/messages", p1, Map.of("code", "PASSENGER_OUTSIDE"), null), "INVALID_TRANSITION");
    }

    @Test
    void phonesOnlyForParticipantsOfAnAcceptedRide() throws Exception {
        setPhone(p1Id, "070-111 11 11");
        setPhone(d1Id, "070-222 22 22");
        setPhone(d2Id, "070-333 33 33");
        long id = bookAt(S.plus(Duration.ofDays(15)));
        // before acceptance nobody sees a phone; offered drivers see the passenger's name (for the request card)
        JsonNode asPassenger = ride(p1, id);
        assertThat(asPassenger.get("driverPhone").isNull()).isTrue();
        assertThat(asPassenger.get("passengerPhone").isNull()).isTrue();
        JsonNode asOffered = ride(d1, id);
        assertThat(asOffered.get("passengerName").asText()).isEqualTo("m1-p1@test.local");
        assertThat(asOffered.get("passengerPhone").isNull()).isTrue();
        assertThat(call("GET", "/api/driver/rides/open", d2, null, 200).body().toString()).doesNotContain("070-");

        acceptOk(d1, id);
        for (String step : List.of("accepted", "start", "arrive", "pickup")) {
            if (!step.equals("accepted")) {
                drive(d1, id, step);
            }
            JsonNode p = ride(p1, id);
            assertThat(p.get("driverPhone").asText()).as(step).isEqualTo("070-222 22 22");
            assertThat(p.get("passengerPhone").isNull()).as(step).isTrue();
            assertThat(p.get("driverVehicleNote")).isNotNull();
            JsonNode d = ride(d1, id);
            assertThat(d.get("passengerPhone").asText()).as(step).isEqualTo("070-111 11 11");
            assertThat(d.get("driverPhone").isNull()).as(step).isTrue();
            // everybody else sees no phones: admin oversight, the share link
            JsonNode a = ride(adminToken, id);
            assertThat(a.get("passengerPhone").isNull()).isTrue();
            assertThat(a.get("driverPhone").isNull()).isTrue();
        }
        String token = call("POST", "/api/rides/" + id + "/share", p1, null, 200).body().get("token").asText();
        JsonNode shared = call("GET", "/api/public/share/" + token, null, null, 200, "X-Forwarded-For", "10.9.9.1").body();
        assertThat(shared.toString()).doesNotContain("070-");
        assertThat(shared.has("pickupNote")).isFalse();
        assertThat(shared.has("availableActions")).isFalse();
        // the other offered driver is shut out with a stale code, not a leak
        assertConflict(call("GET", "/api/rides/" + id, d2, null, null), "RIDE_TAKEN");

        drive(d1, id, "complete");
        assertThat(ride(p1, id).get("driverPhone").isNull()).isTrue();
        assertThat(ride(d1, id).get("passengerPhone").isNull()).isTrue();

        // a returned ride loses the driver's phone again
        long r = bookAt(S.plus(Duration.ofDays(16)));
        acceptOk(d1, r);
        assertThat(ride(p1, r).get("driverPhone").asText()).isEqualTo("070-222 22 22");
        call("POST", "/api/driver/rides/" + r + "/return", d1, null, 200);
        assertThat(ride(p1, r).get("driverPhone").isNull()).isTrue();
    }
}
