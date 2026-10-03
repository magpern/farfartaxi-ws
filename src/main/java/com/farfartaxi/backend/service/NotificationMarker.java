package com.farfartaxi.backend.service;

import com.farfartaxi.backend.repo.RideNotificationSentRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Once-only guard over ride_notifications_sent that survives concurrent callers: a unique violation means "already
 * sent". The insert runs inside a JDBC savepoint so the failure does not abort the caller's transaction (PostgreSQL).
 */
@Component
public class NotificationMarker {
    private final RideNotificationSentRepository sent;
    private final JdbcTemplate jdbc;

    public NotificationMarker(RideNotificationSentRepository sent, JdbcTemplate jdbc) {
        this.sent = sent;
        this.jdbc = jdbc;
    }

    /** True when this call recorded (rideId, kind); false when it already existed, also under a race. */
    public boolean markOnce(Long rideId, String kind, Instant now) {
        if (sent.existsByRideIdAndKind(rideId, kind)) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(jdbc.execute((Connection c) -> insert(c, rideId, kind, now)));
        } catch (DataAccessException e) {
            if (e instanceof org.springframework.dao.DataIntegrityViolationException) {
                return false;
            }
            throw e;
        }
    }

    private static boolean insert(Connection c, Long rideId, String kind, Instant now) throws java.sql.SQLException {
        Savepoint sp = c.getAutoCommit() ? null : c.setSavepoint();
        try (PreparedStatement ps = c.prepareStatement(
            "insert into ride_notifications_sent (ride_id, kind, sent_at) values (?, ?, ?)")) {
            ps.setLong(1, rideId);
            ps.setString(2, kind);
            ps.setTimestamp(3, Timestamp.from(now));
            ps.executeUpdate();
            return true;
        } catch (java.sql.SQLException e) {
            if (sp != null) {
                c.rollback(sp);
            }
            if (e.getSQLState() != null && e.getSQLState().startsWith("23")) {
                return false; // integrity constraint violation: already recorded
            }
            throw e;
        }
    }
}
