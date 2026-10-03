package com.farfartaxi.backend.service;

import com.farfartaxi.backend.model.RideEntity;
import com.farfartaxi.backend.model.RideStatus;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The single place that knows which ride status changes are legal and who may make them.
 * Concurrency is handled by {@code RideEntity @Version} (a losing concurrent transition fails on flush).
 */
@Component
public class RideStateMachine {
    /** Who triggers a transition. Admins acting as drivers are DRIVER. */
    public enum Actor { DRIVER, PASSENGER, SYSTEM }

    private static final Map<RideStatus, Map<RideStatus, Set<Actor>>> ALLOWED = new EnumMap<>(RideStatus.class);

    private static void allow(RideStatus from, RideStatus to, Actor... actors) {
        ALLOWED.computeIfAbsent(from, k -> new EnumMap<>(RideStatus.class)).put(to, EnumSet.copyOf(java.util.List.of(actors)));
    }

    static {
        allow(RideStatus.REQUESTED, RideStatus.ACCEPTED, Actor.DRIVER);
        allow(RideStatus.REQUESTED, RideStatus.NO_DRIVER, Actor.SYSTEM);
        allow(RideStatus.REQUESTED, RideStatus.CANCELLED, Actor.PASSENGER);

        allow(RideStatus.ACCEPTED, RideStatus.EN_ROUTE, Actor.DRIVER);
        allow(RideStatus.ACCEPTED, RideStatus.REQUESTED, Actor.DRIVER, Actor.PASSENGER); // return / material edit
        allow(RideStatus.ACCEPTED, RideStatus.CANCELLED, Actor.PASSENGER);

        allow(RideStatus.EN_ROUTE, RideStatus.ARRIVED, Actor.DRIVER);
        allow(RideStatus.EN_ROUTE, RideStatus.REQUESTED, Actor.DRIVER); // return (reason required)
        allow(RideStatus.EN_ROUTE, RideStatus.CANCELLED, Actor.PASSENGER);

        allow(RideStatus.ARRIVED, RideStatus.PICKED_UP, Actor.DRIVER);
        allow(RideStatus.ARRIVED, RideStatus.CANCELLED, Actor.PASSENGER);

        allow(RideStatus.PICKED_UP, RideStatus.COMPLETED, Actor.DRIVER);

        allow(RideStatus.NO_DRIVER, RideStatus.REQUESTED, Actor.PASSENGER); // keep waiting / edit time
        allow(RideStatus.NO_DRIVER, RideStatus.CANCELLED, Actor.PASSENGER);
    }

    public boolean allowed(RideStatus from, RideStatus to, Actor actor) {
        Map<RideStatus, Set<Actor>> targets = ALLOWED.get(from);
        return targets != null && targets.containsKey(to) && targets.get(to).contains(actor);
    }

    /** Throws 409 INVALID_TRANSITION when {@code actor} may not move the ride to {@code to}. */
    public void require(RideEntity ride, RideStatus to, Actor actor) {
        if (!allowed(ride.getStatus(), to, actor)) {
            throw AppException.conflict("INVALID_TRANSITION",
                "Resan kan inte gå från " + ride.getStatus() + " till " + to + " (" + actor + ")");
        }
    }

    public void transition(RideEntity ride, RideStatus to, Actor actor) {
        require(ride, to, actor);
        ride.setStatus(to);
    }
}
