package com.farfartaxi.backend.service;

import com.farfartaxi.backend.api.dto.RideDtos.AvailabilityDto;
import com.farfartaxi.backend.model.UserEntity;
import com.farfartaxi.backend.repo.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Driver availability: the "available now" toggle (NOW rides) and the away period (any ride in the period). */
@Service
public class AvailabilityService {
    private final CurrentUserService currentUserService;
    private final UserRepository users;

    public AvailabilityService(CurrentUserService currentUserService, UserRepository users) {
        this.currentUserService = currentUserService;
        this.users = users;
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
        return toDto(users.save(u));
    }

    private static AvailabilityDto toDto(UserEntity u) {
        return new AvailabilityDto(u.isDriverAvailableNow(), u.getDriverAwayFrom(), u.getDriverAwayUntil());
    }
}
