package com.farfartaxi.backend.model;

/** Canned quick messages; free text is deliberately not supported. */
public enum MessageCode {
    PASSENGER_OUTSIDE(true, "Jag står utanför"),
    PASSENGER_TWO_MIN(true, "Kommer om 2 min"),
    PASSENGER_CALL_ME(true, "Ring mig"),
    DRIVER_HERE(false, "Jag är här"),
    DRIVER_TWO_MIN(false, "Är där om 2 min"),
    DRIVER_LATE(false, "Blir lite sen");

    private final boolean passenger;
    private final String text;

    MessageCode(boolean passenger, String text) {
        this.passenger = passenger;
        this.text = text;
    }

    public boolean isPassenger() {
        return passenger;
    }

    public String text() {
        return text;
    }
}
