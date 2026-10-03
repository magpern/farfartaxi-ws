package com.farfartaxi.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Per-user notification categories. A missing row means everything is on. */
@Entity
@Table(name = "notification_prefs")
public class NotificationPrefsEntity {
    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "ride_requests", nullable = false)
    private boolean rideRequests = true;

    @Column(name = "ride_updates", nullable = false)
    private boolean rideUpdates = true;

    @Column(nullable = false)
    private boolean reminders = true;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public boolean isRideRequests() { return rideRequests; }
    public void setRideRequests(boolean v) { this.rideRequests = v; }
    public boolean isRideUpdates() { return rideUpdates; }
    public void setRideUpdates(boolean v) { this.rideUpdates = v; }
    public boolean isReminders() { return reminders; }
    public void setReminders(boolean v) { this.reminders = v; }
}
