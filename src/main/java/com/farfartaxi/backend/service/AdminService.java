package com.farfartaxi.backend.service;

import com.farfartaxi.backend.api.dto.AdminDtos.UpdateUserRequest;
import com.farfartaxi.backend.model.Role;
import com.farfartaxi.backend.model.UserEntity;
import com.farfartaxi.backend.repo.UserRepository;
import jakarta.transaction.Transactional;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AdminService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AdminService.class);
    private final UserRepository userRepository;
    private final CurrentUserService currentUserService;
    private final RefreshTokenService refreshTokenService;
    private final java.time.Clock clock;
    private final com.farfartaxi.backend.repo.RideOfferRepository offerRepository;
    private final com.farfartaxi.backend.repo.RideMessageRepository messageRepository;
    private final com.farfartaxi.backend.repo.RideRepository rideRepository;
    private final com.farfartaxi.backend.repo.RideEventRepository eventRepository;

    public AdminService(UserRepository userRepository, CurrentUserService currentUserService, RefreshTokenService refreshTokenService, java.time.Clock clock,
                        com.farfartaxi.backend.repo.RideOfferRepository offerRepository,
                        com.farfartaxi.backend.repo.RideMessageRepository messageRepository,
                        com.farfartaxi.backend.repo.RideRepository rideRepository,
                        com.farfartaxi.backend.repo.RideEventRepository eventRepository) {
        this.rideRepository = rideRepository;
        this.eventRepository = eventRepository;
        this.offerRepository = offerRepository;
        this.messageRepository = messageRepository;
        this.clock = clock;
        this.refreshTokenService = refreshTokenService;
        this.userRepository = userRepository;
        this.currentUserService = currentUserService;
    }

    public List<UserEntity> listUsers() {
        return userRepository.findAll();
    }

    @Transactional
    public UserEntity setRole(Long userId, Role role) {
        UserEntity actor = currentUserService.requireUser();
        UserEntity user = userRepository.findById(userId)
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "User not found"));
        if (user.isTest()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Test accounts cannot change role");
        }
        if (user.getRole() == Role.ADMIN && role != Role.ADMIN && userRepository.countByRole(Role.ADMIN) <= 1) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Cannot remove the last admin");
        }
        if (user.getId().equals(actor.getId()) && user.getRole() == Role.ADMIN && role != Role.ADMIN) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Cannot demote yourself from admin");
        }
        user.setRole(role);
        return userRepository.save(user);
    }

    @Transactional
    public UserEntity setMustChangePassword(Long userId, boolean mustChange) {
        UserEntity user = userRepository.findById(userId)
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "User not found"));
        if (user.getPasswordHash() == null) {
            if (mustChange) {
                throw new AppException(HttpStatus.BAD_REQUEST, "User has no password (Google sign-in only)");
            }
            user.setMustChangePassword(false);
            return userRepository.save(user);
        }
        user.setMustChangePassword(mustChange);
        return userRepository.save(user);
    }

    @Transactional
    public UserEntity updateUser(Long userId, UpdateUserRequest request) {
        UserEntity user = userRepository.findById(userId)
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "User not found"));
        if (request.fullName() != null && !request.fullName().isBlank()) {
            user.setFullName(request.fullName().trim());
        }
        if (request.email() != null && !request.email().isBlank()) {
            user.setEmail(request.email().toLowerCase().trim());
        }
        if (request.phone() != null) {
            user.setPhone(request.phone());
        }
        if (request.vehicleNote() != null) {
            user.setVehicleNote(request.vehicleNote());
        }
        return userRepository.save(user);
    }

    /** Revokes all refresh tokens and invalidates outstanding access tokens (credential version bump). */
    @Transactional
    public UserEntity logoutEverywhere(Long userId) {
        UserEntity actor = currentUserService.requireUser();
        UserEntity user = userRepository.findById(userId)
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "User not found"));
        java.time.Instant now = java.time.Instant.now(clock).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        if (user.getCredentialsChangedAt() != null && !now.isAfter(user.getCredentialsChangedAt())) {
            now = user.getCredentialsChangedAt().plusMillis(1);
        }
        user.setCredentialsChangedAt(now);
        user = userRepository.save(user);
        refreshTokenService.revokeAllForUser(userId);
        log.info("Admin id={} logged out everywhere user id={}", actor.getId(), userId);
        return user;
    }

    @Transactional
    public UserEntity approve(Long userId) {
        UserEntity actor = currentUserService.requireUser();
        UserEntity user = userRepository.findById(userId)
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "User not found"));
        user.setApproved(true);
        log.info("Admin id={} approved user id={}", actor.getId(), user.getId());
        return userRepository.save(user);
    }

    @Transactional
    public UserEntity setEnabled(Long userId, boolean enabled) {
        UserEntity actor = currentUserService.requireUser();
        UserEntity target = userRepository.findById(userId)
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "User not found"));
        if (target.getId().equals(actor.getId()) && !enabled) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Cannot disable yourself");
        }
        if (!enabled && target.getRole() == Role.ADMIN) {
            long activeAdmins = userRepository.countByRoleAndEnabled(Role.ADMIN, true);
            if (activeAdmins <= 1 && target.isEnabled()) {
                throw new AppException(HttpStatus.BAD_REQUEST, "Cannot disable the last active admin");
            }
        }
        target.setEnabled(enabled);
        return userRepository.save(target);
    }

    @Transactional
    public void deleteUser(Long userId) {
        UserEntity actor = currentUserService.requireUser();
        if (actor.getId().equals(userId)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Cannot delete yourself");
        }
        UserEntity target = userRepository.findById(userId)
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "User not found"));
        if (target.getRole() == Role.ADMIN && userRepository.countByRole(Role.ADMIN) <= 1) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Cannot delete the last admin");
        }
        try {
            // offers and canned messages are history that must not block deleting a driver
            offerRepository.deleteByDriverId(userId);
            rideRepository.clearRefusalDriver(userId);
            eventRepository.clearActor(userId);
            messageRepository.deleteBySenderId(userId);
            userRepository.delete(target);
            userRepository.flush(); // surface FK violations here instead of at commit (which would be a 500)
        } catch (DataIntegrityViolationException e) {
            throw new AppException(HttpStatus.CONFLICT, "User has related data; block the account instead or remove rides first");
        }
    }
}
