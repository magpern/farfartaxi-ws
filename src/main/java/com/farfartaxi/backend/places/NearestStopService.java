package com.farfartaxi.backend.places;

import com.farfartaxi.backend.api.dto.PlaceDtos.NearestStopResponse;
import com.farfartaxi.backend.observability.AppMetrics;
import com.farfartaxi.backend.service.Geo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** In-memory SL stop list (SL Transport /v1/sites) with a linear haversine nearest lookup. */
@Service
public class NearestStopService {
    private static final Logger log = LoggerFactory.getLogger(NearestStopService.class);
    static final double MAX_DISTANCE_M = 2000;

    record Site(String id, String name, String area, double lat, double lon) {
    }

    private final String baseUrl;
    private final boolean loadEnabled;
    private final AppMetrics metrics;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    /** null until the first successful load. */
    private volatile List<Site> sites;

    public NearestStopService(
            @Value("${app.places.sl.transport-base-url:https://transport.integration.sl.se}") String baseUrl,
            @Value("${app.places.sites.load-on-startup:true}") boolean loadEnabled,
            AppMetrics metrics) {
        this.baseUrl = baseUrl.trim().replaceAll("/+$", "");
        this.loadEnabled = loadEnabled;
        this.metrics = metrics;
    }

    @EventListener(ApplicationReadyEvent.class)
    void loadAtStartup() {
        if (loadEnabled) {
            CompletableFuture.runAsync(this::reload);
        }
    }

    /** Until the first successful load, retry every 15 minutes (non-blocking: the scheduler thread does the work). */
    @Scheduled(fixedDelayString = "${app.places.sites.retry-interval-ms:900000}",
        initialDelayString = "${app.places.sites.retry-interval-ms:900000}")
    public void retryUntilLoaded() {
        if (loadEnabled && sites == null) {
            reload();
        }
    }

    @Scheduled(cron = "0 10 4 * * *", zone = "Europe/Stockholm")
    void nightlyReload() {
        if (loadEnabled) {
            reload();
        }
    }

    /** Fetches and swaps the stop list; on failure the previous list (if any) is kept. */
    public boolean reload() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/sites")).timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json").header("User-Agent", "FarfartaxiBackend/1.0").GET().build();
            HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                log.warn("SL sites HTTP {}", res.statusCode());
                return false;
            }
            JsonNode root = mapper.readTree(res.body());
            if (!root.isArray()) {
                log.warn("SL sites: unexpected payload");
                return false;
            }
            List<Site> list = new ArrayList<>(root.size());
            for (JsonNode n : root) {
                if (n.hasNonNull("lat") && n.hasNonNull("lon") && n.hasNonNull("name") && n.hasNonNull("gid")) {
                    String note = n.hasNonNull("note") ? n.get("note").asText().trim() : "";
                    list.add(new Site(n.get("gid").asText(), n.get("name").asText().trim(),
                        note.isEmpty() ? null : note, n.get("lat").asDouble(), n.get("lon").asDouble()));
                }
            }
            if (list.isEmpty()) {
                log.warn("SL sites: empty list, keeping previous");
                return false;
            }
            this.sites = List.copyOf(list);
            log.info("Loaded {} SL sites", list.size());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.warn("SL sites load failed: {}", e.toString());
            return false;
        }
    }

    public Optional<NearestStopResponse> nearest(double lat, double lon) {
        long t0 = System.nanoTime();
        List<Site> list = sites;
        Site best = null;
        double bestM = Double.MAX_VALUE;
        if (list != null) {
            for (Site s : list) {
                double d = Geo.haversineMeters(lat, lon, s.lat(), s.lon());
                if (d < bestM) {
                    bestM = d;
                    best = s;
                }
            }
        }
        Optional<NearestStopResponse> out = best != null && bestM <= MAX_DISTANCE_M
            ? Optional.of(new NearestStopResponse(best.name(), best.area(), best.lat(), best.lon(), (int) Math.round(bestM), best.id()))
            : Optional.empty();
        metrics.placesNearestStop(list == null ? "unloaded" : out.isPresent() ? "ok" : "empty", System.nanoTime() - t0);
        return out;
    }
}
