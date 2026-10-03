package com.farfartaxi.backend.model;

public enum OfferStatus {
    OFFERED,
    VIEWED,
    ACCEPTED,
    DECLINED,
    EXPIRED,
    WITHDRAWN;

    /** Offer the driver can still act on. */
    public boolean isOpen() {
        return this == OFFERED || this == VIEWED;
    }
}
