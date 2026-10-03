package com.farfartaxi.backend.service;

import com.farfartaxi.backend.api.dto.RideDtos.RideResponse;
import com.farfartaxi.backend.model.RideEntity;
import com.farfartaxi.backend.model.RideOfferEntity;
import com.farfartaxi.backend.model.RideStatus;
import com.farfartaxi.backend.model.Role;
import com.farfartaxi.backend.model.UserEntity;
import com.farfartaxi.backend.repo.RideFeedbackRepository;
import com.farfartaxi.backend.repo.UserRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Builds the per-viewer ride DTO: phones only for participants of an accepted ride,
 * and {@code availableActions} computed for exactly that caller.
 */
@Component
public class RideResponseFactory {
    private final RideOfferService offers;
    private final RideRealtimeService realtime;
    private final UserRepository users;
    private final RideFeedbackRepository feedback;
    private final java.time.Clock clock;

    public RideResponseFactory(RideOfferService offers, RideRealtimeService realtime, UserRepository users,
                               RideFeedbackRepository feedback, java.time.Clock clock) {
        this.clock = clock;
        this.offers = offers;
        this.realtime = realtime;
        this.users = users;
        this.feedback = feedback;
    }

    /** @param viewer null for anonymous share-link viewers */
    public RideResponse toResponse(RideEntity ride, UserEntity viewer, Boolean lastEditMaterial) {
        return toResponse(ride, viewer, lastEditMaterial, null);
    }

    /** Ids of those rides that already have feedback: one query for a whole list. */
    public Set<Long> feedbackRideIds(List<RideEntity> rides) {
        if (rides.isEmpty()) {
            return Set.of();
        }
        return new java.util.HashSet<>(feedback.findRideIdsWithFeedback(rides.stream().map(RideEntity::getId).toList()));
    }

    /** @param feedbackRideIds pre-loaded ids from {@link #feedbackRideIds} for list endpoints; null = look up singly */
    public RideResponse toResponse(RideEntity ride, UserEntity viewer, Boolean lastEditMaterial, Set<Long> feedbackRideIds) {
        UserEntity driver = ride.getAcceptedByDriver();
        boolean isPassenger = viewer != null && ride.getPassenger().getId().equals(viewer.getId());
        boolean isDriver = viewer != null && driver != null && driver.getId().equals(viewer.getId());
        boolean participantOfAccepted = (isPassenger || isDriver) && driver != null && RideStatus.ASSIGNED.contains(ride.getStatus());
        Optional<RideOfferEntity> myOffer = viewer == null || isPassenger
            ? Optional.empty() : offers.offerOf(ride.getId(), viewer.getId());
        boolean feedbackGiven = isPassenger && (feedbackRideIds != null
            ? feedbackRideIds.contains(ride.getId()) : feedback.findByRideId(ride.getId()).isPresent());
        return new RideResponse(
            ride.getId(),
            ride.getStatus().name(),
            ride.getFromAddress(), ride.getFromLat(), ride.getFromLon(),
            ride.getToAddress(), ride.getToLat(), ride.getToLon(),
            ride.getScheduledAt(),
            ride.getPassenger().getId(),
            driver == null ? null : driver.getId(),
            driver == null ? null : driver.getFullName(),
            ride.getEtaMinutes(),
            ride.getLastDriverLat(), ride.getLastDriverLon(), ride.getLastLocationAt(),
            ride.getKind().name(),
            viewer == null ? null : ride.getPickupNote(),
            ride.isUrgent(),
            viewer == null ? null : ride.getPassenger().getFullName(),
            participantOfAccepted && isDriver ? ride.getPassenger().getPhone() : null,
            participantOfAccepted && isPassenger ? driver.getPhone() : null,
            driver == null ? null : driver.getPhotoUrl(),
            driver == null ? null : driver.getVehicleNote(),
            ride.getArrivedAt(), ride.getPickedUpAt(),
            lastEditMaterial,
            myOffer.map(o -> o.getStatus().name()).orElse(null),
            myOffer.map(RideOfferEntity::isPriority).orElse(null),
            viewer == null ? List.of() : availableActions(ride, viewer, isPassenger, isDriver, myOffer),
            feedbackGiven,
            ride.getLastLocationAccuracyM(), ride.getEtaTarget(), isLocationStale(ride, clock.instant())
        );
    }

    /** Stale = the driver should be sending positions (EN_ROUTE..PICKED_UP) but the last one is missing or older than 2 min. */
    public static boolean isLocationStale(RideEntity ride, java.time.Instant now) {
        RideStatus st = ride.getStatus();
        if (st != RideStatus.EN_ROUTE && st != RideStatus.ARRIVED && st != RideStatus.PICKED_UP) {
            return false;
        }
        return ride.getLastLocationAt() == null
            || ride.getLastLocationAt().isBefore(now.minus(java.time.Duration.ofMinutes(2)));
    }

    private List<String> availableActions(RideEntity ride, UserEntity viewer, boolean isPassenger, boolean isDriver,
                                          Optional<RideOfferEntity> myOffer) {
        Set<String> a = new LinkedHashSet<>();
        RideStatus st = ride.getStatus();
        if (isPassenger) {
            switch (st) {
                case REQUESTED -> { a.add("CANCEL"); a.add("EDIT"); }
                case NO_DRIVER -> {
                    a.add("CANCEL");
                    a.add("EDIT");
                    if (offers.canKeepWaiting(ride)) {
                        a.add("KEEP_WAITING");
                    }
                }
                case ACCEPTED -> { a.add("CANCEL"); a.add("EDIT"); a.add("MESSAGE"); }
                case EN_ROUTE, ARRIVED -> { a.add("CANCEL_CONFIRM"); a.add("MESSAGE"); }
                case PICKED_UP -> a.add("MESSAGE");
                default -> { }
            }
        } else if (isDriver) {
            switch (st) {
                case ACCEPTED -> { a.add("START"); a.add("RETURN"); a.add("MESSAGE"); }
                case EN_ROUTE -> { a.add("ARRIVE"); a.add("RETURN"); a.add("MESSAGE"); }
                case ARRIVED -> { a.add("PICKUP"); a.add("MESSAGE"); }
                case PICKED_UP -> { a.add("COMPLETE"); a.add("MESSAGE"); }
                default -> { }
            }
        } else if (st == RideStatus.REQUESTED && viewer.getRole() != Role.USER
            && myOffer.map(o -> o.getStatus().isOpen()).orElse(false)) {
            a.add("ACCEPT");
            a.add("DECLINE");
        }
        return new ArrayList<>(a);
    }

    /** Pushes every participant's own view over SSE (passenger, assigned driver and any extra users, e.g. a driver just released). */
    public void publish(RideEntity ride, Long... alsoUserIds) {
        Set<Long> ids = new LinkedHashSet<>();
        ids.add(ride.getPassenger().getId());
        if (ride.getAcceptedByDriver() != null) {
            ids.add(ride.getAcceptedByDriver().getId());
        }
        for (Long id : alsoUserIds) {
            if (id != null) {
                ids.add(id);
            }
        }
        for (Long id : ids) {
            UserEntity viewer = ride.getPassenger().getId().equals(id) ? ride.getPassenger()
                : ride.getAcceptedByDriver() != null && ride.getAcceptedByDriver().getId().equals(id) ? ride.getAcceptedByDriver()
                : users.findById(id).orElse(null);
            if (viewer != null) {
                realtime.publishTo(ride.getId(), id, toResponse(ride, viewer, null));
            }
        }
    }
}
