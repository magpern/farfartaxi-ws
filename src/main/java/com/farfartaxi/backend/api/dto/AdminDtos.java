package com.farfartaxi.backend.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;

public final class AdminDtos {
    private AdminDtos() {
    }

    public record UpdateUserRequest(
        String fullName,
        @Email String email,
        String phone,
        String vehicleNote
    ) {
    }

    public record ForcePasswordResetRequest(@NotNull Boolean mustChangePassword) {
    }
}
