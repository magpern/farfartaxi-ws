package com.farfartaxi.backend.places;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** SL journey planner stop-finder: stops, addresses and POIs in Stockholm county. */
@Component
public class SlPlaceProvider implements PlaceProvider {
    public static final String NAME = "SL";

    private final String baseUrl;
    private final Duration timeout;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Cache<String, List<ProviderPlace>> cache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(10)).maximumSize(2000).build();

    public SlPlaceProvider(
            @Value("${app.places.sl.journeyplanner-base-url:https://journeyplanner.integration.sl.se}") String baseUrl,
            @Value("${app.places.sl.timeout-ms:3000}") long timeoutMs) {
        this.baseUrl = baseUrl.trim().replaceAll("/+$", "");
        this.timeout = Duration.ofMillis(Math.max(50, timeoutMs));
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    @Override
    public String name() {
        return NAME;
    }

    /** Drops all cached results (used by tests and operational resets). */
    public void clearCache() {
        cache.invalidateAll();
    }

    @Override
    public boolean isCached(String normalizedQuery) {
        return cache.getIfPresent(normalizedQuery) != null;
    }

    @Override
    public List<ProviderPlace> search(String normalizedQuery) throws PlaceProviderException {
        List<ProviderPlace> hit = cache.getIfPresent(normalizedQuery);
        if (hit != null) {
            return hit;
        }
        try {
            URI uri = URI.create(baseUrl + "/v2/stop-finder?name_sf="
                + URLEncoder.encode(normalizedQuery, StandardCharsets.UTF_8) + "&type_sf=any&any_obj_filter_sf=46");
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("Accept", "application/json").header("User-Agent", "FarfartaxiBackend/1.0").GET().build();
            HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                throw new PlaceProviderException("SL stop-finder HTTP " + res.statusCode(), null);
            }
            List<ProviderPlace> parsed = parse(mapper.readTree(res.body()));
            cache.put(normalizedQuery, parsed);
            return parsed;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PlaceProviderException("SL stop-finder interrupted", e);
        } catch (PlaceProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new PlaceProviderException("SL stop-finder failed: " + e, e);
        }
    }

    List<ProviderPlace> parse(JsonNode root) {
        List<ProviderPlace> out = new ArrayList<>();
        JsonNode locations = root == null ? null : root.get("locations");
        if (locations == null || !locations.isArray()) {
            return out;
        }
        for (JsonNode loc : locations) {
            ProviderPlace p = toPlace(loc);
            if (p != null) {
                out.add(p);
            }
        }
        return out;
    }

    private ProviderPlace toPlace(JsonNode loc) {
        String type = text(loc, "type");
        String kind = switch (type == null ? "" : type) {
            case "stop" -> "STOP";
            case "singlehouse", "street", "address" -> "ADDRESS";
            case "poi" -> "POI";
            default -> null;
        };
        JsonNode coord = loc.get("coord");
        if (kind == null || coord == null || !coord.isArray() || coord.size() < 2
            || !coord.get(0).isNumber() || !coord.get(1).isNumber()) {
            return null;
        }
        double lat = coord.get(0).asDouble();
        double lon = coord.get(1).asDouble();
        String full = text(loc, "name");
        String disassembled = text(loc, "disassembledName");
        String area = null;
        JsonNode parent = loc.get("parent");
        if (parent != null) {
            area = text(parent, "name");
        }
        String name = disassembled;
        if (name == null && full != null) {
            int comma = full.indexOf(", ");
            name = comma > 0 ? full.substring(comma + 2) : full;
            if (area == null && comma > 0) {
                area = full.substring(0, comma);
            }
        }
        if ("ADDRESS".equals(kind)) {
            String street = text(loc, "streetName");
            String no = text(loc, "buildingNumber");
            if (street != null) {
                name = no == null ? street : street + " " + no;
            }
        }
        if (name == null) {
            return null;
        }
        // "Järfälla, McDonalds" style names put the locality first; don't repeat it as the area
        if (area != null && area.equals(name)) {
            area = null;
        }
        String formatted = switch (kind) {
            case "STOP" -> area == null ? name + " (hållplats)" : name + " — " + area + " (hållplats)";
            default -> area == null ? name : name + ", " + area;
        };
        double mq = loc.hasNonNull("matchQuality") ? Math.max(0, Math.min(1, loc.get("matchQuality").asDouble() / 1000.0)) : 0.5;
        String id = text(loc, "id");
        return new ProviderPlace(NAME, id, kind, name, area, formatted, lat, lon, mq);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull() || !v.isValueNode()) {
            return null;
        }
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }
}
