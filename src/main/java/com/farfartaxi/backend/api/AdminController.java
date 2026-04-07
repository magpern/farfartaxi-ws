package com.farfartaxi.backend.api;

import com.farfartaxi.backend.api.dto.AdminDtos.ForcePasswordResetRequest;
import com.farfartaxi.backend.api.dto.AdminDtos.SetUserEnabledRequest;
import com.farfartaxi.backend.api.dto.AdminDtos.UpdateUserRequest;
import com.farfartaxi.backend.model.Role;
import com.farfartaxi.backend.model.UserEntity;
import com.farfartaxi.backend.service.AdminService;
import com.farfartaxi.backend.service.RideService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {
    private final AdminService adminService;
    private final RideService rideService;

    public AdminController(AdminService adminService, RideService rideService) {
        this.adminService = adminService;
        this.rideService = rideService;
    }

    @GetMapping("/users")
    public List<Map<String, Object>> users() {
        return adminService.listUsers().stream().map(this::toDto).toList();
    }

    @PostMapping("/users/{userId}/promote-driver")
    public Map<String, Object> promoteDriver(@PathVariable Long userId) {
        return toDto(adminService.setRole(userId, Role.DRIVER));
    }

    @PostMapping("/users/{userId}/demote-user")
    public Map<String, Object> demoteUser(@PathVariable Long userId) {
        return toDto(adminService.setRole(userId, Role.USER));
    }

    @PostMapping("/users/{userId}/promote-admin")
    public Map<String, Object> promoteAdmin(@PathVariable Long userId) {
        return toDto(adminService.setRole(userId, Role.ADMIN));
    }

    @PostMapping("/users/{userId}/demote-admin-to-driver")
    public Map<String, Object> demoteAdminToDriver(@PathVariable Long userId) {
        return toDto(adminService.setRole(userId, Role.DRIVER));
    }

    @PostMapping("/users/{userId}/enabled")
    public Map<String, Object> setUserEnabled(@PathVariable Long userId, @Valid @RequestBody SetUserEnabledRequest request) {
        return toDto(adminService.setEnabled(userId, request.enabled()));
    }

    @DeleteMapping("/users/{userId}")
    public void deleteUser(@PathVariable Long userId) {
        adminService.deleteUser(userId);
    }

    @PostMapping("/users/{userId}/force-password-change")
    public Map<String, Object> forcePasswordChange(@PathVariable Long userId, @Valid @RequestBody ForcePasswordResetRequest request) {
        return toDto(adminService.setMustChangePassword(userId, request.mustChangePassword()));
    }

    @PatchMapping("/users/{userId}")
    public Map<String, Object> patchUser(@PathVariable Long userId, @Valid @RequestBody UpdateUserRequest request) {
        return toDto(adminService.updateUser(userId, request));
    }

    @DeleteMapping("/rides/{rideId}")
    public void deleteRide(@PathVariable Long rideId) {
        rideService.adminDeleteRide(rideId);
    }

    private Map<String, Object> toDto(UserEntity user) {
        boolean hasLocal = user.getPasswordHash() != null;
        boolean effectiveMustChange = hasLocal && user.isMustChangePassword();
        return Map.of(
            "id", user.getId(),
            "email", user.getEmail(),
            "fullName", user.getFullName(),
            "role", user.getRole().name(),
            "mustChangePassword", effectiveMustChange,
            "hasLocalPassword", hasLocal,
            "enabled", user.isEnabled(),
            "phone", user.getPhone() == null ? "" : user.getPhone(),
            "vehicleNote", user.getVehicleNote() == null ? "" : user.getVehicleNote()
        );
    }
}
