package com.farfartaxi.backend.service;

import com.farfartaxi.backend.api.dto.RideDtos.ActiveRideResponse;
import com.farfartaxi.backend.api.dto.RideDtos.BookRideRequest;
import com.farfartaxi.backend.api.dto.RideDtos.DriverStatsResponse;
import com.farfartaxi.backend.api.dto.RideDtos.EditRideRequest;
import com.farfartaxi.backend.api.dto.RideDtos.LocationUpdateRequest;
import com.farfartaxi.backend.api.dto.RideDtos.RideResponse;
import com.farfartaxi.backend.api.dto.RideDtos.SubmitFeedbackRequest;
import com.farfartaxi.backend.model.OfferStatus;
import com.farfartaxi.backend.model.RideEntity;
import com.farfartaxi.backend.model.RideKind;
import com.farfartaxi.backend.model.RideFeedbackEntity;
import com.farfartaxi.backend.model.RideKind;
import com.farfartaxi.backend.model.RideOfferEntity;
import com.farfartaxi.backend.model.RideStatus;
import com.farfartaxi.backend.model.Role;
import com.farfartaxi.backend.model.UserEntity;
import com.farfartaxi.backend.repo.RideFeedbackRepository;
import com.farfartaxi.backend.repo.RideMessageRepository;
import com.farfartaxi.backend.repo.RideNotificationSentRepository;
import com.farfartaxi.backend.repo.RideOfferRepository;
import com.farfartaxi.backend.repo.RideRepository;
import com.farfartaxi.backend.repo.UserRepository;
import com.farfartaxi.backend.service.RideStateMachine.Actor;
import jakarta.transaction.Transactional;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class RideService {
    public static final String NOW_REPUSH = "NOW_REPUSH";
    private static final int ETA_PUSH_MINUTES = 5;
    private static final Duration LEGACY_NOW_WINDOW = Duration.ofMinutes(10);
    private static final Duration NO_DRIVER_UPCOMING_GRACE = Duration.ofHours(24);
    private static final List<String> REMINDER_KINDS = List.of(RideTimerService.REMINDER_24H, RideTimerService.REMINDER_2H,
        RideTimerService.URGENT, RideTimerService.DRIVER_REMINDER_30M, NOW_REPUSH);
    private static final double MATERIAL_PICKUP_METERS = 500;
    private static final Duration MATERIAL_TIME_SHIFT = Duration.ofMinutes(30);
    private static final double MATERIAL_ROUTE_RATIO = 1.25;
    private static final double MATERIAL_ROUTE_EXTRA_METERS = 5000;

    private final RideRepository rideRepository;
    private final RideFeedbackRepository rideFeedbackRepository;
    private final UserRepository userRepository;
    private final CurrentUserService currentUserService;
    private final PushService pushService;
    private final com.farfartaxi.backend.repo.RideEventRepository eventRepository;
    private final RideOfferRepository offerRepository;
    private final RideMessageRepository messageRepository;
    private final RideNotificationSentRepository notificationRepository;
    private final NotificationMarker notificationMarker;
    private final RideAccessPolicy policy;
    private final RideEventRecorder events;
    private final RideStateMachine machine;
    private final RideOfferService offers;
    private final RideConflictChecker conflictChecker;
    private final RouteDistanceService routes;
    private final RideSystemTransitions systemTransitions;
    private final RideResponseFactory responses;
    private final org.springframework.transaction.support.TransactionTemplate freshTx;
    private final Clock clock;
    private final double defaultEtaKmh;

    public RideService(
        RideRepository rideRepository,
        RideFeedbackRepository rideFeedbackRepository,
        UserRepository userRepository,
        CurrentUserService currentUserService,
        PushService pushService,
        RideAccessPolicy policy,
        RideEventRecorder events,
        com.farfartaxi.backend.repo.RideEventRepository eventRepository,
        RideOfferRepository offerRepository,
        RideMessageRepository messageRepository,
        RideNotificationSentRepository notificationRepository,
        NotificationMarker notificationMarker,
        RideStateMachine machine,
        RideOfferService offers,
        RideConflictChecker conflictChecker,
        RouteDistanceService routes,
        RideSystemTransitions systemTransitions,
        RideResponseFactory responses,
        org.springframework.transaction.support.TransactionTemplate txTemplate,
        Clock clock,
        @Value("${app.eta.default-kmh}") double defaultEtaKmh
    ) {
        this.rideRepository = rideRepository;
        this.rideFeedbackRepository = rideFeedbackRepository;
        this.userRepository = userRepository;
        this.currentUserService = currentUserService;
        this.pushService = pushService;
        this.policy = policy;
        this.events = events;
        this.eventRepository = eventRepository;
        this.offerRepository = offerRepository;
        this.messageRepository = messageRepository;
        this.notificationRepository = notificationRepository;
        this.notificationMarker = notificationMarker;
        this.machine = machine;
        this.offers = offers;
        this.conflictChecker = conflictChecker;
        this.routes = routes;
        this.systemTransitions = systemTransitions;
        this.responses = responses;
        this.freshTx = new org.springframework.transaction.support.TransactionTemplate(txTemplate.getTransactionManager());
        this.freshTx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.freshTx.setReadOnly(true);
        this.clock = clock;
        this.defaultEtaKmh = defaultEtaKmh;
    }

    // ------------------------------------------------------------------ booking

    @Transactional
    public RideResponse book(BookRideRequest request, String idempotencyKey) {
        UserEntity actor = currentUserService.requireUser();
        UserEntity passenger = resolvePassenger(actor, request.passengerUserId());
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim();
        if (key != null && key.length() > 64) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Idempotency-Key must be at most 64 characters");
        }
        if (key != null) {
            Optional<RideEntity> existing = rideRepository.findByPassengerIdAndClientRequestId(passenger.getId(), key);
            if (existing.isPresent()) {
                return responses.toResponse(existing.get(), actor, null);
            }
        }
        Instant now = clock.instant();
        // Old cached clients send no kind: a time within 10 minutes of now is their "Åk nu".
        RideKind kind = request.kind() != null ? request.kind()
            : request.scheduledAt() == null
                || Duration.between(now, request.scheduledAt()).abs().compareTo(LEGACY_NOW_WINDOW) <= 0
                ? RideKind.NOW : RideKind.SCHEDULED;
        Instant when;
        if (kind == RideKind.NOW) {
            when = now;
        } else {
            when = request.scheduledAt();
            if (when == null || !when.isAfter(now)) {
                throw new AppException(HttpStatus.BAD_REQUEST, "scheduledAt: must be a future date");
            }
        }
        RideEntity ride = new RideEntity();
        ride.setPassenger(passenger);
        ride.setFromAddress(request.fromAddress());
        ride.setFromLat(request.fromLat());
        ride.setFromLon(request.fromLon());
        ride.setToAddress(request.toAddress());
        ride.setToLat(request.toLat());
        ride.setToLon(request.toLon());
        ride.setWaypointsJson(request.waypointsJson());
        ride.setKind(kind);
        ride.setScheduledAt(when);
        ride.setPickupNote(blankToNull(request.pickupNote()));
        ride.setClientRequestId(key);
        ride.setRequestedAt(now);
        ride.setStatus(RideStatus.REQUESTED);
        ride.setTest(passenger.isTest());
        ride = rideRepository.save(ride);
        events.record(ride, actor.getId(), RideEventRecorder.BOOKED, passenger.getId().equals(actor.getId()) ? null : "on behalf of user " + passenger.getId());
        offers.createInitialOffers(ride);
        if (offers.openOffers(ride.getId()).isEmpty()) {
            systemTransitions.toNoDriver(ride, "no eligible drivers");
        }
        return responses.toResponse(ride, actor, null);
    }

    /** Used by the controller to resolve an idempotent replay that lost a unique-constraint race. */
    public Optional<RideResponse> findByIdempotencyKey(Long passengerId, String key) {
        UserEntity actor = currentUserService.requireUser();
        return rideRepository.findByPassengerIdAndClientRequestId(passengerId, key.trim())
            .map(r -> responses.toResponse(r, actor, null));
    }

    public List<RideResponse> listMine(boolean history) {
        UserEntity user = currentUserService.requireUser();
        Instant now = clock.instant();
        // upcoming = still in play or scheduled in the future; history = in the past and over
        List<RideEntity> rows = rideRepository.findByPassengerIdAndTestOrderByScheduledAtAsc(user.getId(), policy.world(user)).stream()
            .filter(r -> {
                boolean staleNoDriver = r.getStatus() == RideStatus.NO_DRIVER
                    && !r.getScheduledAt().isAfter(now.minus(NO_DRIVER_UPCOMING_GRACE));
                boolean upcoming = !staleNoDriver && (RideStatus.ACTIVE.contains(r.getStatus()) || r.getScheduledAt().isAfter(now));
                return history != upcoming;
            })
            .sorted(history ? java.util.Comparator.comparing(RideEntity::getScheduledAt).reversed()
                : java.util.Comparator.comparing(RideEntity::getScheduledAt))
            .toList();
        java.util.Set<Long> fb = responses.feedbackRideIds(rows);
        return rows.stream().map(r -> responses.toResponse(r, user, null, fb)).toList();
    }

    // ------------------------------------------------------------------ passenger actions

    @Transactional
    public RideResponse cancelRide(Long rideId, String reason, boolean confirm) {
        UserEntity user = currentUserService.requireUser();
        RideEntity ride = mustFindRide(rideId, user);
        requirePassenger(ride, user);
        RideStatus st = ride.getStatus();
        if (st == RideStatus.CANCELLED) {
            return responses.toResponse(ride, user, null);
        }
        if ((st == RideStatus.EN_ROUTE || st == RideStatus.ARRIVED) && !confirm) {
            throw AppException.conflict("CONFIRM_REQUIRED", "Föraren är på väg — bekräfta avbokningen");
        }
        machine.transition(ride, RideStatus.CANCELLED, Actor.PASSENGER);
        Long driverId = ride.getAcceptedByDriver() != null ? ride.getAcceptedByDriver().getId() : null;
        if (st == RideStatus.REQUESTED) {
            offers.pushToOpen(ride, PushCategory.RIDE_REQUESTS, "RIDE_CANCELLED", "ride.cancelled",
                java.util.List.of(PushArgs.firstName(ride.getPassenger().getFullName())));
        }
        offers.withdrawAll(ride);
        ride.setCancelReason(reason);
        ride.setUrgent(false);
        ride = rideRepository.save(ride);
        events.record(ride, user.getId(), RideEventRecorder.CANCELLED, reason);
        if (driverId != null) {
            pushService.send(driverId, PushCategory.RIDE_UPDATES, "RIDE_CANCELLED", rideId, "/app/forare", "ride.cancelled",
                java.util.List.of(PushArgs.firstName(ride.getPassenger().getFullName())));
        }
        responses.publish(ride);
        return responses.toResponse(ride, user, null);
    }

    @Transactional
    public RideResponse keepWaiting(Long rideId) {
        UserEntity user = currentUserService.requireUser();
        RideEntity ride = mustFindRide(rideId, user);
        requirePassenger(ride, user);
        if (ride.getStatus() != RideStatus.NO_DRIVER) {
            throw AppException.conflict("INVALID_TRANSITION", "Det går bara att fortsätta vänta när ingen förare tackat ja");
        }
        if (ride.getKind() == RideKind.SCHEDULED && !ride.getScheduledAt().isAfter(clock.instant())) {
            throw AppException.conflict("INVALID_TRANSITION", "Tiden har passerat — ändra tiden för att söka förare igen");
        }
        if (!offers.canKeepWaiting(ride)) {
            throw AppException.conflict("ALL_DECLINED", "Alla förare har tackat nej");
        }
        machine.transition(ride, RideStatus.REQUESTED, Actor.PASSENGER);
        if (ride.getKind() == RideKind.NOW) {
            ride.setScheduledAt(clock.instant()); // fresh window, not a stale booking time
        }
        resetWaitWindow(ride);
        offers.keepWaiting(ride);
        ride = rideRepository.save(ride);
        events.record(ride, user.getId(), RideEventRecorder.KEPT_WAITING);
        responses.publish(ride);
        return responses.toResponse(ride, user, null);
    }

    @Transactional
    public RideResponse editRide(Long rideId, EditRideRequest req) {
        UserEntity user = currentUserService.requireUser();
        RideEntity ride = mustFindRide(rideId, user);
        requirePassenger(ride, user);
        RideStatus st = ride.getStatus();
        if (st != RideStatus.REQUESTED && st != RideStatus.NO_DRIVER && st != RideStatus.ACCEPTED) {
            throw AppException.conflict("EDIT_NOT_ALLOWED", "Resan kan inte längre ändras i appen — ring eller skriv till föraren");
        }
        validateEdit(req);
        Instant now = clock.instant();
        double oldFromLat = ride.getFromLat(), oldFromLon = ride.getFromLon();
        double oldToLat = ride.getToLat(), oldToLon = ride.getToLon();
        Instant oldTime = ride.getScheduledAt();

        if (req.fromAddress() != null) ride.setFromAddress(req.fromAddress());
        if (req.fromLat() != null) ride.setFromLat(req.fromLat());
        if (req.fromLon() != null) ride.setFromLon(req.fromLon());
        if (req.toAddress() != null) ride.setToAddress(req.toAddress());
        if (req.toLat() != null) ride.setToLat(req.toLat());
        if (req.toLon() != null) ride.setToLon(req.toLon());
        if (req.pickupNote() != null) ride.setPickupNote(blankToNull(req.pickupNote()));
        boolean timeChanged = false;
        if (req.scheduledAt() != null) {
            if (!req.scheduledAt().isAfter(now)) {
                throw new AppException(HttpStatus.BAD_REQUEST, "scheduledAt: must be a future date");
            }
            timeChanged = !req.scheduledAt().equals(oldTime);
            if (timeChanged) {
                // reminders / urgent belong to the old time
                ride.setUrgent(false);
                notificationRepository.deleteByRideIdAndKindIn(ride.getId(), REMINDER_KINDS);
            }
            ride.setScheduledAt(req.scheduledAt());
            ride.setKind(RideKind.SCHEDULED); // choosing a time turns a NOW ride into a scheduled one
        }

        boolean material = false;
        switch (st) {
            case REQUESTED -> {
                if (timeChanged) {
                    offers.syncForTimeChange(ride, false);
                } else {
                    offers.pushToOpen(ride, PushCategory.RIDE_REQUESTS, "RIDE_EDITED", "ride.edited_open", PushArgs.ride(ride));
                }
            }
            case NO_DRIVER -> {
                if (timeChanged) {
                    machine.transition(ride, RideStatus.REQUESTED, Actor.PASSENGER);
                    resetWaitWindow(ride);
                    offers.syncForTimeChange(ride, true);
                }
            }
            case ACCEPTED -> {
                material = isMaterial(ride, oldFromLat, oldFromLon, oldToLat, oldToLon, oldTime);
                UserEntity driver = ride.getAcceptedByDriver();
                if (material) {
                    machine.transition(ride, RideStatus.REQUESTED, Actor.PASSENGER);
                    ride.setAcceptedByDriver(null);
                    ride.setUrgent(false);
                    resetWaitWindow(ride);
                    notificationRepository.deleteByRideIdAndKind(ride.getId(), RideTimerService.DRIVER_REMINDER_30M);
                    offers.reofferAfterMaterialEdit(ride, driver.getId(), ride.getPassenger().getFullName());
                } else {
                    pushService.send(driver.getId(), PushCategory.RIDE_UPDATES, "RIDE_EDITED", ride.getId(),
                        "/app/forare/kor/" + ride.getId(), "ride.edited_minor",
                        java.util.List.of(PushArgs.firstName(ride.getPassenger().getFullName())));
                }
            }
            default -> { }
        }
        ride = rideRepository.save(ride);
        events.record(ride, user.getId(), RideEventRecorder.EDITED, material ? "material" : "minor");
        if (ride.getStatus() == RideStatus.REQUESTED && offers.openOffers(ride.getId()).isEmpty()) {
            systemTransitions.toNoDriver(ride, "no eligible drivers after edit");
        }
        responses.publish(ride, st == RideStatus.ACCEPTED && material ? offerDriverIds(ride) : new Long[0]);
        return responses.toResponse(ride, user, material);
    }

    private Long[] offerDriverIds(RideEntity ride) {
        return offers.openOffers(ride.getId()).stream().map(RideOfferEntity::getDriverId).toArray(Long[]::new);
    }

    private boolean isMaterial(RideEntity ride, double oldFromLat, double oldFromLon, double oldToLat, double oldToLon, Instant oldTime) {
        if (Geo.haversineMeters(oldFromLat, oldFromLon, ride.getFromLat(), ride.getFromLon()) > MATERIAL_PICKUP_METERS) {
            return true;
        }
        if (Duration.between(oldTime, ride.getScheduledAt()).abs().compareTo(MATERIAL_TIME_SHIFT) > 0) {
            return true;
        }
        boolean routeChanged = oldFromLat != ride.getFromLat() || oldFromLon != ride.getFromLon()
            || oldToLat != ride.getToLat() || oldToLon != ride.getToLon();
        if (!routeChanged) {
            return false;
        }
        double before = routes.distanceMeters(oldFromLat, oldFromLon, oldToLat, oldToLon);
        double after = routes.distanceMeters(ride.getFromLat(), ride.getFromLon(), ride.getToLat(), ride.getToLon());
        return after - before > MATERIAL_ROUTE_EXTRA_METERS || after > before * MATERIAL_ROUTE_RATIO;
    }

    private void validateEdit(EditRideRequest r) {
        if (r.fromAddress() != null && r.fromAddress().isBlank()) throw bad("fromAddress: must not be blank");
        if (r.toAddress() != null && r.toAddress().isBlank()) throw bad("toAddress: must not be blank");
        if (badLat(r.fromLat()) || badLat(r.toLat())) throw bad("latitude out of range");
        if (badLon(r.fromLon()) || badLon(r.toLon())) throw bad("longitude out of range");
    }

    private static boolean badLat(Double v) {
        return v != null && (!Double.isFinite(v) || v < -90 || v > 90);
    }

    private static boolean badLon(Double v) {
        return v != null && (!Double.isFinite(v) || v < -180 || v > 180);
    }

    private static AppException bad(String msg) {
        return new AppException(HttpStatus.BAD_REQUEST, msg);
    }

    /** A fresh waiting window: NOW timeouts count from here and the 10-min re-push may fire again. */
    private void resetWaitWindow(RideEntity ride) {
        if (ride.getKind() == RideKind.NOW) {
            ride.setRequestedAt(clock.instant());
            notificationRepository.deleteByRideIdAndKind(ride.getId(), NOW_REPUSH);
        }
    }

    // ------------------------------------------------------------------ driver: offers

    @Transactional
    public List<RideResponse> listOpenForDrivers() {
        UserEntity driver = currentUserService.requireUser();
        requireRole(driver, Role.DRIVER);
        List<RideOfferEntity> open = offerRepository.findByDriverIdAndStatusIn(driver.getId(), List.of(OfferStatus.OFFERED, OfferStatus.VIEWED));
        Map<Long, RideOfferEntity> byRide = open.stream().collect(java.util.stream.Collectors.toMap(RideOfferEntity::getRideId, o -> o));
        List<RideEntity> rides = rideRepository.findAllById(byRide.keySet()).stream()
            .filter(r -> r.getStatus() == RideStatus.REQUESTED && r.isTest() == policy.world(driver))
            .sorted(java.util.Comparator
                .comparing((RideEntity r) -> !byRide.get(r.getId()).isPriority())
                .thenComparing(RideEntity::getScheduledAt))
            .toList();
        rides.forEach(r -> offers.markViewed(byRide.get(r.getId())));
        return rides.stream().map(r -> responses.toResponse(r, driver, null)).toList();
    }

    /** Rides this driver has accepted and not yet completed. */
    public List<RideResponse> listMyAssignedRides() {
        UserEntity driver = currentUserService.requireUser();
        requireRole(driver, Role.DRIVER);
        return rideRepository
            .findByAcceptedByDriver_IdAndStatusInAndTestOrderByScheduledAtAsc(driver.getId(), RideStatus.ASSIGNED, policy.world(driver))
            .stream()
            .map(r -> responses.toResponse(r, driver, null))
            .toList();
    }

    public List<RideResponse> driverHistory(Integer limit) {
        UserEntity driver = currentUserService.requireUser();
        requireRole(driver, Role.DRIVER);
        int size = limit == null ? 50 : Math.max(1, Math.min(limit, 100));
        var page = org.springframework.data.domain.PageRequest.of(0, size,
            org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "scheduledAt", "id"));
        return rideRepository.findByAcceptedByDriver_IdAndStatusInAndTest(driver.getId(),
                java.util.EnumSet.of(RideStatus.COMPLETED, RideStatus.CANCELLED), policy.world(driver), page)
            .stream().map(r -> responses.toResponse(r, driver, null)).toList();
    }

    /** The caller's single most relevant active ride (driver view first), or empty. */
    public Optional<ActiveRideResponse> activeRide() {
        UserEntity user = currentUserService.requireUser();
        Instant now = clock.instant();
        boolean world = policy.world(user);
        java.util.Comparator<RideEntity> byTime = java.util.Comparator.comparing(RideEntity::getScheduledAt);
        if (user.getRole() == Role.DRIVER || user.getRole() == Role.ADMIN) {
            List<RideEntity> mine = rideRepository.findByAcceptedByDriver_IdAndStatusInAndTestOrderByScheduledAtAsc(
                user.getId(), RideStatus.ASSIGNED, world);
            Optional<RideEntity> pick = mine.stream()
                .filter(r -> r.getStatus() != RideStatus.ACCEPTED)
                .max(java.util.Comparator.comparingInt((RideEntity r) -> r.getStatus().ordinal()).thenComparing(byTime.reversed()));
            if (pick.isEmpty()) {
                Instant horizon = now.plus(Duration.ofMinutes(60));
                pick = mine.stream()
                    .filter(r -> r.getStatus() == RideStatus.ACCEPTED && !r.getScheduledAt().isAfter(horizon))
                    .min(byTime);
            }
            if (pick.isPresent()) {
                return Optional.of(new ActiveRideResponse("DRIVER", responses.toResponse(pick.get(), user, null)));
            }
        }
        Instant horizon = now.plus(Duration.ofMinutes(60));
        List<RideEntity> own = rideRepository.findByPassengerIdAndStatusInAndTest(user.getId(),
            java.util.EnumSet.of(RideStatus.ACCEPTED, RideStatus.EN_ROUTE, RideStatus.ARRIVED, RideStatus.PICKED_UP,
                RideStatus.REQUESTED, RideStatus.NO_DRIVER), world);
        // A far-future booking is not "active": ACCEPTED/REQUESTED count only for NOW rides or within the next hour.
        java.util.function.Predicate<RideEntity> relevant = r -> switch (r.getStatus()) {
            case EN_ROUTE, ARRIVED, PICKED_UP -> true;
            case ACCEPTED, REQUESTED -> r.getKind() == RideKind.NOW || !r.getScheduledAt().isAfter(horizon);
            default -> false;
        };
        Optional<RideEntity> pick = own.stream().filter(relevant).filter(r -> RideStatus.ASSIGNED.contains(r.getStatus()))
            .max(java.util.Comparator.comparingInt((RideEntity r) -> r.getStatus().ordinal()).thenComparing(byTime.reversed()));
        if (pick.isEmpty()) {
            pick = own.stream().filter(relevant).filter(r -> r.getStatus() == RideStatus.REQUESTED).min(byTime);
        }
        if (pick.isEmpty()) {
            Instant cutoff = now.minus(Duration.ofHours(2));
            pick = own.stream().filter(r -> r.getStatus() == RideStatus.NO_DRIVER && r.getScheduledAt().isAfter(cutoff))
                .max(byTime);
        }
        return pick.map(r -> new ActiveRideResponse("PASSENGER", responses.toResponse(r, user, null)));
    }

    @Transactional
    public RideResponse accept(Long rideId, boolean confirmProximity) {
        UserEntity driver = currentUserService.requireUser();
        requireRole(driver, Role.DRIVER);
        RideEntity ride = mustFindRide(rideId, driver);
        if (ride.getPassenger().getId().equals(driver.getId())) {
            throw new AppException(HttpStatus.FORBIDDEN, "Cannot accept your own ride");
        }
        if (ride.getAcceptedByDriver() != null && ride.getAcceptedByDriver().getId().equals(driver.getId())) {
            throw AppException.conflict("INVALID_TRANSITION", "Du har redan tagit resan");
        }
        Optional<RideOfferEntity> offer = offers.offerOf(rideId, driver.getId());
        if (ride.getStatus() != RideStatus.REQUESTED || offer.isEmpty() || !offer.get().getStatus().isOpen()) {
            if (offer.isEmpty() && ride.getStatus() == RideStatus.REQUESTED) {
                throw AppException.conflict("OFFER_CLOSED", "Resan är inte erbjuden till dig");
            }
            throw staleConflict(ride, offer);
        }
        if (!confirmProximity) {
            Optional<RideEntity> conflict = conflictChecker.findConflict(driver, ride);
            if (conflict.isPresent()) {
                RideEntity c = conflict.get();
                throw new AppException(HttpStatus.CONFLICT, "PROXIMITY_WARNING",
                    "Du har redan en resa runt samma tid — ta ändå?",
                    Map.of("conflictingRide", Map.of("id", c.getId(), "scheduledAt", c.getScheduledAt().toString(), "fromAddress", c.getFromAddress())));
            }
        }
        machine.transition(ride, RideStatus.ACCEPTED, Actor.DRIVER);
        ride.setAcceptedByDriver(driver);
        ride.setUrgent(false);
        try {
            offers.accept(ride, driver);
            ride = rideRepository.saveAndFlush(ride);
        } catch (org.springframework.dao.ConcurrencyFailureException | jakarta.persistence.OptimisticLockException e) {
            throw lostRace(rideId, driver.getId());
        }
        notificationRepository.deleteByRideIdAndKind(rideId, RideTimerService.DRIVER_REMINDER_30M); // new driver gets their own reminder
        events.record(ride, driver.getId(), RideEventRecorder.ACCEPTED);
        pushService.send(ride.getPassenger().getId(), PushCategory.RIDE_UPDATES, "ACCEPTED", rideId,
            "/app/resa/" + rideId, "ride.accepted", java.util.List.of(PushArgs.firstName(driver.getFullName())));
        responses.publish(ride);
        return responses.toResponse(ride, driver, null);
    }

    /** Lost an optimistic-lock race: re-read the committed state in a fresh transaction and report what really happened. */
    private AppException lostRace(Long rideId, Long driverId) {
        AppException ex = freshTx.execute(st -> {
            RideEntity fresh = rideRepository.findById(rideId).orElse(null);
            if (fresh == null) {
                return AppException.conflict("RIDE_CHANGED", "Resan har ändrats");
            }
            return staleConflict(fresh, offers.offerOf(rideId, driverId));
        });
        return ex != null ? ex : AppException.conflict("RIDE_CHANGED", "Resan har ändrats");
    }

    @Transactional
    public RideResponse decline(Long rideId, String comment) {
        UserEntity driver = currentUserService.requireUser();
        requireRole(driver, Role.DRIVER);
        RideEntity ride = mustFindRide(rideId, driver);
        Optional<RideOfferEntity> offer = offers.offerOf(rideId, driver.getId());
        if (ride.getStatus() != RideStatus.REQUESTED || offer.isEmpty() || !offer.get().getStatus().isOpen()) {
            if (offer.isEmpty() && ride.getStatus() == RideStatus.REQUESTED) {
                throw AppException.conflict("OFFER_CLOSED", "Resan är inte erbjuden till dig");
            }
            throw staleConflict(ride, offer);
        }
        boolean none = offers.decline(offer.get(), comment);
        ride.setRefusalDriver(driver);
        ride.setRefusalComment(comment);
        events.record(ride, driver.getId(), RideEventRecorder.DECLINED, comment);
        if (none) {
            systemTransitions.toNoDriver(ride, "all offers declined");
        } else {
            rideRepository.save(ride);
        }
        return responses.toResponse(ride, driver, null);
    }

    // ------------------------------------------------------------------ driver: driving

    @Transactional
    public RideResponse returnRide(Long rideId, String reason) {
        UserEntity driver = currentUserService.requireUser();
        requireRole(driver, Role.DRIVER);
        RideEntity ride = mustFindRide(rideId, driver);
        requireDriverAssignment(ride, driver);
        driverStateCheck(ride, RideStatus.REQUESTED);
        if (ride.getStatus() == RideStatus.EN_ROUTE && (reason == null || reason.isBlank())) {
            throw new AppException(HttpStatus.BAD_REQUEST, "reason: required when returning an ongoing ride");
        }
        machine.transition(ride, RideStatus.REQUESTED, Actor.DRIVER);
        ride.setAcceptedByDriver(null);
        ride.setStartedAt(null);
        resetWaitWindow(ride);
        notificationRepository.deleteByRideIdAndKindIn(rideId,
            java.util.List.of(RideTimerService.DRIVER_REMINDER_30M, "ETA_5MIN", "ARRIVED"));
        int open = offers.reofferAfterReturn(ride, driver.getId());
        ride = rideRepository.save(ride);
        events.record(ride, driver.getId(), RideEventRecorder.RETURNED, reason);
        // the passenger is not pushed: the ride is re-offered silently (still visible in the app)
        if (open == 0) {
            systemTransitions.toNoDriver(ride, "no drivers left after return");
        } else {
            responses.publish(ride, driver.getId());
        }
        return responses.toResponse(ride, driver, null);
    }

    @Transactional
    public RideResponse startDriving(Long rideId) {
        return driverStep(rideId, RideStatus.EN_ROUTE, RideEventRecorder.STARTED, r -> r.setStartedAt(clock.instant()),
            "EN_ROUTE", "ride.en_route", false);
    }

    @Transactional
    public RideResponse arrive(Long rideId) {
        return driverStep(rideId, RideStatus.ARRIVED, RideEventRecorder.ARRIVED, r -> r.setArrivedAt(clock.instant()),
            "ARRIVED", "ride.arrived", true);
    }

    @Transactional
    public RideResponse pickup(Long rideId) {
        return driverStep(rideId, RideStatus.PICKED_UP, RideEventRecorder.PICKED_UP, r -> r.setPickedUpAt(clock.instant()),
            null, null, false);
    }

    @Transactional
    public RideResponse complete(Long rideId) {
        return driverStep(rideId, RideStatus.COMPLETED, RideEventRecorder.COMPLETED, r -> r.setCompletedAt(clock.instant()),
            null, null, false);
    }

    private RideResponse driverStep(Long rideId, RideStatus to, String eventType, Consumer<RideEntity> mutate,
                                   String pushKind, String pushKey, boolean once) {
        UserEntity driver = currentUserService.requireUser();
        requireRole(driver, Role.DRIVER);
        RideEntity ride = mustFindRide(rideId, driver);
        requireDriverAssignment(ride, driver);
        driverStateCheck(ride, to);
        machine.transition(ride, to, Actor.DRIVER);
        mutate.accept(ride);
        ride = rideRepository.save(ride);
        events.record(ride, driver.getId(), eventType);
        if (pushKind != null && (!once || markNotificationSent(ride.getId(), pushKind))) {
            pushService.send(ride.getPassenger().getId(), PushCategory.RIDE_UPDATES, pushKind, ride.getId(),
                "/app/resa/" + ride.getId(), pushKey, java.util.List.of(PushArgs.firstName(driver.getFullName())));
        }
        responses.publish(ride);
        return responses.toResponse(ride, driver, null);
    }

    /** A cancelled ride is reported with its own code; everything else illegal is INVALID_TRANSITION (from the machine). */
    private void driverStateCheck(RideEntity ride, RideStatus to) {
        if (ride.getStatus() == RideStatus.CANCELLED) {
            throw AppException.conflict("RIDE_CANCELLED", "Resan är avbokad");
        }
        machine.require(ride, to, Actor.DRIVER);
    }

    @Transactional
    public RideResponse updateLocation(Long rideId, LocationUpdateRequest request) {
        UserEntity driver = currentUserService.requireUser();
        requireRole(driver, Role.DRIVER);
        RideEntity ride = mustFindRide(rideId, driver);
        requireDriverAssignment(ride, driver);
        RideStatus st = ride.getStatus();
        if (st == RideStatus.CANCELLED) {
            throw AppException.conflict("RIDE_CANCELLED", "Resan är avbokad");
        }
        if (st != RideStatus.EN_ROUTE && st != RideStatus.ARRIVED && st != RideStatus.PICKED_UP) {
            throw AppException.conflict("INVALID_TRANSITION", "Platsuppdatering kan bara skickas under körning");
        }
        ride.setLastDriverLat(request.lat());
        ride.setLastDriverLon(request.lon());
        ride.setLastLocationAt(clock.instant());
        // heading to the pickup until the passenger is on board, then to the destination
        boolean toPickup = st != RideStatus.PICKED_UP;
        ride.setEtaMinutes(calculateEtaMinutes(request.lat(), request.lon(),
            toPickup ? ride.getFromLat() : ride.getToLat(), toPickup ? ride.getFromLon() : ride.getToLon()));
        ride = rideRepository.save(ride);
        if (st == RideStatus.EN_ROUTE && ride.getEtaMinutes() != null && ride.getEtaMinutes() <= ETA_PUSH_MINUTES
            && markNotificationSent(rideId, "ETA_5MIN")) {
            pushService.send(ride.getPassenger().getId(), PushCategory.RIDE_UPDATES, "ETA_5MIN", rideId,
                "/app/resa/" + rideId, "ride.eta_5min", java.util.List.of(PushArgs.firstName(driver.getFullName())));
        }
        responses.publish(ride);
        return responses.toResponse(ride, driver, null);
    }

    /** Once-only guard (ride_notifications_sent): true when this call recorded the notification. */
    private boolean markNotificationSent(Long rideId, String kind) {
        return notificationMarker.markOnce(rideId, kind, clock.instant());
    }

    // ------------------------------------------------------------------ feedback / sharing

    @Transactional
    public void submitFeedback(Long rideId, SubmitFeedbackRequest request) {
        UserEntity passenger = currentUserService.requireUser();
        RideEntity ride = mustFindRide(rideId, passenger);
        requirePassenger(ride, passenger);
        if (ride.getStatus() != RideStatus.COMPLETED) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Feedback only for completed rides");
        }
        rideFeedbackRepository.findByRideId(rideId).ifPresent(existing -> {
            throw new AppException(HttpStatus.CONFLICT, "Feedback already submitted");
        });
        RideFeedbackEntity feedback = new RideFeedbackEntity();
        feedback.setRide(ride);
        feedback.setPassenger(passenger);
        feedback.setStars(request.stars());
        feedback.setComment(request.comment());
        rideFeedbackRepository.save(feedback);
        events.record(ride, passenger.getId(), RideEventRecorder.FEEDBACK, "stars=" + request.stars());
    }

    @Transactional
    public String createShareToken(Long rideId, String baseUrl) {
        UserEntity passenger = currentUserService.requireUser();
        RideEntity ride = mustFindRide(rideId, passenger);
        requirePassenger(ride, passenger);
        String token = UUID.randomUUID().toString().replace("-", "");
        ride.setShareToken(token);
        ride.setShareExpiresAt(clock.instant().plusSeconds(60L * 60L * 8L));
        rideRepository.save(ride);
        events.record(ride, passenger.getId(), RideEventRecorder.SHARE_CREATED);
        return baseUrl + "/api/rides/share/" + token;
    }

    @Transactional
    public void revokeShareToken(Long rideId) {
        UserEntity passenger = currentUserService.requireUser();
        RideEntity ride = mustFindRide(rideId, passenger);
        requirePassenger(ride, passenger);
        ride.setShareToken(null);
        ride.setShareExpiresAt(null);
        rideRepository.save(ride);
        events.record(ride, passenger.getId(), RideEventRecorder.SHARE_REVOKED);
    }

    public RideResponse byShareToken(String token) {
        RideEntity ride = rideRepository.findByShareToken(token)
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Share link not found"));
        if (ride.getShareExpiresAt() == null || ride.getShareExpiresAt().isBefore(clock.instant())) {
            throw new AppException(HttpStatus.GONE, "Share link expired");
        }
        // Anonymous viewers may follow any share link; an authenticated user must be in the ride's world.
        UserEntity viewer = currentUserService.currentUserOrNull();
        if (viewer != null) {
            policy.requireSameWorld(viewer, ride);
        }
        // Share links are always rendered anonymously: no phones, passenger name, note or actions.
        return responses.toResponse(ride, null, null);
    }

    @Transactional
    public RideResponse getMyRide(Long rideId) {
        UserEntity user = currentUserService.requireUser();
        RideEntity ride = mustFindRide(rideId, user);
        boolean isPassenger = ride.getPassenger().getId().equals(user.getId());
        boolean isDriver = ride.getAcceptedByDriver() != null && ride.getAcceptedByDriver().getId().equals(user.getId());
        if (isPassenger || isDriver) {
            return responses.toResponse(ride, user, null);
        }
        Optional<RideOfferEntity> offer = user.getRole() == Role.USER ? Optional.empty() : offers.offerOf(rideId, user.getId());
        if (offer.isPresent() && offer.get().getStatus().isOpen() && ride.getStatus() == RideStatus.REQUESTED) {
            offers.markViewed(offer.get());
            return responses.toResponse(ride, user, null);
        }
        if (user.getRole() == Role.ADMIN) {
            return responses.toResponse(ride, user, null);
        }
        if (offer.isPresent()) {
            throw staleConflict(ride, offer); // the offer link/notification is stale
        }
        throw new AppException(HttpStatus.FORBIDDEN, "No access to ride");
    }

    public DriverStatsResponse driverStats() {
        UserEntity driver = currentUserService.requireUser();
        requireRole(driver, Role.DRIVER);
        List<RideEntity> mine = rideRepository.findByAcceptedByDriver_IdAndTest(driver.getId(), policy.world(driver));
        long completed = mine.stream().filter(r -> r.getStatus() == RideStatus.COMPLETED).count();
        long accepted = mine.stream().filter(r -> r.getStatus() != RideStatus.CANCELLED).count();
        return new DriverStatsResponse(completed, accepted);
    }

    @Transactional
    public void adminDeleteRide(Long rideId) {
        // Admins are real users but may delete any ride (including test rides) - the one isolation exception.
        currentUserService.requireUser();
        deleteChildren(rideId);
        rideRepository.deleteById(rideId);
    }

    /** Passenger removes a cancelled or unanswered ride from their history. */
    @Transactional
    public void deleteMyRide(Long rideId) {
        UserEntity user = currentUserService.requireUser();
        RideEntity ride = mustFindRide(rideId, user);
        requirePassenger(ride, user);
        if (ride.getStatus() != RideStatus.CANCELLED && ride.getStatus() != RideStatus.NO_DRIVER) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Ride cannot be deleted");
        }
        deleteChildren(rideId);
        rideRepository.delete(ride);
    }

    private void deleteChildren(Long rideId) {
        eventRepository.deleteByRideId(rideId);
        rideFeedbackRepository.deleteByRideIdQuery(rideId);
        offerRepository.deleteByRideId(rideId);
        messageRepository.deleteByRideId(rideId);
        notificationRepository.deleteByRideId(rideId);
    }

    /** Loads a ride for a user; a ride from the other world is reported as not found. */
    public RideEntity mustFindRide(Long rideId, UserEntity user) {
        RideEntity ride = rideRepository.findById(rideId).orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Ride not found"));
        policy.requireSameWorld(user, ride);
        return ride;
    }

    // ------------------------------------------------------------------ helpers

    private UserEntity resolvePassenger(UserEntity actor, Long passengerUserId) {
        if (passengerUserId == null) {
            return actor;
        }
        if (actor.getRole() != Role.DRIVER && actor.getRole() != Role.ADMIN) {
            throw new AppException(HttpStatus.FORBIDDEN, "Cannot book for another user");
        }
        UserEntity passenger = userRepository.findById(passengerUserId)
            .orElseThrow(() -> new AppException(HttpStatus.BAD_REQUEST, "Passenger user not found"));
        if (!policy.canBookFor(actor, passenger)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Passenger user not found");
        }
        if (!passenger.isEnabled()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Passenger account is disabled");
        }
        if (!passenger.isApproved()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Passenger account is not approved");
        }
        return passenger;
    }

    private void requireRole(UserEntity user, Role role) {
        if (user.getRole() != role && user.getRole() != Role.ADMIN) {
            throw new AppException(HttpStatus.FORBIDDEN, "Role " + role.name() + " required");
        }
    }

    private void requirePassenger(RideEntity ride, UserEntity user) {
        if (!ride.getPassenger().getId().equals(user.getId())) {
            throw new AppException(HttpStatus.FORBIDDEN, "Not your ride");
        }
    }

    /**
     * The caller must be the assigned driver. A driver who was offered the ride but no longer holds it gets a
     * 409 with the reason (taken / cancelled / changed); anybody else is simply forbidden.
     */
    private void requireDriverAssignment(RideEntity ride, UserEntity driver) {
        if (ride.getAcceptedByDriver() != null && ride.getAcceptedByDriver().getId().equals(driver.getId())) {
            return;
        }
        Optional<RideOfferEntity> offer = offers.offerOf(ride.getId(), driver.getId());
        if (offer.isPresent()) {
            throw staleConflict(ride, offer);
        }
        throw new AppException(HttpStatus.FORBIDDEN, "Ride is assigned to another driver");
    }

    /** Maps "this ride is no longer what the caller expected" to a coded 409. */
    private AppException staleConflict(RideEntity ride, Optional<RideOfferEntity> offer) {
        RideStatus st = ride.getStatus();
        // A withdrawn offer on a ride we still see as REQUESTED means another driver's accept (or a cancel) just
        // committed: read the committed status so the loser gets the precise reason (RIDE_TAKEN / RIDE_CANCELLED).
        if (st == RideStatus.REQUESTED && offer.map(o -> o.getStatus() == OfferStatus.WITHDRAWN).orElse(false)) {
            st = rideRepository.findCommittedStatus(ride.getId()).map(RideStatus::valueOf).orElse(st);
        }
        if (st == RideStatus.CANCELLED) {
            return AppException.conflict("RIDE_CANCELLED", "Resan är avbokad");
        }
        if (RideStatus.ASSIGNED.contains(st) || st == RideStatus.COMPLETED) {
            return AppException.conflict("RIDE_TAKEN", "Resan är redan tagen");
        }
        if (st == RideStatus.REQUESTED && offer.map(o -> !o.getStatus().isOpen()).orElse(false)) {
            return AppException.conflict("OFFER_CLOSED", "Erbjudandet är inte längre öppet");
        }
        return AppException.conflict("RIDE_CHANGED", "Resan har ändrats");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private int calculateEtaMinutes(double fromLat, double fromLon, double toLat, double toLon) {
        double distanceKm = Geo.haversineMeters(fromLat, fromLon, toLat, toLon) / 1000.0;
        double hours = distanceKm / Math.max(5.0, defaultEtaKmh);
        return Math.max(1, (int) Math.round(hours * 60));
    }
}
