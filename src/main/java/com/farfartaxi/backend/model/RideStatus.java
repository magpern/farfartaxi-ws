package com.farfartaxi.backend.model;

import java.util.EnumSet;
import java.util.Set;

public enum RideStatus {
    REQUESTED,
    ACCEPTED,
    EN_ROUTE,
    ARRIVED,
    PICKED_UP,
    COMPLETED,
    CANCELLED,
    /** Nobody accepted in time (or everybody declined). Recoverable: keep waiting, edit the time, or cancel. */
    NO_DRIVER;

    /** Statuses in which a driver is assigned and the ride is being served. */
    public static final Set<RideStatus> ASSIGNED = EnumSet.of(ACCEPTED, EN_ROUTE, ARRIVED, PICKED_UP);
    /** Statuses that are not final for the passenger (still on their home screen). */
    public static final Set<RideStatus> ACTIVE = EnumSet.of(REQUESTED, NO_DRIVER, ACCEPTED, EN_ROUTE, ARRIVED, PICKED_UP);
}
