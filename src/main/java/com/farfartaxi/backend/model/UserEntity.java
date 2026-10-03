package com.farfartaxi.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "users")
public class UserEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "is_test", nullable = false)
    private boolean test;

    /** Optimistic lock: stale saves (e.g. slow password change vs. concurrent Google link) must fail, not overwrite. */
    @Version
    @Column(nullable = false)
    private long version;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "credentials_changed_at")
    private Instant credentialsChangedAt;

    @Column(name = "google_sub")
    private String googleSub;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    private String phone;

    @Column(name = "photo_url")
    private String photoUrl;

    @Column(name = "vehicle_note")
    private String vehicleNote;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false)
    private boolean approved = false;

    /** Driver toggle: affects NOW rides only. */
    @Column(name = "driver_available_now", nullable = false)
    private boolean driverAvailableNow = true;

    /** Inclusive Europe/Stockholm dates during which no offers are made for rides scheduled in the period. */
    @Column(name = "driver_away_from")
    private java.time.LocalDate driverAwayFrom;

    @Column(name = "driver_away_until")
    private java.time.LocalDate driverAwayUntil;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getGoogleSub() {
        return googleSub;
    }

    public void setGoogleSub(String googleSub) {
        this.googleSub = googleSub;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    public void setMustChangePassword(boolean mustChangePassword) {
        this.mustChangePassword = mustChangePassword;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Instant getCredentialsChangedAt() {
        return credentialsChangedAt;
    }

    public void setCredentialsChangedAt(Instant credentialsChangedAt) {
        this.credentialsChangedAt = credentialsChangedAt;
    }

    public boolean isApproved() {
        return approved;
    }

    public void setApproved(boolean approved) {
        this.approved = approved;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getPhotoUrl() {
        return photoUrl;
    }

    public void setPhotoUrl(String photoUrl) {
        this.photoUrl = photoUrl;
    }

    public String getVehicleNote() {
        return vehicleNote;
    }

    public void setVehicleNote(String vehicleNote) {
        this.vehicleNote = vehicleNote;
    }

    public long getVersion() {
        return version;
    }

    public boolean isTest() {
        return test;
    }

    public void setTest(boolean test) {
        this.test = test;
    }

    public boolean isDriverAvailableNow() { return driverAvailableNow; }
    public void setDriverAvailableNow(boolean v) { this.driverAvailableNow = v; }
    public java.time.LocalDate getDriverAwayFrom() { return driverAwayFrom; }
    public void setDriverAwayFrom(java.time.LocalDate d) { this.driverAwayFrom = d; }
    public java.time.LocalDate getDriverAwayUntil() { return driverAwayUntil; }
    public void setDriverAwayUntil(java.time.LocalDate d) { this.driverAwayUntil = d; }

    public boolean isAwayOn(java.time.LocalDate date) {
        return driverAwayFrom != null && driverAwayUntil != null
            && !date.isBefore(driverAwayFrom) && !date.isAfter(driverAwayUntil);
    }
}
