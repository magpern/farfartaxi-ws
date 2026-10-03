package com.farfartaxi.backend.service;

import com.farfartaxi.backend.api.dto.RideDtos.AvailabilityDto;
import com.farfartaxi.backend.model.UserEntity;
import com.farfartaxi.backend.repo.RideRepository;
import com.farfartaxi.backend.repo.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Driver availability: the "available now" toggle (NOW rides) and the away period (any ride in the period). */
@Service
public class AvailabilityService {
    private final CurrentUserService currentUserService;
    private final UserRepository users;
    private final RideOfferService offers;
    private final RideRepository rides;
    private final RideSystemTransitions transitions;

    public AvailabilityService(CurrentUserService currentUserService, UserRepository users, RideOfferService offers,
                               RideRepository rides, RideSystemTransitions transitions) {
        this.currentUserService = currentUserService;
        this.users = users;
        this.offers = offers;
        this.rides = rides;
        this.transitions = transitions;
    }

    public AvailabilityDto get() {
        return toDto(currentUserService.requireUser());
    }

    @Transactional
    public AvailabilityDto put(AvailabilityDto dto) {
        UserEntity u = currentUserService.requireUser();
        if ((dto.awayFrom() == null) != (dto.awayUntil() == null)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "awayFrom and awayUntil must be given together");
        }
        if (dto.awayFrom() != null && dto.awayUntil().isBefore(dto.awayFrom())) {
            throw new AppException(HttpStatus.BAD_REQUEST, "awayUntil must not be before awayFrom");
        }
        u.setDriverAvailableNow(dto.availableNow());
        u.setDriverAwayFrom(dto.awayFrom());
        u.setDriverAwayUntil(dto.awayUntil());
        u = users.save(u);
        // Going away: open offers on rides inside the period are withdrawn; rides left without offers become NO_DRIVER.
        if (u.getDriverAwayFrom() != null) {
            for (var ride : offers.withdrawForAway(u, id -> rides.findById(id).orElse(null))) {
                transitions.toNoDriver(ride, "no drivers left (driver away)");
            }
        }
        return toDto(u);
    }

    private static AvailabilityDto toDto(UserEntity u) {
        return new AvailabilityDto(u.isDriverAvailableNow(), u.getDriverAwayFrom(), u.getDriverAwayUntil());
    }
}
