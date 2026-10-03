package com.farfartaxi.backend.service;

import com.farfartaxi.backend.model.RideEntity;
import com.farfartaxi.backend.model.RideStatus;
import com.farfartaxi.backend.repo.RideRepository;
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

    public RideSystemTransitions(RideStateMachine machine, RideOfferService offers, RideRepository rides,
                                 RideEventRecorder events, PushService push, RideResponseFactory responses) {
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
        push.notifyUser(saved.getPassenger().getId(), "Ingen förare har tackat ja än",
            "Fortsätt vänta, ring eller avboka.");
        responses.publish(saved);
    }
}
