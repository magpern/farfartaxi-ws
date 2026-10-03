package com.farfartaxi.backend.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.farfartaxi.backend.model.RideEntity;
import com.farfartaxi.backend.model.RideStatus;
import com.farfartaxi.backend.service.AppException;
import com.farfartaxi.backend.service.RideStateMachine;
import com.farfartaxi.backend.service.RideStateMachine.Actor;
import java.util.List;
import org.junit.jupiter.api.Test;

class RideStateMachineTest {
    private record T(RideStatus from, RideStatus to, Actor actor) {
    }

    /** The complete set of legal transitions (contract "Statuses"); anything else must be rejected. */
    private static final List<T> LEGAL = List.of(
        new T(RideStatus.REQUESTED, RideStatus.ACCEPTED, Actor.DRIVER),
        new T(RideStatus.REQUESTED, RideStatus.NO_DRIVER, Actor.SYSTEM),
        new T(RideStatus.REQUESTED, RideStatus.CANCELLED, Actor.PASSENGER),
        new T(RideStatus.ACCEPTED, RideStatus.EN_ROUTE, Actor.DRIVER),
        new T(RideStatus.ACCEPTED, RideStatus.REQUESTED, Actor.DRIVER),
        new T(RideStatus.ACCEPTED, RideStatus.REQUESTED, Actor.PASSENGER),
        new T(RideStatus.ACCEPTED, RideStatus.CANCELLED, Actor.PASSENGER),
        new T(RideStatus.EN_ROUTE, RideStatus.ARRIVED, Actor.DRIVER),
        new T(RideStatus.EN_ROUTE, RideStatus.REQUESTED, Actor.DRIVER),
        new T(RideStatus.EN_ROUTE, RideStatus.CANCELLED, Actor.PASSENGER),
        new T(RideStatus.ARRIVED, RideStatus.PICKED_UP, Actor.DRIVER),
        new T(RideStatus.ARRIVED, RideStatus.CANCELLED, Actor.PASSENGER),
        new T(RideStatus.PICKED_UP, RideStatus.COMPLETED, Actor.DRIVER),
        new T(RideStatus.NO_DRIVER, RideStatus.REQUESTED, Actor.PASSENGER),
        new T(RideStatus.NO_DRIVER, RideStatus.CANCELLED, Actor.PASSENGER));

    private final RideStateMachine machine = new RideStateMachine();

    @Test
    void onlyTheDocumentedTransitionsAreAllowedForTheDocumentedActors() {
        for (RideStatus from : RideStatus.values()) {
            for (RideStatus to : RideStatus.values()) {
                for (Actor actor : Actor.values()) {
                    boolean expected = LEGAL.contains(new T(from, to, actor));
                    assertThat(machine.allowed(from, to, actor)).as(from + " -> " + to + " by " + actor).isEqualTo(expected);
                }
            }
        }
    }

    @Test
    void illegalTransitionThrowsInvalidTransitionAndLeavesStatusAlone() {
        RideEntity ride = new RideEntity();
        ride.setStatus(RideStatus.PICKED_UP);
        assertThatThrownBy(() -> machine.transition(ride, RideStatus.CANCELLED, Actor.PASSENGER))
            .isInstanceOfSatisfying(AppException.class, e -> {
                assertThat(e.getStatus().value()).isEqualTo(409);
                assertThat(e.getCode()).isEqualTo("INVALID_TRANSITION");
            });
        assertThat(ride.getStatus()).isEqualTo(RideStatus.PICKED_UP);
        machine.transition(ride, RideStatus.COMPLETED, Actor.DRIVER);
        assertThat(ride.getStatus()).isEqualTo(RideStatus.COMPLETED);
    }

    @Test
    void terminalStatesHaveNoWayOut() {
        for (RideStatus to : RideStatus.values()) {
            for (Actor a : Actor.values()) {
                assertThat(machine.allowed(RideStatus.COMPLETED, to, a)).isFalse();
                assertThat(machine.allowed(RideStatus.CANCELLED, to, a)).isFalse();
            }
        }
    }
}
