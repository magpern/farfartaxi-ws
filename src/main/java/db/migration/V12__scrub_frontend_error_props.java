package db.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.regex.Pattern;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Privacy: frontend_error events no longer carry free-text exception messages. Rewrites the stored rows so that
 * {@code message} is removed (and a legacy {@code source} that is not a short component token is dropped too).
 * Java rather than SQL so it behaves the same on PostgreSQL and H2.
 */
public class V12__scrub_frontend_error_props extends BaseJavaMigration {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern SOURCE = Pattern.compile("[A-Za-z0-9_.-]{1,40}");

    @Override
    public void migrate(Context context) throws Exception {
        Connection c = context.getConnection();
        try (Statement select = c.createStatement();
             ResultSet rs = select.executeQuery("SELECT id, props FROM app_events WHERE name = 'frontend_error' AND props IS NOT NULL");
             PreparedStatement update = c.prepareStatement("UPDATE app_events SET props = ? WHERE id = ?")) {
            int pending = 0;
            while (rs.next()) {
                String old = rs.getString(2);
                String scrubbed = scrub(old);
                if (java.util.Objects.equals(old, scrubbed)) {
                    continue;
                }
                update.setString(1, scrubbed);
                update.setLong(2, rs.getLong(1));
                update.addBatch();
                if (++pending % 500 == 0) {
                    update.executeBatch();
                }
            }
            update.executeBatch();
        }
    }

    /** @return the props JSON without message (and without a non-token source), or null when nothing is left. */
    public static String scrub(String json) {
        try {
            JsonNode n = MAPPER.readTree(json);
            if (n == null || !n.isObject()) {
                return null;
            }
            ObjectNode o = ((ObjectNode) n).deepCopy();
            o.remove("message");
            JsonNode src = o.get("source");
            if (src != null && !(src.isTextual() && SOURCE.matcher(src.asText()).matches())) {
                o.remove("source");
            }
            return o.isEmpty() ? null : o.toString();
        } catch (Exception e) {
            return null; // unparsable props are not worth keeping
        }
    }
}
