package com.farfartaxi.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "rides")
public class RideEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "passenger_id")
    private UserEntity passenger;

    @ManyToOne
    @JoinColumn(name = "accepted_by_driver_id")
    private UserEntity acceptedByDriver;

    @Column(name = "from_address", nullable = false)
    private String fromAddress;

    @Column(name = "from_lat", nullable = false)
    private double fromLat;

    @Column(name = "from_lon", nullable = false)
    private double fromLon;

    @Column(name = "to_address", nullable = false)
    private String toAddress;

    @Column(name = "to_lat", nullable = false)
    private double toLat;

    @Column(name = "to_lon", nullable = false)
    private double toLon;

    @Column(name = "waypoints_json")
    private String waypointsJson;

    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RideStatus status;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Column(name = "refusal_comment")
    private String refusalComment;

    @ManyToOne
    @JoinColumn(name = "refusal_driver_id")
    private UserEntity refusalDriver;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "last_driver_lat")
    private Double lastDriverLat;

    @Column(name = "last_driver_lon")
    private Double lastDriverLon;

    @Column(name = "last_location_at")
    private Instant lastLocationAt;

    @Column(name = "eta_minutes")
    private Integer etaMinutes;

    @Column(name = "share_token")
    private String shareToken;

    @Column(name = "share_expires_at")
    private Instant shareExpiresAt;

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

    public UserEntity getPassenger() {
        return passenger;
    }

    public void setPassenger(UserEntity passenger) {
        this.passenger = passenger;
    }

    public UserEntity getAcceptedByDriver() {
        return acceptedByDriver;
    }

    public void setAcceptedByDriver(UserEntity acceptedByDriver) {
        this.acceptedByDriver = acceptedByDriver;
    }

    public String getFromAddress() {
        return fromAddress;
    }

    public void setFromAddress(String fromAddress) {
        this.fromAddress = fromAddress;
    }

    public double getFromLat() {
        return fromLat;
    }

    public void setFromLat(double fromLat) {
        this.fromLat = fromLat;
    }

    public double getFromLon() {
        return fromLon;
    }

    public void setFromLon(double fromLon) {
        this.fromLon = fromLon;
    }

    public String getToAddress() {
        return toAddress;
    }

    public void setToAddress(String toAddress) {
        this.toAddress = toAddress;
    }

    public double getToLat() {
        return toLat;
    }

    public void setToLat(double toLat) {
        this.toLat = toLat;
    }

    public double getToLon() {
        return toLon;
    }

    public void setToLon(double toLon) {
        this.toLon = toLon;
    }

    public String getWaypointsJson() {
        return waypointsJson;
    }

    public void setWaypointsJson(String waypointsJson) {
        this.waypointsJson = waypointsJson;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public void setScheduledAt(Instant scheduledAt) {
        this.scheduledAt = scheduledAt;
    }

    public RideStatus getStatus() {
        return status;
    }

    public void setStatus(RideStatus status) {
        this.status = status;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public void setCancelReason(String cancelReason) {
        this.cancelReason = cancelReason;
    }

    public String getRefusalComment() {
        return refusalComment;
    }

    public void setRefusalComment(String refusalComment) {
        this.refusalComment = refusalComment;
    }

    public UserEntity getRefusalDriver() {
        return refusalDriver;
    }

    public void setRefusalDriver(UserEntity refusalDriver) {
        this.refusalDriver = refusalDriver;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public Double getLastDriverLat() {
        return lastDriverLat;
    }

    public void setLastDriverLat(Double lastDriverLat) {
        this.lastDriverLat = lastDriverLat;
    }

    public Double getLastDriverLon() {
        return lastDriverLon;
    }

    public void setLastDriverLon(Double lastDriverLon) {
        this.lastDriverLon = lastDriverLon;
    }

    public Instant getLastLocationAt() {
        return lastLocationAt;
    }

    public void setLastLocationAt(Instant lastLocationAt) {
        this.lastLocationAt = lastLocationAt;
    }

    public Integer getEtaMinutes() {
        return etaMinutes;
    }

    public void setEtaMinutes(Integer etaMinutes) {
        this.etaMinutes = etaMinutes;
    }

    public String getShareToken() {
        return shareToken;
    }

    public void setShareToken(String shareToken) {
        this.shareToken = shareToken;
    }

    public Instant getShareExpiresAt() {
        return shareExpiresAt;
    }

    public void setShareExpiresAt(Instant shareExpiresAt) {
        this.shareExpiresAt = shareExpiresAt;
    }
}
