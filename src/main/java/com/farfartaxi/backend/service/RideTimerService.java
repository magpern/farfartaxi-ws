package com.farfartaxi.backend.service;

import com.farfartaxi.backend.model.RideEntity;
import com.farfartaxi.backend.model.RideKind;
import com.farfartaxi.backend.model.RideNotificationSentEntity;
import com.farfartaxi.backend.model.RideStatus;
import com.farfartaxi.backend.repo.RideNotificationSentRepository;
import com.farfartaxi.backend.repo.RideRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Time-driven ride lifecycle: NOW re-push/timeout, SCHEDULED reminders, urgent flag, NO_DRIVER at the scheduled time.
 * All time comes from the injectable {@link Clock}; every push is recorded once-only in ride_notifications_sent.
 */
@Service
public class RideTimerService {
    private static final Logger log = LoggerFactory.getLogger(RideTimerService.class);
    public static final String REMINDER_24H = "REMINDER_24H";
    public static final String REMINDER_2H = "REMINDER_2H";
    public static final String URGENT = "URGENT";
    public static final String DRIVER_REMINDER_30M = "DRIVER_REMINDER_30M";

    private final RideRepository rides;
    private final RideNotificationSentRepository sent;
    private final RideOfferService offers;
    private final RideSystemTransitions transitions;
    private final PushService push;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Duration nowRepush;
    private final Duration nowTimeout;
    private final boolean enabled;

    public RideTimerService(RideRepository rides, RideNotificationSentRepository sent, RideOfferService offers,
                            RideSystemTransitions transitions, PushService push, TransactionTemplate tx, Clock clock,
                            @Value("${app.rides.now-repush-minutes:10}") long nowRepushMinutes,
                            @Value("${app.rides.now-timeout-minutes:20}") long nowTimeoutMinutes,
                            @Value("${app.rides.scheduler-enabled:true}") boolean enabled) {
        this.rides = rides;
        this.sent = sent;
        this.offers = offers;
        this.transitions = transitions;
        this.push = push;
        this.tx = tx;
        this.clock = clock;
        this.nowRepush = Duration.ofMinutes(nowRepushMinutes);
        this.nowTimeout = Duration.ofMinutes(nowTimeoutMinutes);
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** One scheduler pass. Each ride is handled in its own transaction so one failure cannot block the others. */
    public void tick() {
        Instant now = clock.instant();
        for (RideEntity r : rides.findByStatus(RideStatus.REQUESTED)) {
            runIsolated(r.getId(), () -> handleRequested(r.getId(), now));
        }
        for (RideEntity r : rides.findByStatus(RideStatus.ACCEPTED)) {
            runIsolated(r.getId(), () -> handleAccepted(r.getId(), now));
        }
    }

    private void runIsolated(Long rideId, Runnable work) {
        try {
            tx.executeWithoutResult(s -> work.run());
        } catch (RuntimeException e) {
            log.warn("Ride timer failed for ride {}: {}", rideId, e.toString());
        }
    }

    private void handleRequested(Long rideId, Instant now) {
        RideEntity ride = rides.findById(rideId).orElse(null);
        if (ride == null || ride.getStatus() != RideStatus.REQUESTED) {
            return;
        }
        if (ride.getKind() == RideKind.NOW) {
            Instant start = ride.getRequestedAt();
            if (!now.isBefore(start.plus(nowTimeout))) {
                transitions.toNoDriver(ride, "NOW timeout");
            } else if (!now.isBefore(start.plus(nowRepush)) && markSent(rideId, RideService.NOW_REPUSH, now)) {
                offers.repushNow(ride);
            }
            return;
        }
        Instant at = ride.getScheduledAt();
        if (!now.isBefore(at)) {
            transitions.toNoDriver(ride, "scheduled time reached");
            return;
        }
        if (!now.isBefore(at.minus(Duration.ofMinutes(60)))) {
            if (!ride.isUrgent()) {
                ride.setUrgent(true);
                rides.save(ride);
            }
            if (markSent(rideId, URGENT, now)) {
                offers.pushToOpen(ride, "Brådskande resa", "En resa startar snart och saknar förare.");
            }
        }
        remindIfDue(ride, now, Duration.ofHours(24), REMINDER_24H, "Resa om 24 timmar", "En resa saknar fortfarande förare.");
        remindIfDue(ride, now, Duration.ofHours(2), REMINDER_2H, "Resa om 2 timmar", "En resa saknar fortfarande förare.");
    }

    /** Only when the ride was booked before the reminder moment (otherwise the booking offer was the reminder). */
    private void remindIfDue(RideEntity ride, Instant now, Duration before, String kind, String title, String body) {
        Instant due = ride.getScheduledAt().minus(before);
        if (!now.isBefore(due) && ride.getRequestedAt().isBefore(due) && markSent(ride.getId(), kind, now)) {
            offers.pushToOpen(ride, title, body);
        }
    }

    private void handleAccepted(Long rideId, Instant now) {
        RideEntity ride = rides.findById(rideId).orElse(null);
        if (ride == null || ride.getStatus() != RideStatus.ACCEPTED || ride.getKind() != RideKind.SCHEDULED
            || ride.getAcceptedByDriver() == null) {
            return;
        }
        Instant at = ride.getScheduledAt();
        if (!now.isBefore(at.minus(Duration.ofMinutes(30))) && now.isBefore(at) && markSent(rideId, DRIVER_REMINDER_30M, now)) {
            push.notifyUser(ride.getAcceptedByDriver().getId(), "Resa om 30 minuter",
                "Hämta " + ride.getPassenger().getFullName() + " kl " + at.atZone(Geo.STOCKHOLM).toLocalTime().withSecond(0).withNano(0));
        }
    }

    /** @return true when this call recorded the notification (i.e. it had not been sent before) */
    private boolean markSent(Long rideId, String kind, Instant now) {
        if (sent.existsByRideIdAndKind(rideId, kind)) {
            return false;
        }
        RideNotificationSentEntity n = new RideNotificationSentEntity();
        n.setRideId(rideId);
        n.setKind(kind);
        n.setSentAt(now);
        sent.save(n);
        return true;
    }
}
