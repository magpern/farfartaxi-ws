package com.farfartaxi.backend.places;

import com.farfartaxi.backend.api.dto.PlaceDtos.PlaceResult;
import com.farfartaxi.backend.observability.AppMetrics;
import com.farfartaxi.backend.service.AppException;
import com.farfartaxi.backend.service.NominatimProxyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Map-tap lookup: Nominatim reverse mapped to a {@link PlaceResult}. */
@Service
public class ReverseGeocodeService {
    private final NominatimProxyService nominatim;
    private final AppMetrics metrics;
    private final ObjectMapper mapper = new ObjectMapper();

    public ReverseGeocodeService(NominatimProxyService nominatim, AppMetrics metrics) {
        this.nominatim = nominatim;
        this.metrics = metrics;
    }

    /** @return empty when Nominatim has no address there; also on upstream failure (never 502); 429 AppException when over the rate budget */
    public Optional<PlaceResult> reverse(double lat, double lon) {
        long t0 = System.nanoTime();
        String body;
        try {
            body = nominatim.reverse(lat, lon);
        } catch (AppException e) {
            metrics.placesReverse("rate_limited", System.nanoTime() - t0);
            throw e;
        }
        if (body == null) {
            metrics.placesReverse("error", System.nanoTime() - t0);
            return Optional.empty(); // upstream trouble is not "our backend is down": 204, the client just has no label
        }
        Optional<PlaceResult> result = map(body, lat, lon);
        metrics.placesReverse(result.isPresent() ? "ok" : "empty", System.nanoTime() - t0);
        return result;
    }

    Optional<PlaceResult> map(String body, double lat, double lon) {
        try {
            JsonNode n = mapper.readTree(body);
            if (n == null || n.has("error")) {
                return Optional.empty();
            }
            JsonNode a = n.path("address");
            String name = null;
            String road = first(a, "road", "pedestrian", "footway", "path", "cycleway");
            if (road != null) {
                String no = text(a, "house_number");
                name = no == null ? road : road + " " + no;
            }
            if (name == null) {
                name = text(n, "name");
            }
            if (name == null && text(n, "display_name") != null) {
                name = text(n, "display_name").split(",")[0].trim();
            }
            if (name == null || name.isEmpty()) {
                return Optional.empty();
            }
            String area = first(a, "city", "town", "village", "municipality", "suburb", "city_district");
            if (name.equals(area)) {
                area = null;
            }
            double plat = n.hasNonNull("lat") ? Double.parseDouble(n.get("lat").asText()) : lat;
            double plon = n.hasNonNull("lon") ? Double.parseDouble(n.get("lon").asText()) : lon;
            return Optional.of(new PlaceResult("NOMINATIM", text(n, "place_id"), "ADDRESS", name, area,
                area == null ? name : name + ", " + area, plat, plon, null));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String first(JsonNode n, String... fields) {
        for (String f : fields) {
            String v = text(n, f);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.get(f);
        if (v == null || v.isNull() || !v.isValueNode()) {
            return null;
        }
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }
}
