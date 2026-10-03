package com.farfartaxi.backend.service;

import com.farfartaxi.backend.model.RideEntity;
import com.farfartaxi.backend.model.RideStatus;
import com.farfartaxi.backend.model.UserEntity;
import com.farfartaxi.backend.repo.RideRepository;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Proximity warning only (not true conflict detection): another assigned ride within +-N minutes. */
@Component
public class ProximityRideConflictChecker implements RideConflictChecker {
    private final RideRepository rideRepository;
    private final Duration window;

    public ProximityRideConflictChecker(RideRepository rideRepository,
                                        @Value("${app.rides.proximity-minutes:45}") long minutes) {
        this.rideRepository = rideRepository;
        this.window = Duration.ofMinutes(minutes);
    }

    @Override
    public Optional<RideEntity> findConflict(UserEntity driver, RideEntity candidate) {
        return rideRepository
            .findByAcceptedByDriver_IdAndStatusInAndTestOrderByScheduledAtAsc(driver.getId(), RideStatus.ASSIGNED, candidate.isTest())
            .stream()
            .filter(r -> !r.getId().equals(candidate.getId()))
            .filter(r -> Duration.between(r.getScheduledAt(), candidate.getScheduledAt()).abs().compareTo(window) <= 0)
            .findFirst();
    }
}
