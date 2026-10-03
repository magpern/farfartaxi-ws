package com.farfartaxi.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.farfartaxi.backend.model.RideStatus;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Runs the real V6__ride_model.sql on H2 (PostgreSQL mode) against a minimal pre-V6 schema. The full migration
 * cannot be applied through Flyway on H2 because V1 uses a partial index, so this replays V6 itself: the status
 * data migration plus every DDL statement (catching typos Hibernate's create-drop would never see).
 */
class V6MigrationDataTest {
    private static List<String> statements() throws Exception {
        String sql;
        try (var in = V6MigrationDataTest.class.getResourceAsStream("/db/migration/V6__ride_model.sql")) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String noComments = sql.lines().filter(l -> !l.trim().startsWith("--")).collect(Collectors.joining("\n"));
        List<String> out = new ArrayList<>();
        for (String s : noComments.split(";")) {
            if (!s.isBlank()) {
                out.add(s.trim());
            }
        }
        return out;
    }

    private static void exec(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    @Test
    void v6MapsLegacyStatusesAndAppliesCleanly() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:v6mig;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "")) {
            exec(c, "CREATE TABLE users (id BIGSERIAL PRIMARY KEY, email VARCHAR(255) NOT NULL)");
            exec(c, "CREATE TABLE rides (id BIGSERIAL PRIMARY KEY, passenger_id BIGINT NOT NULL REFERENCES users(id), "
                + "status VARCHAR(32) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW())");
            exec(c, "INSERT INTO users (email) VALUES ('a@b.c')");
            Map<String, String> legacy = new LinkedHashMap<>();
            legacy.put("PENDING_OPEN", "REQUESTED");
            legacy.put("IN_PROGRESS", "EN_ROUTE");
            legacy.put("REJECTED", "NO_DRIVER");
            legacy.put("ACCEPTED", "ACCEPTED");
            legacy.put("COMPLETED", "COMPLETED");
            legacy.put("CANCELLED", "CANCELLED");
            List<String> order = new ArrayList<>(legacy.keySet());
            for (String s : order) {
                exec(c, "INSERT INTO rides (passenger_id, status) VALUES (1, '" + s + "')");
            }

            for (String s : statements()) {
                exec(c, s);
            }

            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT status, kind, version, urgent, client_request_id, requested_at, created_at FROM rides ORDER BY id")) {
                int i = 0;
                while (rs.next()) {
                    String legacyStatus = order.get(i++);
                    assertThat(rs.getString("status")).as(legacyStatus).isEqualTo(legacy.get(legacyStatus));
                    assertThat(rs.getString("kind")).isEqualTo("SCHEDULED");
                    assertThat(rs.getLong("version")).isZero();
                    assertThat(rs.getBoolean("urgent")).isFalse();
                    assertThat(rs.getString("client_request_id")).isNull();
                    assertThat(rs.getTimestamp("requested_at")).isEqualTo(rs.getTimestamp("created_at"));
                }
                assertThat(i).isEqualTo(order.size());
            }
            // every migrated value is a valid enum constant (Hibernate would fail to load the row otherwise)
            Set<String> valid = java.util.Arrays.stream(RideStatus.values()).map(Enum::name).collect(Collectors.toSet());
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT DISTINCT status FROM rides")) {
                while (rs.next()) {
                    assertThat(valid).contains(rs.getString(1));
                }
            }
            // new tables and constraints work
            exec(c, "INSERT INTO ride_offers (ride_id, driver_id, status, offered_at) VALUES (1, 1, 'OFFERED', NOW())");
            assertThatThrownBy(() -> exec(c, "INSERT INTO ride_offers (ride_id, driver_id, status, offered_at) VALUES (1, 1, 'OFFERED', NOW())"))
                .hasMessageContaining("Unique");
            exec(c, "INSERT INTO ride_notifications_sent (ride_id, kind, sent_at) VALUES (1, 'NOW_REPUSH', NOW())");
            assertThatThrownBy(() -> exec(c, "INSERT INTO ride_notifications_sent (ride_id, kind, sent_at) VALUES (1, 'NOW_REPUSH', NOW())"))
                .hasMessageContaining("Unique");
            exec(c, "INSERT INTO ride_messages (ride_id, sender_id, code, text, created_at) VALUES (1, 1, 'DRIVER_HERE', 'x', NOW())");
            exec(c, "UPDATE rides SET client_request_id = 'k' WHERE id = 1");
            assertThatThrownBy(() -> exec(c, "UPDATE rides SET client_request_id = 'k' WHERE id = 2"))
                .hasMessageContaining("Unique"); // same passenger + same key
            exec(c, "ALTER TABLE users ADD COLUMN IF NOT EXISTS probe INT"); // sanity: connection still healthy
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT driver_available_now, driver_away_from, driver_away_until FROM users")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean(1)).isTrue();
                assertThat(rs.getDate(2)).isNull();
            }
        }
    }
}
