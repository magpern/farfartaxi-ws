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
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "rides", uniqueConstraints = @UniqueConstraint(columnNames = {"passenger_id", "client_request_id"}))
public class RideEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "is_test", nullable = false)
    private boolean test;

    /** Optimistic lock: two concurrent transitions (e.g. two accepts) cannot both win. */
    @Version
    @Column(nullable = false)
    private long version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RideKind kind = RideKind.SCHEDULED;

    @Column(name = "pickup_note")
    private String pickupNote;

    @Column(nullable = false)
    private boolean urgent;

    @Column(name = "client_request_id")
    private String clientRequestId;

    /** Start of the current waiting window (NOW rides: timeouts count from here; SCHEDULED: booking time). */
    @Column(name = "requested_at")
    private Instant requestedAt;

    @Column(name = "arrived_at")
    private Instant arrivedAt;

    @Column(name = "picked_up_at")
    private Instant pickedUpAt;

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

    @Column(name = "last_location_accuracy_m")
    private Double lastLocationAccuracyM;

    /** PICKUP or DESTINATION: what {@link #etaMinutes} is measured to. */
    @Column(name = "eta_target", length = 16)
    private String etaTarget;

    @Column(name = "eta_computed_at")
    private Instant etaComputedAt;

    /** Driver position used for the last ETA computation. */
    @Column(name = "eta_lat")
    private Double etaLat;

    @Column(name = "eta_lon")
    private Double etaLon;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "share_revoked_at")
    private Instant shareRevokedAt;

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

    public boolean isTest() {
        return test;
    }

    public void setTest(boolean test) {
        this.test = test;
    }

    public long getVersion() { return version; }
    public RideKind getKind() { return kind; }
    public void setKind(RideKind kind) { this.kind = kind; }
    public String getPickupNote() { return pickupNote; }
    public void setPickupNote(String pickupNote) { this.pickupNote = pickupNote; }
    public boolean isUrgent() { return urgent; }
    public void setUrgent(boolean urgent) { this.urgent = urgent; }
    public String getClientRequestId() { return clientRequestId; }
    public void setClientRequestId(String clientRequestId) { this.clientRequestId = clientRequestId; }
    public Instant getRequestedAt() { return requestedAt != null ? requestedAt : createdAt; }
    public void setRequestedAt(Instant requestedAt) { this.requestedAt = requestedAt; }
    public Instant getArrivedAt() { return arrivedAt; }
    public void setArrivedAt(Instant arrivedAt) { this.arrivedAt = arrivedAt; }
    public Instant getPickedUpAt() { return pickedUpAt; }
    public void setPickedUpAt(Instant pickedUpAt) { this.pickedUpAt = pickedUpAt; }
    public Double getLastLocationAccuracyM() { return lastLocationAccuracyM; }
    public void setLastLocationAccuracyM(Double v) { this.lastLocationAccuracyM = v; }
    public String getEtaTarget() { return etaTarget; }
    public void setEtaTarget(String etaTarget) { this.etaTarget = etaTarget; }
    public Instant getEtaComputedAt() { return etaComputedAt; }
    public void setEtaComputedAt(Instant etaComputedAt) { this.etaComputedAt = etaComputedAt; }
    public Double getEtaLat() { return etaLat; }
    public void setEtaLat(Double etaLat) { this.etaLat = etaLat; }
    public Double getEtaLon() { return etaLon; }
    public void setEtaLon(Double etaLon) { this.etaLon = etaLon; }
    public Instant getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(Instant cancelledAt) { this.cancelledAt = cancelledAt; }
    public Instant getShareRevokedAt() { return shareRevokedAt; }
    public void setShareRevokedAt(Instant shareRevokedAt) { this.shareRevokedAt = shareRevokedAt; }

    /** When the ride ended (COMPLETED: completed_at, CANCELLED: cancelled_at, falling back to updated_at); null while active. */
    public Instant endedAt() {
        if (status == RideStatus.COMPLETED) {
            return completedAt != null ? completedAt : updatedAt;
        }
        if (status == RideStatus.CANCELLED) {
            return cancelledAt != null ? cancelledAt : updatedAt;
        }
        return null;
    }
}
