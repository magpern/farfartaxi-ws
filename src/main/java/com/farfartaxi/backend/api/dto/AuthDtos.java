package com.farfartaxi.backend.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {
    private AuthDtos() {
    }

    public record RegisterRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8, max = 100) String password,
        @NotBlank @Size(max = 255) String fullName
    ) {
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    }

    public record AuthResponse(String token, UserView user) {
    }

    public record ForgotPasswordRequest(@NotBlank @Email String email) {
    }

    public record ChangePasswordRequest(@NotBlank String oldPassword, @NotBlank @Size(min = 8) String newPassword) {
    }

    public record UserView(Long id, String email, String fullName, String role, boolean mustChangePassword) {
    }
}
