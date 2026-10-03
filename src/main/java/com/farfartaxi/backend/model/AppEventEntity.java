package com.farfartaxi.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "app_events")
public class AppEventEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "is_test", nullable = false)
    private boolean test;

    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(nullable = false, length = 40)
    private String name;

    /** JSON object (allowlisted keys, scalar values), at most 1 KB. Never logged. */
    @Column(length = 1024)
    private String props;

    @Column(name = "client_ts")
    private Instant clientTs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public boolean isTest() { return test; }
    public void setTest(boolean test) { this.test = test; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getProps() { return props; }
    public void setProps(String props) { this.props = props; }
    public Instant getClientTs() { return clientTs; }
    public void setClientTs(Instant clientTs) { this.clientTs = clientTs; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
