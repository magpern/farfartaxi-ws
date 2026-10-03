package com.farfartaxi.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.farfartaxi.backend.model.OfferStatus;
import com.farfartaxi.backend.model.RideNotificationSentEntity;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** M1 gate: decline-all, keep-waiting, NOW/SCHEDULED timers, reminders once-only, availability, idempotency, world isolation. */
class RideOffersAndTimersIntegrationTest extends M1TestSupport {

    private List<String> sentKinds(long rideId) {
        return sentRepo.findByRideId(rideId).stream().map(RideNotificationSentEntity::getKind).sorted().toList();
    }

    private long sentCount(long rideId, String kind) {
        return sentKinds(rideId).stream().filter(kind::equals).count();
    }

    private boolean hasOffer(long rideId, long driverId) {
        return offerRepo.findByRideIdAndDriverId(rideId, driverId).isPresent();
    }

    @Test
    void declineByAllDriversMakesNoDriverAndKeepWaitingIsRefused() throws Exception {
        long id = bookAt(at("2027-05-03", "10:00"));
        // every real driver but the last declines: still REQUESTED
        List<String> holders = new ArrayList<>();
        for (String t : realDrivers) {
            if (openIds(t).contains(id)) {
                holders.add(t);
            }
        }
        assertThat(holders).contains(d1, d2, d3);
        for (int i = 0; i < holders.size() - 1; i++) {
            call("POST", "/api/driver/rides/" + id + "/decline", holders.get(i), Map.of("comment", "nej"), 200);
            assertThat(status(id)).isEqualTo("REQUESTED");
        }
        JsonNode last = call("POST", "/api/driver/rides/" + id + "/refuse", holders.get(holders.size() - 1), Map.of("comment", "nej"), 200).body(); // alias
        assertThat(last.get("myOfferStatus").asText()).isEqualTo("DECLINED");
        assertThat(status(id)).isEqualTo("NO_DRIVER");
        assertThat(timeline(id)).endsWith("NO_DRIVER");
        assertThat(actions(ride(p1, id))).containsExactlyInAnyOrder("CANCEL", "EDIT"); // no KEEP_WAITING: everybody declined
        assertConflict(call("POST", "/api/rides/" + id + "/keep-waiting", p1, null, null), "ALL_DECLINED");
        assertThat(status(id)).isEqualTo("NO_DRIVER");
    }

    @Test
    void nowRideTimesOutRepushesAndKeepWaitingGivesAFreshWindow() throws Exception {
        setAvailability(d2, false, null, null);                      // not available now: no immediate offer
        setAvailability(d3, true, "2027-03-01", "2027-03-01");       // away today: never offered a NOW ride
        long id = bookNow(p1);
        JsonNode booked = ride(p1, id);
        assertThat(booked.get("kind").asText()).isEqualTo("NOW");
        assertThat(Instant.parse(booked.get("scheduledAt").asText())).isEqualTo(BASE);
        assertThat(openIds(d1)).contains(id);
        assertThat(openIds(d2)).doesNotContain(id);
        assertThat(hasOffer(id, d3Id)).isFalse();

        clock.set(BASE.plus(Duration.ofMinutes(9)));
        timers.tick();
        assertThat(openIds(d2)).doesNotContain(id);
        assertThat(sentCount(id, "NOW_REPUSH")).isZero();

        clock.set(BASE.plus(Duration.ofMinutes(10)));
        timers.tick();
        assertThat(openIds(d2)).contains(id);        // re-push also reaches not-available-now drivers
        assertThat(hasOffer(id, d3Id)).isFalse();    // ...but never away ones
        assertThat(sentCount(id, "NOW_REPUSH")).isEqualTo(1);
        clock.set(BASE.plus(Duration.ofMinutes(11)));
        timers.tick();
        timers.tick();
        assertThat(sentCount(id, "NOW_REPUSH")).isEqualTo(1);
        assertThat(status(id)).isEqualTo("REQUESTED");

        clock.set(BASE.plus(Duration.ofMinutes(19)));
        timers.tick();
        assertThat(status(id)).isEqualTo("REQUESTED");
        clock.set(BASE.plus(Duration.ofMinutes(20)));
        timers.tick();
        assertThat(status(id)).isEqualTo("NO_DRIVER");
        assertThat(openIds(d1)).doesNotContain(id);
        assertThat(offerRepo.findByRideId(id)).allMatch(o -> o.getStatus() == OfferStatus.EXPIRED);
        assertThat(timeline(id)).endsWith("NO_DRIVER");
        assertThat(actions(ride(p1, id))).contains("KEEP_WAITING");
    }

    @Test
    void keepWaitingReoffersExpiredOnlyAndStartsNewWindow() throws Exception {
        setAvailability(d3, false, null, null);
        long id = bookNow(p1);
        call("POST", "/api/driver/rides/" + id + "/decline", d1, null, 200);
        clock.set(BASE.plus(Duration.ofMinutes(21)));
        timers.tick();
        assertThat(status(id)).isEqualTo("NO_DRIVER");
        assertThat(actions(ride(p1, id))).contains("KEEP_WAITING");

        JsonNode kept = call("POST", "/api/rides/" + id + "/keep-waiting", p1, null, 200).body();
        assertThat(kept.get("status").asText()).isEqualTo("REQUESTED");
        assertThat(openIds(d2)).contains(id);        // expired offer re-offered
        assertThat(openIds(d3)).contains(id);        // NOW: drivers not yet offered are offered too
        assertThat(openIds(d1)).doesNotContain(id);  // decliner stays declined
        assertThat(timeline(id)).endsWith("KEPT_WAITING");

        clock.set(BASE.plus(Duration.ofMinutes(40)));
        timers.tick();
        assertThat(status(id)).isEqualTo("REQUESTED");   // 19 min into the new window
        clock.set(BASE.plus(Duration.ofMinutes(42)));
        timers.tick();
        assertThat(status(id)).isEqualTo("NO_DRIVER");   // 21 min into the new window
    }

    @Test
    void nowKindIgnoresScheduledAtAndScheduledRequiresFuture() throws Exception {
        Map<String, Object> now = rideBody(Instant.parse("2020-01-01T00:00:00Z"));
        now.put("kind", "NOW");
        JsonNode r = call("POST", "/api/rides", p1, now, 200).body();
        assertThat(Instant.parse(r.get("scheduledAt").asText())).isEqualTo(BASE);
        assertThat(r.get("kind").asText()).isEqualTo("NOW");
        Map<String, Object> past = rideBody(Instant.parse("2020-01-01T00:00:00Z"));
        assertThat(call("POST", "/api/rides", p1, past, null).status()).isEqualTo(400);
        Map<String, Object> missing = rideBody(BASE);
        missing.remove("scheduledAt");
        assertThat(call("POST", "/api/rides", p1, missing, null).status()).isEqualTo(400);
        Map<String, Object> note = rideBody(at("2027-05-04", "10:00"));
        note.put("pickupNote", "Port 3, ring på");
        JsonNode withNote = call("POST", "/api/rides", p1, note, 200).body();
        assertThat(withNote.get("pickupNote").asText()).isEqualTo("Port 3, ring på");
        assertThat(ride(d1, withNote.get("id").asLong()).get("pickupNote").asText()).isEqualTo("Port 3, ring på");
    }

    @Test
    void scheduledRideStillRequestedAtItsTimeBecomesNoDriver() throws Exception {
        Instant s = BASE.plus(Duration.ofDays(3));
        long id = bookAt(s);
        call("POST", "/api/driver/rides/" + id + "/decline", d1, null, 200);
        clock.set(s.minusSeconds(60));
        timers.tick();
        assertThat(status(id)).isEqualTo("REQUESTED");
        clock.set(s);
        timers.tick();
        assertThat(status(id)).isEqualTo("NO_DRIVER");
        assertThat(timeline(id)).endsWith("NO_DRIVER");
        assertThat(openIds(d2)).doesNotContain(id);
        assertThat(ride(p1, id).get("urgent").asBoolean()).isFalse();
        // nothing to keep waiting for once the time has passed; editing the time re-offers everybody incl. decliners
        assertConflict(call("POST", "/api/rides/" + id + "/keep-waiting", p1, null, null), "INVALID_TRANSITION");
        call("PATCH", "/api/rides/" + id, p1, Map.of("scheduledAt", s.plus(Duration.ofDays(1)).toString()), 200);
        assertThat(status(id)).isEqualTo("REQUESTED");
        assertThat(openIds(d1)).contains(id);
        assertThat(openIds(d2)).contains(id);
        // an accepted scheduled ride is left alone when its time passes
        long acc = bookAt(clock.instant().plus(Duration.ofDays(2)));
        acceptOk(d2, acc);
        clock.set(clock.instant().plus(Duration.ofDays(3)));
        timers.tick();
        assertThat(status(acc)).isEqualTo("ACCEPTED");
    }

    @Test
    void remindersAndUrgentAreRecordedOnceOnly() throws Exception {
        Instant s = BASE.plus(Duration.ofDays(3));   // 2027-03-04T08:00Z
        long id = bookAt(s);
        clock.set(s.minus(Duration.ofHours(24)).minusSeconds(60));
        timers.tick();
        assertThat(sentKinds(id)).isEmpty();
        clock.set(s.minus(Duration.ofHours(24)).plusSeconds(60));
        timers.tick();
        timers.tick();
        assertThat(sentKinds(id)).containsExactly("REMINDER_24H");
        clock.set(s.minus(Duration.ofHours(2)).plusSeconds(60));
        timers.tick();
        timers.tick();
        assertThat(sentKinds(id)).containsExactly("REMINDER_24H", "REMINDER_2H");
        assertThat(ride(p1, id).get("urgent").asBoolean()).isFalse();
        clock.set(s.minus(Duration.ofMinutes(59)));
        timers.tick();
        timers.tick();
        assertThat(sentKinds(id)).containsExactly("REMINDER_24H", "REMINDER_2H", "URGENT");
        assertThat(ride(p1, id).get("urgent").asBoolean()).isTrue();
        assertThat(ride(d1, id).get("urgent").asBoolean()).isTrue();
        clock.set(s.minus(Duration.ofMinutes(10)));
        timers.tick();
        assertThat(sentKinds(id)).hasSize(3);
        // accepting clears urgent
        acceptOk(d1, id);
        assertThat(ride(p1, id).get("urgent").asBoolean()).isFalse();

        // assigned-driver reminder at T-30 min, once
        clock.set(BASE);
        Instant s2 = BASE.plus(Duration.ofDays(5));
        long b = bookAt(s2);
        acceptOk(d2, b);
        clock.set(s2.minus(Duration.ofMinutes(31)));
        timers.tick();
        assertThat(sentKinds(b)).isEmpty();
        clock.set(s2.minus(Duration.ofMinutes(29)));
        timers.tick();
        timers.tick();
        assertThat(sentKinds(b)).containsExactly("DRIVER_REMINDER_30M");

        // a ride booked inside the window gets no 24h/2h reminder (the booking offer was the reminder)
        Instant s3 = BASE.plus(Duration.ofDays(8));
        clock.set(s3.minus(Duration.ofMinutes(90)));
        long late = bookAt(s3);
        timers.tick();
        assertThat(sentKinds(late)).isEmpty();
        clock.set(s3.minus(Duration.ofMinutes(59)));
        timers.tick();
        assertThat(sentKinds(late)).containsExactly("URGENT");
    }

    @Test
    void awayDriverGetsNoOfferOnAwayDayButDoesOnTheNext() throws Exception {
        assertThat(java.time.LocalDate.parse("2027-03-06").getDayOfWeek()).isEqualTo(java.time.DayOfWeek.SATURDAY);
        setAvailability(d1, true, "2027-03-06", "2027-03-06");
        long sat = bookAt(at("2027-03-06", "10:00"));
        long sun = bookAt(at("2027-03-07", "10:00"));
        assertThat(openIds(d1)).contains(sun).doesNotContain(sat);
        assertThat(openIds(d2)).contains(sat, sun);
        assertThat(hasOffer(sat, d1Id)).isFalse();
        // Stockholm calendar days, not UTC days: Sat 00:30 local is still Friday in UTC; Sun 00:30 local is Saturday in UTC
        long satEarly = bookAt(at("2027-03-06", "00:30"));
        long satLate = bookAt(at("2027-03-06", "23:30"));
        long sunEarly = bookAt(at("2027-03-07", "00:30"));
        assertThat(hasOffer(satEarly, d1Id)).isFalse();
        assertThat(hasOffer(satLate, d1Id)).isFalse();
        assertThat(hasOffer(sunEarly, d1Id)).isTrue();
        // the away period is inclusive at both ends and ignores the available-now toggle
        setAvailability(d2, false, "2027-03-06", "2027-03-07");
        long sat2 = bookAt(at("2027-03-06", "15:00"));
        long sun2 = bookAt(at("2027-03-07", "15:00"));
        long mon = bookAt(at("2027-03-08", "15:00"));
        assertThat(hasOffer(sat2, d2Id)).isFalse();
        assertThat(hasOffer(sun2, d2Id)).isFalse();
        assertThat(hasOffer(mon, d2Id)).isTrue();           // scheduled rides ignore "available now"
        // an away driver can still reach a NOW ride today (away is about the ride's date)
        setAvailability(d3, true, "2027-03-06", "2027-03-07");
        long nowRide = bookNow(p1);
        assertThat(hasOffer(nowRide, d3Id)).isTrue();
    }

    @Test
    void availabilityEndpoint() throws Exception {
        JsonNode def = call("GET", "/api/driver/availability", d1, null, 200).body();
        assertThat(def.get("availableNow").asBoolean()).isTrue();
        assertThat(def.get("awayFrom").isNull()).isTrue();
        Map<String, Object> put = new HashMap<>();
        put.put("availableNow", false);
        put.put("awayFrom", "2027-07-01");
        put.put("awayUntil", "2027-07-14");
        JsonNode saved = call("PUT", "/api/driver/availability", d1, put, 200).body();
        assertThat(saved.get("availableNow").asBoolean()).isFalse();
        assertThat(saved.get("awayFrom").asText()).isEqualTo("2027-07-01");
        assertThat(call("GET", "/api/driver/availability", d1, null, 200).body().get("awayUntil").asText()).isEqualTo("2027-07-14");
        assertThat(call("GET", "/api/driver/availability", d2, null, 200).body().get("availableNow").asBoolean()).isTrue(); // per user
        put.put("awayUntil", "2027-06-30");
        assertThat(call("PUT", "/api/driver/availability", d1, put, null).status()).isEqualTo(400);
        put.put("awayUntil", null);
        assertThat(call("PUT", "/api/driver/availability", d1, put, null).status()).isEqualTo(400);
        assertThat(call("PUT", "/api/driver/availability", d1, Map.of("awayFrom", "2027-07-01"), null).status()).isEqualTo(400);
        assertThat(call("PUT", "/api/driver/availability", p1, put, null).status()).isEqualTo(403);
        // clearing
        setAvailability(d1, true, null, null);
        assertThat(call("GET", "/api/driver/availability", d1, null, 200).body().get("awayFrom").isNull()).isTrue();
    }

    @Test
    void idempotentBooking() throws Exception {
        Map<String, Object> body = rideBody(at("2027-06-01", "10:00"));
        String key = "11111111-1111-1111-1111-111111111111";
        Resp first = call("POST", "/api/rides", p1, body, 200, "Idempotency-Key", key);
        Resp second = call("POST", "/api/rides", p1, body, 200, "Idempotency-Key", key);
        assertThat(second.body().get("id").asLong()).isEqualTo(first.body().get("id").asLong());
        long count = rideRepo.findAll().stream().filter(r -> key.equals(r.getClientRequestId())).count();
        assertThat(count).isEqualTo(1);
        Resp other = call("POST", "/api/rides", p1, body, 200, "Idempotency-Key", "22222222-2222-2222-2222-222222222222");
        assertThat(other.body().get("id").asLong()).isNotEqualTo(first.body().get("id").asLong());
        // no key: every call is a new ride
        long a = call("POST", "/api/rides", p1, body, 200).body().get("id").asLong();
        long b = call("POST", "/api/rides", p1, body, 200).body().get("id").asLong();
        assertThat(a).isNotEqualTo(b);
        // the key is scoped to the passenger
        Resp otherPassenger = call("POST", "/api/rides", p2, body, 200, "Idempotency-Key", key);
        assertThat(otherPassenger.body().get("id").asLong()).isNotEqualTo(first.body().get("id").asLong());
        // length limit
        assertThat(call("POST", "/api/rides", p1, body, null, "Idempotency-Key", "x".repeat(65)).status()).isEqualTo(400);
        assertThat(call("POST", "/api/rides", p1, body, 200, "Idempotency-Key", "x".repeat(64)).status()).isEqualTo(200);
    }

    @Test
    void concurrentBookingsWithTheSameKeyYieldOneRide() throws Exception {
        Map<String, Object> body = rideBody(at("2027-06-02", "10:00"));
        String key = "33333333-3333-3333-3333-333333333333";
        CyclicBarrier barrier = new CyclicBarrier(4);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<Resp>> fs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Callable<Resp> c = () -> {
                barrier.await();
                return call("POST", "/api/rides", p1, body, null, "Idempotency-Key", key);
            };
            fs.add(pool.submit(c));
        }
        long firstId = -1;
        for (Future<Resp> f : fs) {
            Resp r = f.get();
            assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
            long rid = r.body().get("id").asLong();
            firstId = firstId == -1 ? rid : firstId;
            assertThat(rid).isEqualTo(firstId);
        }
        pool.shutdown();
        assertThat(rideRepo.findAll().stream().filter(r -> key.equals(r.getClientRequestId())).count()).isEqualTo(1);
    }

    @Test
    void offersAndAvailabilityAreWorldIsolated() throws Exception {
        long testRide = bookAt(tp, at("2027-06-10", "10:00"));
        long realRide = bookAt(p1, at("2027-06-10", "10:00"));
        // offers are created for same-world drivers only
        assertThat(offerRepo.findByRideId(testRide)).extracting(o -> o.getDriverId()).containsExactly(tdId);
        assertThat(hasOffer(realRide, tdId)).isFalse();
        assertThat(openIds(td)).contains(testRide).doesNotContain(realRide);
        assertThat(openIds(d1)).contains(realRide).doesNotContain(testRide);
        assertThat(openIds(adminToken)).doesNotContain(testRide);
        // cross-world access to the new endpoints is a 404 in both directions
        assertThat(call("POST", "/api/driver/rides/" + realRide + "/accept", td, null, null).status()).isEqualTo(404);
        assertThat(call("POST", "/api/driver/rides/" + testRide + "/accept", d1, null, null).status()).isEqualTo(404);
        assertThat(call("POST", "/api/driver/rides/" + testRide + "/decline", d1, null, null).status()).isEqualTo(404);
        assertThat(call("GET", "/api/rides/" + testRide, d1, null, null).status()).isEqualTo(404);
        assertThat(call("GET", "/api/rides/" + testRide + "/messages", p1, null, null).status()).isEqualTo(404);
        assertThat(call("PATCH", "/api/rides/" + testRide, p1, Map.of("pickupNote", "x"), null).status()).isEqualTo(404);
        assertThat(call("POST", "/api/rides/" + realRide + "/keep-waiting", tp, null, null).status()).isEqualTo(404);
        // availability of a test driver does not leak into the real world and vice versa
        setAvailability(td, true, "2027-06-10", "2027-06-10");
        long testRide2 = bookAt(tp, at("2027-06-10", "12:00"));
        assertThat(hasOffer(testRide2, tdId)).isFalse();
        assertThat(call("GET", "/api/driver/availability", d1, null, 200).body().get("awayFrom").isNull()).isTrue();
        long realRide2 = bookAt(p1, at("2027-06-10", "12:00"));
        assertThat(hasOffer(realRide2, d1Id)).isTrue();
        // the test world has its own full lifecycle, incl. NO_DRIVER when its only driver declines
        setAvailability(td, true, null, null);
        long testRide3 = bookAt(tp, at("2027-06-11", "10:00"));
        call("POST", "/api/driver/rides/" + testRide3 + "/decline", td, null, 200);
        assertThat(call("GET", "/api/rides/" + testRide3, tp, null, 200).body().get("status").asText()).isEqualTo("NO_DRIVER");
        // real drivers unaffected
        assertThat(status(realRide)).isEqualTo("REQUESTED");
        // test-world rides are served by the test driver; a real driver cannot message on them
        acceptOk(td, testRide);
        assertThat(call("POST", "/api/rides/" + testRide + "/messages", d1, Map.of("code", "DRIVER_HERE"), null).status()).isEqualTo(404);
        assertThat(call("POST", "/api/rides/" + testRide + "/messages", td, Map.of("code", "DRIVER_HERE"), 200).status()).isEqualTo(200);
    }
}
