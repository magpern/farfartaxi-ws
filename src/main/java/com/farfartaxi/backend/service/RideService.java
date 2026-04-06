package com.farfartaxi.backend.service;

import com.farfartaxi.backend.api.dto.RideDtos.BookRideRequest;
import com.farfartaxi.backend.api.dto.RideDtos.DriverRefuseRequest;
import com.farfartaxi.backend.api.dto.RideDtos.DriverStatsResponse;
import com.farfartaxi.backend.api.dto.RideDtos.LocationUpdateRequest;
import com.farfartaxi.backend.api.dto.RideDtos.RideResponse;
import com.farfartaxi.backend.api.dto.RideDtos.SubmitFeedbackRequest;
import com.farfartaxi.backend.model.RideEntity;
import com.farfartaxi.backend.model.RideFeedbackEntity;
import com.farfartaxi.backend.model.RideStatus;
import com.farfartaxi.backend.model.Role;
import com.farfartaxi.backend.model.UserEntity;
import com.farfartaxi.backend.repo.RideFeedbackRepository;
import com.farfartaxi.backend.repo.RideRepository;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class RideService {
    private final RideRepository rideRepository;
    private final RideFeedbackRepository rideFeedbackRepository;
    private final CurrentUserService currentUserService;
    private final PushService pushService;
    private final RideRealtimeService realtimeService;
    private final double defaultEtaKmh;

    public RideService(
        RideRepository rideRepository,
        RideFeedbackRepository rideFeedbackRepository,
        CurrentUserService currentUserService,
        PushService pushService,
        RideRealtimeService realtimeService,
        @Value("${app.eta.default-kmh}") double defaultEtaKmh
    ) {
        this.rideRepository = rideRepository;
        this.rideFeedbackRepository = rideFeedbackRepository;
        this.currentUserService = currentUserService;
        this.pushService = pushService;
        this.realtimeService = realtimeService;
        this.defaultEtaKmh = defaultEtaKmh;
    }

    @Transactional
    public RideResponse book(BookRideRequest request) {
        UserEntity user = currentUserService.requireUser();
        RideEntity ride = new RideEntity();
        ride.setPassenger(user);
        ride.setFromAddress(request.fromAddress());
        ride.setFromLat(request.fromLat());
        ride.setFromLon(request.fromLon());
        ride.setToAddress(request.toAddress());
        ride.setToLat(request.toLat());
        ride.setToLon(request.toLon());
        ride.setWaypointsJson(request.waypointsJson());
        ride.setScheduledAt(request.scheduledAt());
        ride.setStatus(RideStatus.PENDING_OPEN);
        ride = rideRepository.save(ride);
        pushService.notifyRole(Role.DRIVER, "Ny Farfartaxi-bokning", "En ny resa vantar pa accept.");
        return toResponse(ride);
    }

    public List<RideResponse> listMine(boolean history) {
        UserEntity user = currentUserService.requireUser();
        Instant now = Instant.now();
        List<RideEntity> rides = history
            ? rideRepository.findByPassengerIdAndScheduledAtBeforeOrderByScheduledAtDesc(user.getId(), now)
            : rideRepository.findByPassengerIdAndScheduledAtAfterOrderByScheduledAtAsc(user.getId(), now);
        return rides.stream().map(this::toResponse).toList();
    }

    @Transactional
    public RideResponse cancelRide(Long rideId, String reason) {
        UserEntity user = currentUserService.requireUser();
        RideEntity ride = mustFindRide(rideId);
        if (!ride.getPassenger().getId().equals(user.getId())) {
            throw new AppException(HttpStatus.FORBIDDEN, "Not your ride");
        }
        if (ride.getStatus() != RideStatus.PENDING_OPEN) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Ride cannot be cancelled");
        }
        ride.setStatus(RideStatus.CANCELLED);
        ride.setCancelReason(reason);
        return toResponse(rideRepository.save(ride));
    }

    public List<RideResponse> listOpenForDrivers() {
        return rideRepository.findByStatusOrderByScheduledAtAsc(RideStatus.PENDING_OPEN).stream().map(this::toResponse).toList();
    }

    @Transactional
    public RideResponse accept(Long rideId) {
        UserEntity driver = currentUserService.requireUser();
        requireRole(driver, Role.DRIVER);
        RideEntity ride = mustFindRide(rideId);
        if (ride.getStatus() != RideStatus.PENDING_OPEN || ride.getAcceptedByDriver() != null) {
            throw new AppException(HttpStatus.CONFLICT, "Ride already taken");
        }
        ride.setAcceptedByDriver(driver);
        ride.setStatus(RideStatus.ACCEPTED);
        ride = rideRepository.save(ride);
        pushService.notifyUser(ride.getPassenger().getId(), "Din resa accepterades", driver.getFullName() + " tar resan.");
        realtimeService.publish(ride.getId(), ride.getPassenger().getId(), driver.getId(), toResponse(ride));
        return toResponse(ride);
    }

    @Transactional
    public RideResponse refuse(Long rideId, DriverRefuseRequest request) {
        UserEntity driver = currentUserService.requireUser();
        requireRole(driver, Role.DRIVER);
        RideEntity ride = mustFindRide(rideId);
        if (ride.getStatus() != RideStatus.PENDING_OPEN) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Ride cannot be refused now");
        }
        ride.setRefusalDriver(driver);
        ride.setRefusalComment(request.comment());
        ride.setStatus(RideStatus.REJECTED);
        return toResponse(rideRepository.save(ride));
    }

    @Transactional
    public RideResponse unaccept(Long rideId) {
        UserEntity driver = currentUserService.requireUser();
        requireRole(driver, Role.DRIVER);
        RideEntity ride = mustFindRide(rideId);
        if (ride.getAcceptedByDriver() == null || !ride.getAcceptedByDriver().getId().equals(driver.getId())) {
            throw new AppException(HttpStatus.FORBIDDEN, "Only assigned driver can unaccept");
        }
        if (ride.getStatus() != RideStatus.ACCEPTED) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Ride cannot be unaccepted now");
        }
        ride.setAcceptedByDriver(null);
        ride.setStatus(RideStatus.PENDING_OPEN);
        ride = rideRepository.save(ride);
        pushService.notifyRole(Role.DRIVER, "Resa blev ledig igen", "En resa ar tillbaka i koen.");
        return toResponse(ride);
    }

    @Transactional
    public RideResponse startDriving(Long rideId) {
        UserEntity driver = currentUserService.requireUser();
        requireRole(driver, Role.DRIVER);
        RideEntity ride = mustFindRide(rideId);
        requireDriverAssignment(ride, driver);
        if (ride.getStatus() != RideStatus.ACCEPTED) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Ride must be accepted first");
        }
        ride.setStatus(RideStatus.IN_PROGRESS);
        ride.setStartedAt(Instant.now());
        ride = rideRepository.save(ride);
        pushService.notifyUser(ride.getPassenger().getId(), "Foraren har startat", "Resan ar pa vag.");
        realtimeService.publish(ride.getId(), ride.getPassenger().getId(), driver.getId(), toResponse(ride));
        return toResponse(ride);
    }

    @Transactional
    public RideResponse updateLocation(Long rideId, LocationUpdateRequest request) {
        UserEntity driver = currentUserService.requireUser();
        requireRole(driver, Role.DRIVER);
        RideEntity ride = mustFindRide(rideId);
        requireDriverAssignment(ride, driver);
        if (ride.getStatus() != RideStatus.IN_PROGRESS) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Ride is not in progress");
        }
        ride.setLastDriverLat(request.lat());
        ride.setLastDriverLon(request.lon());
        ride.setLastLocationAt(Instant.now());
        ride.setEtaMinutes(calculateEtaMinutes(request.lat(), request.lon(), ride.getToLat(), ride.getToLon()));
        ride = rideRepository.save(ride);
        realtimeService.publish(ride.getId(), ride.getPassenger().getId(), driver.getId(), toResponse(ride));
        return toResponse(ride);
    }

    @Transactional
    public RideResponse complete(Long rideId) {
        UserEntity driver = currentUserService.requireUser();
        requireRole(driver, Role.DRIVER);
        RideEntity ride = mustFindRide(rideId);
        requireDriverAssignment(ride, driver);
        if (ride.getStatus() != RideStatus.IN_PROGRESS) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Ride is not in progress");
        }
        ride.setStatus(RideStatus.COMPLETED);
        ride.setCompletedAt(Instant.now());
        ride = rideRepository.save(ride);
        pushService.notifyUser(ride.getPassenger().getId(), "Resan klar", "Tack for att du bokade Farfartaxi.");
        realtimeService.publish(ride.getId(), ride.getPassenger().getId(), driver.getId(), toResponse(ride));
        return toResponse(ride);
    }

    @Transactional
    public void submitFeedback(Long rideId, SubmitFeedbackRequest request) {
        UserEntity passenger = currentUserService.requireUser();
        RideEntity ride = mustFindRide(rideId);
        if (!ride.getPassenger().getId().equals(passenger.getId())) {
            throw new AppException(HttpStatus.FORBIDDEN, "Not your ride");
        }
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
    }

    @Transactional
    public String createShareToken(Long rideId, String baseUrl) {
        UserEntity passenger = currentUserService.requireUser();
        RideEntity ride = mustFindRide(rideId);
        if (!ride.getPassenger().getId().equals(passenger.getId())) {
            throw new AppException(HttpStatus.FORBIDDEN, "Not your ride");
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        ride.setShareToken(token);
        ride.setShareExpiresAt(Instant.now().plusSeconds(60L * 60L * 8L));
        rideRepository.save(ride);
        return baseUrl + "/api/rides/share/" + token;
    }

    @Transactional
    public void revokeShareToken(Long rideId) {
        UserEntity passenger = currentUserService.requireUser();
        RideEntity ride = mustFindRide(rideId);
        if (!ride.getPassenger().getId().equals(passenger.getId())) {
            throw new AppException(HttpStatus.FORBIDDEN, "Not your ride");
        }
        ride.setShareToken(null);
        ride.setShareExpiresAt(null);
        rideRepository.save(ride);
    }

    public RideResponse byShareToken(String token) {
        RideEntity ride = rideRepository.findByShareToken(token)
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Share link not found"));
        if (ride.getShareExpiresAt() == null || ride.getShareExpiresAt().isBefore(Instant.now())) {
            throw new AppException(HttpStatus.GONE, "Share link expired");
        }
        return toResponse(ride);
    }

    public RideResponse getMyRide(Long rideId) {
        UserEntity user = currentUserService.requireUser();
        RideEntity ride = mustFindRide(rideId);
        boolean isPassenger = ride.getPassenger().getId().equals(user.getId());
        boolean isDriver = ride.getAcceptedByDriver() != null && ride.getAcceptedByDriver().getId().equals(user.getId());
        if (!isPassenger && !isDriver && user.getRole() != Role.ADMIN) {
            throw new AppException(HttpStatus.FORBIDDEN, "No access to ride");
        }
        return toResponse(ride);
    }

    public DriverStatsResponse driverStats() {
        UserEntity driver = currentUserService.requireUser();
        requireRole(driver, Role.DRIVER);
        long completed = rideRepository.findAll().stream()
            .filter(r -> r.getAcceptedByDriver() != null && driver.getId().equals(r.getAcceptedByDriver().getId()))
            .filter(r -> r.getStatus() == RideStatus.COMPLETED)
            .count();
        long accepted = rideRepository.findAll().stream()
            .filter(r -> r.getAcceptedByDriver() != null && driver.getId().equals(r.getAcceptedByDriver().getId()))
            .count();
        return new DriverStatsResponse(completed, accepted);
    }

    @Transactional
    public void adminDeleteRide(Long rideId) {
        currentUserService.requireUser();
        rideRepository.deleteById(rideId);
    }

    /** Passenger removes a cancelled or driver-rejected ride from their history. */
    @Transactional
    public void deleteMyRide(Long rideId) {
        UserEntity user = currentUserService.requireUser();
        RideEntity ride = mustFindRide(rideId);
        if (!ride.getPassenger().getId().equals(user.getId())) {
            throw new AppException(HttpStatus.FORBIDDEN, "Not your ride");
        }
        if (ride.getStatus() != RideStatus.CANCELLED && ride.getStatus() != RideStatus.REJECTED) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Ride cannot be deleted");
        }
        rideRepository.delete(ride);
    }

    public RideEntity mustFindRide(Long rideId) {
        return rideRepository.findById(rideId).orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "Ride not found"));
    }

    private void requireRole(UserEntity user, Role role) {
        if (user.getRole() != role && user.getRole() != Role.ADMIN) {
            throw new AppException(HttpStatus.FORBIDDEN, "Role " + role.name() + " required");
        }
    }

    private void requireDriverAssignment(RideEntity ride, UserEntity driver) {
        if (ride.getAcceptedByDriver() == null || !ride.getAcceptedByDriver().getId().equals(driver.getId())) {
            throw new AppException(HttpStatus.FORBIDDEN, "Ride is assigned to another driver");
        }
    }

    private RideResponse toResponse(RideEntity ride) {
        Long driverId = ride.getAcceptedByDriver() == null ? null : ride.getAcceptedByDriver().getId();
        String driverName = ride.getAcceptedByDriver() == null ? null : ride.getAcceptedByDriver().getFullName();
        return new RideResponse(
            ride.getId(),
            ride.getStatus().name(),
            ride.getFromAddress(),
            ride.getFromLat(),
            ride.getFromLon(),
            ride.getToAddress(),
            ride.getToLat(),
            ride.getToLon(),
            ride.getScheduledAt(),
            ride.getPassenger().getId(),
            driverId,
            driverName,
            ride.getEtaMinutes(),
            ride.getLastDriverLat(),
            ride.getLastDriverLon(),
            ride.getLastLocationAt()
        );
    }

    private int calculateEtaMinutes(double fromLat, double fromLon, double toLat, double toLon) {
        double distanceKm = haversineKm(fromLat, fromLon, toLat, toLon);
        double hours = distanceKm / Math.max(5.0, defaultEtaKmh);
        return Math.max(1, (int) Math.round(hours * 60));
    }

    private double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
            * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return r * c;
    }
}
