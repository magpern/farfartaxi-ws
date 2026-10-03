package com.farfartaxi.backend.service;

import com.farfartaxi.backend.model.RideEntity;
import com.farfartaxi.backend.model.RideStatus;
import com.farfartaxi.backend.repo.RideRepository;
import com.farfartaxi.backend.observability.AppMetrics;
import org.springframework.stereotype.Component;

/** Transitions triggered by the system (timers, last offer declined) rather than by a user. */
@Component
public class RideSystemTransitions {
    private final RideStateMachine machine;
    private final RideOfferService offers;
    private final RideRepository rides;
    private final RideEventRecorder events;
    private final PushService push;
    private final RideResponseFactory responses;
    private final AppMetrics metrics;

    public RideSystemTransitions(RideStateMachine machine, RideOfferService offers, RideRepository rides,
                                 RideEventRecorder events, PushService push, RideResponseFactory responses, AppMetrics metrics) {
        this.metrics = metrics;
        this.machine = machine;
        this.offers = offers;
        this.rides = rides;
        this.events = events;
        this.push = push;
        this.responses = responses;
    }

    /** REQUESTED to NO_DRIVER: open offers EXPIRED, the passenger is told to call or keep waiting. */
    public void toNoDriver(RideEntity ride, String reason) {
        machine.transition(ride, RideStatus.NO_DRIVER, RideStateMachine.Actor.SYSTEM);
        ride.setUrgent(false);
        offers.expireOpen(ride);
        RideEntity saved = rides.save(ride);
        events.record(saved, null, RideEventRecorder.NO_DRIVER, reason);
        metrics.rideNoDriver(saved.isTest());
        push.send(saved.getPassenger().getId(), PushCategory.RIDE_UPDATES, "NO_DRIVER", saved.getId(),
            "/app/resa/" + saved.getId(), "ride.no_driver", java.util.List.of());
        responses.publish(saved);
    }
}
