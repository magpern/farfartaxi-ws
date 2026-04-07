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

    /** Google Sign-In: JWT credential from <code>google.accounts.id</code> callback. */
    public record GoogleLoginRequest(@NotBlank String credential) {
    }

    public record SetPasswordRequest(@NotBlank @Size(min = 8, max = 100) String newPassword) {
    }

    public record AuthResponse(String token, UserView user) {
    }

    public record ForgotPasswordRequest(@NotBlank @Email String email) {
    }

    public record ChangePasswordRequest(@NotBlank String oldPassword, @NotBlank @Size(min = 8) String newPassword) {
    }

    public record UserView(
        Long id,
        String email,
        String fullName,
        String role,
        /** Only meaningful for accounts with a local password (not Google-only). */
        boolean mustChangePassword,
        boolean hasLocalPassword
    ) {
    }
}
