package com.farfartaxi.backend.service;

import com.farfartaxi.backend.model.AppEventEntity;
import com.farfartaxi.backend.model.UserEntity;
import com.farfartaxi.backend.observability.AppMetrics;
import com.farfartaxi.backend.repo.AppEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Validates and stores first-party product telemetry (M8). Only allowlisted event names and allowlisted props (keys and
 * scalar types) are stored; anything that looks like an address, coordinate or contact detail rejects the whole event.
 * Props are never logged.
 */
@Service
public class TelemetryService {
    public static final int MAX_BODY_BYTES = 32 * 1024;
    public static final int MAX_EVENTS = 50;
    public static final int MAX_PROPS_JSON = 1024;
    public static final int RATE_LIMIT_PER_HOUR = 600;
    private static final Duration RATE_WINDOW = Duration.ofHours(1);

    /** Prop value types. */
    private sealed interface Spec permits IntSpec, StrSpec, EnumSpec {
    }

    private record IntSpec() implements Spec {
    }

    private record StrSpec(int max) implements Spec {
    }

    private record EnumSpec(Set<String> values) implements Spec {
    }

    private static final Spec INT = new IntSpec();
    private static final Spec KIND = new EnumSpec(Set.of("NOW", "SCHEDULED"));
    private static final Spec SOURCE = new EnumSpec(Set.of("home", "search", "favorite", "recent", "rebook"));

    private static final Map<String, Spec> SEARCH = Map.of(
        "queryLength", INT, "provider", new StrSpec(20), "kind", new StrSpec(20), "rank", INT, "latencyMs", INT);
    private static final Map<String, Spec> BOOKING = Map.of("kind", KIND, "source", SOURCE);
    private static final Map<String, Spec> RIDE = Map.of("kind", KIND, "status", new StrSpec(20));

    /** The event name allowlist, each with the allowlist of its props. */
    static final Map<String, Map<String, Spec>> ALLOWED = Map.of(
        "booking_started", BOOKING,
        "booking_created", BOOKING,
        "search_started", SEARCH,
        "search_result_selected", SEARCH,
        "search_empty", SEARCH,
        "ride_accepted", RIDE,
        "ride_cancelled", RIDE,
        "push_permission", Map.of("state", new EnumSpec(Set.of("granted", "denied", "default"))),
        "push_opened", Map.of("kind", new StrSpec(40)),
        "frontend_error", Map.of("message", new StrSpec(200), "source", new StrSpec(120), "line", INT));

    private static final Set<String> SENSITIVE_TOKENS = Set.of(
        "lat", "lon", "lng", "latitude", "longitude", "coord", "coords", "coordinate", "coordinates", "name", "street", "position");
    private static final Pattern SENSITIVE_SUBSTRING = Pattern.compile("address|email|phone|mail|adress");
    private static final Pattern CAMEL_BOUNDARY = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|[^A-Za-z0-9]+");
    private static final Pattern URL_QUERY = Pattern.compile("(https?://[^\\s?#\"'<>]*)[?#][^\\s\"'<>]*");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");
    private static final Pattern COORD_PAIR = Pattern.compile("-?\\d{1,3}\\.\\d{4,}\\s*[,;/ ]\\s*-?\\d{1,3}\\.\\d{4,}");
    private static final Pattern DECIMAL_ONLY = Pattern.compile("\\s*-?\\d+\\.\\d{4,}\\s*");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}]");
    private static final Pattern SESSION_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private final AppEventRepository repository;
    private final AppMetrics metrics;
    private final Clock clock;
    private final Map<Long, Window> windows = new ConcurrentHashMap<>();

    public TelemetryService(AppEventRepository repository, AppMetrics metrics, Clock clock) {
        this.repository = repository;
        this.metrics = metrics;
        this.clock = clock;
    }

    public record Result(int accepted, int dropped) {
    }

    private static final class Window {
        Instant start;
        int count;
    }

    public Result ingest(String body, UserEntity user) {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_JSON", "Invalid JSON");
        }
        if (root == null || !root.isObject() || !root.has("events") || !root.get("events").isArray()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_BODY", "Expected {sessionId, events: []}");
        }
        JsonNode events = root.get("events");
        if (events.size() > MAX_EVENTS) {
            throw new AppException(HttpStatus.BAD_REQUEST, "TOO_MANY_EVENTS", "At most " + MAX_EVENTS + " events per request");
        }
        String sessionId = root.path("sessionId").isTextual() && SESSION_ID.matcher(root.get("sessionId").asText()).matches()
            ? root.get("sessionId").asText() : null;
        boolean test = user.isTest();
        Instant now = clock.instant();
        int quota = reserveQuota(user.getId(), events.size(), now);
        int accepted = 0;
        int seen = 0;
        List<AppEventEntity> toSave = new ArrayList<>();
        for (JsonNode ev : events) {
            if (seen++ >= quota) {
                metrics.appEventDropped("rate_limited", test);
                continue;
            }
            String name = ev.path("name").isTextual() ? ev.get("name").asText() : null;
            Map<String, Spec> allowed = name == null ? null : ALLOWED.get(name);
            if (allowed == null) {
                metrics.appEventDropped("unknown_name", test);
                continue;
            }
            ObjectNode props = sanitizeProps(ev.get("props"), allowed);
            if (props == null) {
                metrics.appEventDropped("rejected", test);
                continue;
            }
            String json = props.isEmpty() ? null : props.toString();
            if (json != null && json.length() > MAX_PROPS_JSON) {
                metrics.appEventDropped("invalid", test);
                continue;
            }
            AppEventEntity e = new AppEventEntity();
            e.setUserId(user.getId());
            e.setTest(test);
            e.setSessionId(sessionId);
            e.setName(name);
            e.setProps(json);
            e.setClientTs(parseTs(ev.get("ts")));
            e.setCreatedAt(now);
            toSave.add(e);
            metrics.appEvent(name, test);
            accepted++;
        }
        if (!toSave.isEmpty()) {
            repository.saveAll(toSave);
        }
        return new Result(accepted, events.size() - accepted);
    }

    /** @return how many of {@code n} submitted events still fit into the user's hourly budget */
    private int reserveQuota(Long userId, int n, Instant now) {
        if (windows.size() > 10_000) {
            windows.values().removeIf(w -> w.start.plus(RATE_WINDOW).isBefore(now));
        }
        Window w = windows.computeIfAbsent(userId, k -> new Window());
        synchronized (w) {
            if (w.start == null || !now.isBefore(w.start.plus(RATE_WINDOW))) {
                w.start = now;
                w.count = 0;
            }
            int allowed = Math.max(0, Math.min(n, RATE_LIMIT_PER_HOUR - w.count));
            w.count += allowed;
            return allowed;
        }
    }

    /** @return the cleaned props, or null when the event must be rejected (looks like an address/coordinate/contact). */
    private ObjectNode sanitizeProps(JsonNode raw, Map<String, Spec> allowed) {
        ObjectNode out = mapper.createObjectNode();
        if (raw == null || raw.isNull() || raw.isMissingNode()) {
            return out;
        }
        if (!raw.isObject()) {
            return out; // not an object: stored without props
        }
        Map<String, JsonNode> clean = new HashMap<>();
        for (var it = raw.fields(); it.hasNext();) {
            var f = it.next();
            if (sensitiveKey(f.getKey()) || looksLikeLocation(f.getValue())) {
                return null;
            }
            clean.put(f.getKey(), f.getValue());
        }
        for (var entry : allowed.entrySet()) {
            JsonNode v = clean.get(entry.getKey());
            if (v == null) {
                continue;
            }
            switch (entry.getValue()) {
                case IntSpec s -> {
                    if (v.isIntegralNumber() && v.canConvertToInt()) {
                        out.put(entry.getKey(), v.asInt());
                    }
                }
                case EnumSpec s -> {
                    if (v.isTextual() && s.values().contains(v.asText())) {
                        out.put(entry.getKey(), v.asText());
                    }
                }
                case StrSpec s -> {
                    if (v.isTextual()) {
                        String t = sanitizeString(v.asText(), s.max());
                        if (looksLikeLocation(com.fasterxml.jackson.databind.node.TextNode.valueOf(t))) {
                            return null;
                        }
                        out.put(entry.getKey(), t);
                    }
                }
            }
        }
        return out;
    }

    static boolean sensitiveKey(String key) {
        String lower = key.toLowerCase();
        if (SENSITIVE_SUBSTRING.matcher(lower).find()) {
            return true;
        }
        for (String token : CAMEL_BOUNDARY.split(key)) {
            if (SENSITIVE_TOKENS.contains(token.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    static boolean looksLikeLocation(JsonNode v) {
        if (v.isNumber()) {
            BigDecimal d = v.decimalValue().stripTrailingZeros();
            return d.scale() >= 4;
        }
        if (v.isTextual()) {
            String t = v.asText();
            return COORD_PAIR.matcher(t).find() || DECIMAL_ONLY.matcher(t).matches() || EMAIL.matcher(t).find();
        }
        return false;
    }

    static String sanitizeString(String s, int max) {
        String t = URL_QUERY.matcher(s).replaceAll("$1");
        t = CONTROL.matcher(t).replaceAll(" ").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static Instant parseTs(JsonNode ts) {
        try {
            Instant i = null;
            if (ts == null) {
                return null;
            } else if (ts.isIntegralNumber()) {
                i = Instant.ofEpochMilli(ts.asLong());
            } else if (ts.isTextual()) {
                i = Instant.parse(ts.asText());
            }
            if (i != null && i.isAfter(Instant.parse("2000-01-01T00:00:00Z")) && i.isBefore(Instant.parse("2100-01-01T00:00:00Z"))) {
                return i;
            }
        } catch (RuntimeException ignored) {
            // unparsable client timestamp: stored as null
        }
        return null;
    }
}
