package com.farfartaxi.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.farfartaxi.backend.model.OfferStatus;
import com.farfartaxi.backend.model.RideOfferEntity;
import com.farfartaxi.backend.service.RideOfferService;
import com.farfartaxi.backend.service.RideTimerService;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Independent-review fixes for M1 (offers must exist to accept, keep-waiting rules, away rule everywhere, ...). */
class ReviewFixesIntegrationTest extends M1TestSupport {
    @Autowired RideOfferService offerService;

    private static final Instant S = at("2027-08-10", "10:00");

    private void declineAllExcept(long id, String... keep) throws Exception {
        List<String> k = Arrays.asList(keep);
        for (String t : realDrivers) {
            if (!k.contains(t) && openIds(t).contains(id)) {
                call("POST", "/api/driver/rides/" + id + "/decline", t, Map.of("comment", "nej"), 200);
            }
        }
    }

    private String offerStatus(long rideId, long driverId) {
        return offerRepo.findByRideIdAndDriverId(rideId, driverId).map(o -> o.getStatus().name()).orElse("NONE");
    }

    // ---------------------------------------------------------------- B1

    @Test
    void driverWithoutOfferCannotAccept() throws Exception {
        // approved after booking
        long id = bookAt(S);
        String late = newDriver();
        assertConflict(accept(late, id), "OFFER_CLOSED");
        assertThat(status(id)).isEqualTo("REQUESTED");
        // away on the date: no offer at booking
        setAvailability(d2, true, "2027-08-10", "2027-08-10");
        long awayRide = bookAt(S);
        assertConflict(accept(d2, awayRide), "OFFER_CLOSED");
        // not available now: no offer for a NOW ride
        setAvailability(d3, false, null, null);
        long now = bookNow(p1);
        assertConflict(accept(d3, now), "OFFER_CLOSED");
        assertThat(status(now)).isEqualTo("REQUESTED");
        assertThat(offerRepo.findByRideIdAndDriverId(now, d3Id)).isEmpty();
    }

    // ---------------------------------------------------------------- B2

    @Test
    void keepWaitingNeverReoffersTheDriverWhoReturnedTheRide() throws Exception {
        long id = bookNow(p1);
        declineAllExcept(id, d1);
        acceptOk(d1, id);
        call("POST", "/api/driver/rides/" + id + "/return", d1, null, 200); // everybody else declined, d1 withdrawn
        assertThat(status(id)).isEqualTo("NO_DRIVER");
        assertConflict(call("POST", "/api/rides/" + id + "/keep-waiting", p1, null, null), "ALL_DECLINED");
        assertThat(actions(ride(p1, id))).doesNotContain("KEEP_WAITING");
    }

    @Test
    void keepWaitingReoffersExpiredButNotReturnerOrAwayDrivers() throws Exception {
        long id = bookNow(p1);
        declineAllExcept(id, d1, d2, d3);
        acceptOk(d1, id);
        call("POST", "/api/driver/rides/" + id + "/return", d1, null, 200); // d2, d3 re-offered
        assertThat(openIds(d2)).contains(id);
        clock.set(BASE.plus(Duration.ofMinutes(21)));
        timers.tick();
        assertThat(status(id)).isEqualTo("NO_DRIVER");
        setAvailability(d3, true, "2027-03-01", "2027-03-01"); // away now: stays expired
        call("POST", "/api/rides/" + id + "/keep-waiting", p1, null, 200);
        assertThat(openIds(d2)).contains(id);
        assertThat(openIds(d1)).doesNotContain(id);
        assertThat(openIds(d3)).doesNotContain(id);
        assertThat(offerStatus(id, d1Id)).isEqualTo("WITHDRAWN");
    }

    // ---------------------------------------------------------------- N2 / N5

    @Test
    void markViewedDoesNotResurrectAClosedOffer() throws Exception {
        long id = bookAt(S);
        RideOfferEntity stale = offerRepo.findByRideIdAndDriverId(id, d1Id).orElseThrow();
        assertThat(stale.getStatus()).isEqualTo(OfferStatus.OFFERED);
        call("POST", "/api/driver/rides/" + id + "/decline", d1, null, 200);
        offerService.markViewed(stale); // stale snapshot still says OFFERED
        assertThat(offerStatus(id, d1Id)).isEqualTo("DECLINED");
        // and the normal path still works
        RideOfferEntity fresh = offerRepo.findByRideIdAndDriverId(id, d2Id).orElseThrow();
        offerService.markViewed(fresh);
        assertThat(offerStatus(id, d2Id)).isEqualTo("VIEWED");
    }

    @Test
    void concurrentAcceptsGiveOneWinnerAndRideTakenForTheLoser() throws Exception {
        long id = bookAt(S.plus(Duration.ofDays(1)));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Future<Resp>> fs = new ArrayList<>();
        for (String t : new String[] {d1, d2}) {
            fs.add(pool.submit(() -> {
                barrier.await();
                return accept(t, id);
            }));
        }
        List<Resp> rs = new ArrayList<>();
        for (Future<Resp> f : fs) {
            rs.add(f.get());
        }
        pool.shutdown();
        assertThat(rs.stream().filter(r -> r.status() == 200).count()).isEqualTo(1);
        Resp loser = rs.stream().filter(r -> r.status() != 200).findFirst().orElseThrow();
        assertConflict(loser, "RIDE_TAKEN");
        assertThat(status(id)).isEqualTo("ACCEPTED");
    }

    // ---------------------------------------------------------------- N3

    @Test
    void goingAwayWithdrawsOpenOffersAndLeavesNoDriverWhenNobodyIsLeft() throws Exception {
        long id = bookAt(S.plus(Duration.ofDays(2)));
        declineAllExcept(id, d1);
        assertThat(status(id)).isEqualTo("REQUESTED");
        setAvailability(d1, true, "2027-08-12", "2027-08-13");
        assertThat(offerStatus(id, d1Id)).isEqualTo("WITHDRAWN");
        assertThat(status(id)).isEqualTo("NO_DRIVER");
        assertThat(timeline(id)).endsWith("NO_DRIVER");
        // an away period that does not cover the ride's date leaves the offer alone
        long other = bookAt(S.plus(Duration.ofDays(20)));
        setAvailability(d2, true, "2027-08-12", "2027-08-13");
        assertThat(offerStatus(other, d2Id)).isEqualTo("OFFERED");
    }

    @Test
    void returnAndMaterialEditSkipAwayDrivers() throws Exception {
        long id = bookAt(S.plus(Duration.ofDays(3)));
        acceptOk(d1, id);
        setAvailability(d2, true, "2027-08-13", "2027-08-13");
        call("POST", "/api/driver/rides/" + id + "/return", d1, null, 200);
        assertThat(openIds(d2)).doesNotContain(id);
        assertThat(openIds(d3)).contains(id);

        long id2 = bookAt(S.plus(Duration.ofDays(5)));
        acceptOk(d1, id2);
        setAvailability(d2, true, "2027-08-16", "2027-08-16");
        // move the ride two days later (material): d2 away that day, d1 (prior driver) gets the priority offer
        call("PATCH", "/api/rides/" + id2, p1, Map.of("scheduledAt", S.plus(Duration.ofDays(6)).toString()), 200);
        assertThat(openIds(d2)).doesNotContain(id2);
        assertThat(offerStatus(id2, d1Id)).isEqualTo("OFFERED");
        assertThat(openIds(d3)).contains(id2);
    }

    @Test
    void materialEditWhereThePriorDriverIsAwayWithdrawsHim() throws Exception {
        long id = bookAt(S.plus(Duration.ofDays(7)));
        acceptOk(d1, id);
        setAvailability(d1, true, "2027-08-18", "2027-08-18");
        call("PATCH", "/api/rides/" + id, p1, Map.of("scheduledAt", S.plus(Duration.ofDays(8)).toString()), 200);
        assertThat(offerStatus(id, d1Id)).isEqualTo("WITHDRAWN");
        assertThat(status(id)).isEqualTo("REQUESTED");
    }

    // ---------------------------------------------------------------- N4

    @Test
    void timeEditResetsUrgentAndReminderMarkers() throws Exception {
        long id = bookAt(BASE.plus(Duration.ofHours(3)));
        clock.set(BASE.plus(Duration.ofMinutes(130))); // T-50 min: 2h reminder due and urgent
        timers.tick();
        assertThat(ride(p1, id).get("urgent").asBoolean()).isTrue();
        assertThat(sentRepo.existsByRideIdAndKind(id, RideTimerService.URGENT)).isTrue();
        assertThat(sentRepo.existsByRideIdAndKind(id, RideTimerService.REMINDER_2H)).isTrue();

        JsonNode edited = call("PATCH", "/api/rides/" + id, p1,
            Map.of("scheduledAt", BASE.plus(Duration.ofHours(30)).toString()), 200).body();
        assertThat(edited.get("urgent").asBoolean()).isFalse();
        assertThat(sentRepo.existsByRideIdAndKind(id, RideTimerService.URGENT)).isFalse();
        assertThat(sentRepo.existsByRideIdAndKind(id, RideTimerService.REMINDER_2H)).isFalse();
        // the new time gets its own reminders
        clock.set(BASE.plus(Duration.ofHours(29)));
        timers.tick();
        assertThat(sentRepo.existsByRideIdAndKind(id, RideTimerService.REMINDER_2H)).isTrue();
    }

    @Test
    void driverReminderIsResetOnReturnSoTheNextDriverGetsIt() throws Exception {
        long id = bookAt(BASE.plus(Duration.ofHours(3)));
        acceptOk(d1, id);
        clock.set(BASE.plus(Duration.ofMinutes(150))); // T-30
        timers.tick();
        assertThat(sentRepo.existsByRideIdAndKind(id, RideTimerService.DRIVER_REMINDER_30M)).isTrue();
        call("POST", "/api/driver/rides/" + id + "/return", d1, null, 200);
        assertThat(sentRepo.existsByRideIdAndKind(id, RideTimerService.DRIVER_REMINDER_30M)).isFalse();
        acceptOk(d2, id);
        timers.tick();
        assertThat(sentRepo.existsByRideIdAndKind(id, RideTimerService.DRIVER_REMINDER_30M)).isTrue();
    }

    // ---------------------------------------------------------------- N9 / N11 / N16

    @Test
    void bookingWithoutKindAndATimeNearNowIsTreatedAsNow() throws Exception {
        Map<String, Object> m = rideBody(BASE.plus(Duration.ofMinutes(3)));
        m.remove("kind");
        JsonNode r = call("POST", "/api/rides", p1, m, 200).body();
        assertThat(r.get("kind").asText()).isEqualTo("NOW");
        Map<String, Object> m2 = rideBody(BASE.plus(Duration.ofHours(3)));
        m2.remove("kind");
        assertThat(call("POST", "/api/rides", p1, m2, 200).body().get("kind").asText()).isEqualTo("SCHEDULED");
    }

    @Test
    void keepWaitingOnNowRideFreshensTimeAndOldNoDriverRidesGoToHistory() throws Exception {
        long id = bookNow(p1);
        clock.set(BASE.plus(Duration.ofMinutes(21)));
        timers.tick();
        assertThat(status(id)).isEqualTo("NO_DRIVER");
        assertThat(upcomingIds(p1)).contains(id);
        // a day and a bit later it is history
        clock.set(BASE.plus(Duration.ofHours(26)));
        assertThat(upcomingIds(p1)).doesNotContain(id);
        assertThat(historyIds(p1)).contains(id);
        // keep waiting (clock: 26 h later) gives a fresh scheduledAt = now
        clock.set(BASE.plus(Duration.ofMinutes(22)));
        call("POST", "/api/rides/" + id + "/keep-waiting", p1, null, 200);
        Instant sched = Instant.parse(ride(p1, id).get("scheduledAt").asText());
        assertThat(sched).isEqualTo(clock.instant());
        assertThat(upcomingIds(p1)).contains(id);
    }

    private List<Long> ids(String path, String token) throws Exception {
        List<Long> out = new ArrayList<>();
        call("GET", path, token, null, 200).body().forEach(n -> out.add(n.get("id").asLong()));
        return out;
    }

    private List<Long> upcomingIds(String token) throws Exception {
        return ids("/api/rides/my", token);
    }

    private List<Long> historyIds(String token) throws Exception {
        return ids("/api/rides/my?history=true", token);
    }

    @Test
    void bookingWithNoEligibleDriversGoesStraightToNoDriver() throws Exception {
        for (String t : realDrivers) {
            setAvailability(t, false, "2027-09-01", "2027-09-01");
        }
        long scheduled = bookAt(at("2027-09-01", "10:00"));
        assertThat(status(scheduled)).isEqualTo("NO_DRIVER");
        assertThat(timeline(scheduled)).containsExactly("BOOKED", "NO_DRIVER");
        for (String t : realDrivers) {
            setAvailability(t, false, null, null);
        }
        long now = bookNow(p1);
        assertThat(status(now)).isEqualTo("NO_DRIVER");
        assertThat(timeline(now)).endsWith("NO_DRIVER");
    }

    // ---------------------------------------------------------------- N12 / N13 / N15

    @Test
    void newDriverDoesNotSeePreviousDriversMessages() throws Exception {
        long id = bookAt(S.plus(Duration.ofDays(9)));
        acceptOk(d1, id);
        call("POST", "/api/rides/" + id + "/messages", d1, Map.of("code", "DRIVER_LATE"), 200);
        call("POST", "/api/rides/" + id + "/messages", p1, Map.of("code", "PASSENGER_CALL_ME"), 200);
        call("POST", "/api/driver/rides/" + id + "/return", d1, null, 200);
        acceptOk(d2, id);
        call("POST", "/api/rides/" + id + "/messages", d2, Map.of("code", "DRIVER_HERE"), 200);
        for (String t : new String[] {d2, p1}) {
            JsonNode msgs = call("GET", "/api/rides/" + id + "/messages", t, null, 200).body();
            List<String> codes = new ArrayList<>();
            msgs.forEach(n -> codes.add(n.get("code").asText()));
            assertThat(codes).containsExactly("PASSENGER_CALL_ME", "DRIVER_HERE");
        }
    }

    @Test
    void offerPriorityIsExposedToTheDriverOwningThePriorityOffer() throws Exception {
        long id = bookAt(S.plus(Duration.ofDays(10)));
        acceptOk(d1, id);
        JsonNode edited = call("PATCH", "/api/rides/" + id, p1, Map.of("fromLat", 59.4000, "fromLon", 18.2000), 200).body();
        assertThat(edited.get("lastEditMaterial").asBoolean()).isTrue();
        assertThat(call("GET", "/api/rides/" + id, d1, null, 200).body().get("offerPriority").asBoolean()).isTrue();
        assertThat(call("GET", "/api/rides/" + id, d2, null, 200).body().get("offerPriority").asBoolean()).isFalse();
        assertThat(ride(p1, id).get("offerPriority").isNull()).isTrue();
    }

    @Test
    void adminCanDeleteADriverWithOffersAndMessages() throws Exception {
        String del = account("m1-del@test.local", true);
        long delId = idOf("m1-del@test.local");
        long id = bookAt(S.plus(Duration.ofDays(11)));
        long id2 = bookAt(S.plus(Duration.ofDays(12)));
        call("POST", "/api/driver/rides/" + id2 + "/decline", del, null, 200); // sets the refusal driver on the ride
        acceptOk(del, id);
        call("POST", "/api/rides/" + id + "/messages", del, Map.of("code", "DRIVER_HERE"), 200);
        call("POST", "/api/driver/rides/" + id + "/return", del, null, 200); // no longer assigned, offers + message remain
        assertThat(offerRepo.findByRideIdAndDriverId(id, delId)).isPresent();
        call("DELETE", "/api/admin/users/" + delId, adminToken, null, 200);
        assertThat(offerRepo.findByRideIdAndDriverId(id, delId)).isEmpty();
        assertThat(users.findById(delId)).isEmpty();
        // a driver who is still assigned to a ride cannot be deleted: friendly 409, not 500
        String busy = account("m1-busy@test.local", true);
        realDrivers.add(busy); // stays in the driver pool: later tests must decline for it too
        long busyId = idOf("m1-busy@test.local");
        long id3 = bookAt(S.plus(Duration.ofDays(13)));
        acceptOk(busy, id3);
        Resp r = call("DELETE", "/api/admin/users/" + busyId, adminToken, null, null);
        assertThat(r.status()).isEqualTo(409);
    }
}
