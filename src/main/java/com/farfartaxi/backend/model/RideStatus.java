package com.farfartaxi.backend.model;

public enum RideStatus {
    PENDING_OPEN,
    ACCEPTED,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED,
    /** Driver declined the open ride; passenger may remove from their list. */
    REJECTED
}
