package com.farfartaxi.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/** A place a user picked for a (normalized) query; {@code score} decays with a 90 day half-life. */
@Entity
@Table(name = "place_selections", uniqueConstraints = @UniqueConstraint(
    columnNames = {"user_id", "normalized_query", "provider", "provider_place_id"}))
public class PlaceSelectionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id")
    private UserEntity user;

    @Column(name = "normalized_query", nullable = false, length = 100)
    private String normalizedQuery;

    @Column(nullable = false, length = 16)
    private String provider;

    @Column(name = "provider_place_id", nullable = false, length = 128)
    private String providerPlaceId;

    @Column(nullable = false, length = 256)
    private String name;

    @Column(nullable = false)
    private double lat;

    @Column(nullable = false)
    private double lon;

    @Column(nullable = false)
    private double score;

    @Column(name = "last_selected_at", nullable = false)
    private Instant lastSelectedAt;

    public Long getId() { return id; }
    public UserEntity getUser() { return user; }
    public void setUser(UserEntity user) { this.user = user; }
    public String getNormalizedQuery() { return normalizedQuery; }
    public void setNormalizedQuery(String normalizedQuery) { this.normalizedQuery = normalizedQuery; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getProviderPlaceId() { return providerPlaceId; }
    public void setProviderPlaceId(String providerPlaceId) { this.providerPlaceId = providerPlaceId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public double getLat() { return lat; }
    public void setLat(double lat) { this.lat = lat; }
    public double getLon() { return lon; }
    public void setLon(double lon) { this.lon = lon; }
    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }
    public Instant getLastSelectedAt() { return lastSelectedAt; }
    public void setLastSelectedAt(Instant lastSelectedAt) { this.lastSelectedAt = lastSelectedAt; }
}
