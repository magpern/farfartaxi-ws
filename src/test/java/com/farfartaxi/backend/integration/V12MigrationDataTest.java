package com.farfartaxi.backend.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import db.migration.V12__scrub_frontend_error_props;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

/** Runs the real V12 Java migration on H2 against a minimal app_events table. */
class V12MigrationDataTest {
    @Test
    void removesMessageFromStoredFrontendErrorsOnly() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:v12mig;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE app_events (id BIGINT PRIMARY KEY, name VARCHAR(40) NOT NULL, props TEXT)");
            st.execute("INSERT INTO app_events VALUES (1, 'frontend_error', '{\"message\":\"boom for anna@example.com\",\"source\":\"BookingPage\",\"line\":12}')");
            st.execute("INSERT INTO app_events VALUES (2, 'frontend_error', '{\"message\":\"only a message\"}')");
            st.execute("INSERT INTO app_events VALUES (3, 'frontend_error', '{\"message\":\"x\",\"source\":\"/app/x?token=SECRET\",\"line\":3}')");
            st.execute("INSERT INTO app_events VALUES (4, 'frontend_error', NULL)");
            st.execute("INSERT INTO app_events VALUES (5, 'push_opened', '{\"message\":\"keep me\"}')");
            Context ctx = mock(Context.class);
            when(ctx.getConnection()).thenReturn(c);
            new V12__scrub_frontend_error_props().migrate(ctx);
            Map<Long, String> got = new LinkedHashMap<>();
            try (ResultSet rs = st.executeQuery("SELECT id, props FROM app_events ORDER BY id")) {
                while (rs.next()) {
                    got.put(rs.getLong(1), rs.getString(2));
                }
            }
            assertThat(got.get(1L)).isEqualTo("{\"source\":\"BookingPage\",\"line\":12}");
            assertThat(got.get(2L)).isNull();
            assertThat(got.get(3L)).isEqualTo("{\"line\":3}");
            assertThat(got.get(4L)).isNull();
            assertThat(got.get(5L)).contains("keep me");
        }
    }
}
