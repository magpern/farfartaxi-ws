package com.farfartaxi.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Replays the real V8 SQL on H2 (PostgreSQL mode) against a minimal pre-V8 saved_places table. */
class V8MigrationDataTest {
    @Test
    void backfillsHomeKindOncePerUser() throws Exception {
        String sql;
        try (var in = V8MigrationDataTest.class.getResourceAsStream("/db/migration/V8__saved_place_details.sql")) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String noComments = sql.lines().filter(l -> !l.trim().startsWith("--")).collect(Collectors.joining("\n"));
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:v8mig;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE saved_places (id BIGSERIAL PRIMARY KEY, user_id BIGINT NOT NULL, label VARCHAR(128) NOT NULL)");
            st.execute("INSERT INTO saved_places (user_id, label) VALUES (1, 'Hem'), (1, 'home'), (1, 'Skolan'), (2, ' HOME '), (3, 'Hemma hos Lisa')");
            for (String s : noComments.split(";")) {
                if (!s.isBlank()) {
                    st.execute(s.trim());
                }
            }
            Map<Long, String> kinds = new LinkedHashMap<>();
            try (ResultSet rs = st.executeQuery("SELECT id, kind FROM saved_places ORDER BY id")) {
                while (rs.next()) {
                    kinds.put(rs.getLong(1), rs.getString(2));
                }
            }
            assertThat(kinds).containsExactly(Map.entry(1L, "HOME"), Map.entry(2L, "OTHER"), Map.entry(3L, "OTHER"),
                Map.entry(4L, "HOME"), Map.entry(5L, "OTHER"));
        }
    }
}
