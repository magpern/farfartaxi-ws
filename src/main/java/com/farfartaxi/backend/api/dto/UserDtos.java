package com.farfartaxi.backend.api.dto;

public final class UserDtos {
    private UserDtos() {
    }

    public record BookingUserOption(long id, String fullName, String email) {
    }
}
