package com.farfartaxi.backend.places;

import com.farfartaxi.backend.api.dto.PlaceDtos.PlaceResult;
import com.farfartaxi.backend.api.dto.PlaceDtos.PlaceSearchResponse;
import com.farfartaxi.backend.model.RideEntity;
import com.farfartaxi.backend.model.SavedPlaceEntity;
import com.farfartaxi.backend.model.UserEntity;
import com.farfartaxi.backend.observability.AppMetrics;
import com.farfartaxi.backend.repo.RideRepository;
import com.farfartaxi.backend.repo.SavedPlaceRepository;
import com.farfartaxi.backend.service.AppException;
import com.farfartaxi.backend.service.Geo;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class PlaceSearchService {
    private static final Logger log = LoggerFactory.getLogger(PlaceSearchService.class);
    public static final int DEFAULT_LIMIT = 8;
    static final int MAX_LIMIT = 50;
    static final int MAX_PER_PERSONAL_GROUP = 3;
    static final double GPS_MAX_ACCURACY_M = 1000;
    static final double DEDUPE_METERS = 50;

    private final PlaceProvider provider;
    private final SavedPlaceRepository savedPlaces;
    private final RideRepository rides;
    private final PlaceSelectionService selections;
    private final AppMetrics metrics;
    private final Clock clock;
    private final double defaultLat;
    private final double defaultLon;

    public PlaceSearchService(PlaceProvider provider, SavedPlaceRepository savedPlaces, RideRepository rides,
                              PlaceSelectionService selections, AppMetrics metrics, Clock clock,
                              @Value("${app.places.default-center:59.4235,17.8350}") String defaultCenter) {
        this.provider = provider;
        this.savedPlaces = savedPlaces;
        this.rides = rides;
        this.selections = selections;
        this.metrics = metrics;
        this.clock = clock;
        String[] parts = defaultCenter.split(",");
        this.defaultLat = Double.parseDouble(parts[0].trim());
        this.defaultLon = Double.parseDouble(parts[1].trim());
    }

    record Context(String name, double lat, double lon) {
    }

    private record Scored(ProviderPlace place, double score, double boost, double distM) {
    }

    public PlaceSearchResponse search(UserEntity user, String q, Double lat, Double lon, Double accuracy,
                                      Double pickupLat, Double pickupLon, Integer limitParam) {
        if (q != null && q.length() > 100) {
            throw new AppException(HttpStatus.BAD_REQUEST, "q too long");
        }
        int limit = limitParam == null ? DEFAULT_LIMIT : Math.clamp(limitParam, 1, MAX_LIMIT);
        List<SavedPlaceEntity> saved = savedPlaces.findByUserIdOrderBySortOrderAscLabelAsc(user.getId());
        Context ctx = context(saved, lat, lon, accuracy, pickupLat, pickupLon);
        String nq = PlaceNormalizer.normalize(q);
        if (nq.length() < 2) {
            return new PlaceSearchResponse(List.of(), false, ctx.name());
        }
        long t0 = System.nanoTime();

        // 1. favorites and recents
        List<PlaceResult> personal = new ArrayList<>();
        int favs = 0;
        for (SavedPlaceEntity s : saved) {
            if (favs < MAX_PER_PERSONAL_GROUP && PlaceNormalizer.tokenPrefixMatch(nq, s.getLabel() + " " + s.getAddress())) {
                personal.add(withDistance(new PlaceResult("FAVORITE", String.valueOf(s.getId()), "FAVORITE", s.getLabel(), null,
                    s.getAddress(), s.getLat(), s.getLon(), null), ctx));
                favs++;
            }
        }
        personal.addAll(recents(user, nq, ctx));

        // 2. provider results
        List<ProviderPlace> found = List.of();
        String outcome;
        boolean cached = provider.isCached(nq);
        try {
            found = provider.search(nq);
            outcome = found.isEmpty() ? "empty" : "ok";
            metrics.placesCache(provider.name(), cached);
        } catch (PlaceProvider.PlaceProviderException e) {
            outcome = "error";
            log.warn("Place provider {} failed: {}", provider.name(), e.getMessage());
        } catch (RuntimeException e) {
            outcome = "error";
            log.warn("Place provider {} failed unexpectedly: {}", provider.name(), e.toString());
        }
        Map<String, Double> boosts = found.isEmpty() ? Map.of() : selections.boosts(user, nq);
        List<Scored> scored = new ArrayList<>();
        for (ProviderPlace p : found) {
            double distM = Geo.haversineMeters(ctx.lat(), ctx.lon(), p.lat(), p.lon());
            double boost = boosts.getOrDefault(PlaceSelectionService.key(p.provider(), p.providerPlaceId()), 0.0);
            scored.add(new Scored(p, p.matchQuality() * distanceFactor(distM) + boost, boost, distM));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed().thenComparingDouble(Scored::distM));

        // 3. merge + dedupe (favorites/recents win)
        List<PlaceResult> merged = new ArrayList<>();
        for (PlaceResult r : personal) {
            addIfNew(merged, r);
        }
        boolean moreRequested = limit > DEFAULT_LIMIT;
        List<PlaceResult> far = new ArrayList<>();
        for (Scored s : scored) {
            ProviderPlace p = s.place();
            PlaceResult r = new PlaceResult(p.provider(), p.providerPlaceId(), p.kind(), p.name(), p.area(), p.formattedAddress(),
                p.lat(), p.lon(), round2(s.distM() / 1000.0));
            if (s.distM() > 100_000 && s.boost() <= 0 && !moreRequested) {
                far.add(r); // only after "Visa fler" (or when nothing else matches)
            } else {
                addIfNew(merged, r);
            }
        }
        int farHidden = 0;
        if (merged.isEmpty()) {
            far.forEach(r -> addIfNew(merged, r));
        } else {
            farHidden = far.size();
        }
        boolean hasMore = merged.size() > limit || farHidden > 0;
        List<PlaceResult> out = merged.size() > limit ? List.copyOf(merged.subList(0, limit)) : merged;
        metrics.placesSearch(provider.name(), outcome, System.nanoTime() - t0);
        return new PlaceSearchResponse(out, hasMore, ctx.name());
    }

    private List<PlaceResult> recents(UserEntity user, String nq, Context ctx) {
        List<PlaceResult> out = new ArrayList<>();
        List<RideEntity> list = rides.findRecentForPlaces(user.getId(), user.isTest(),
            clock.instant().minus(Duration.ofDays(90)), PageRequest.of(0, 200));
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (RideEntity r : list) {
            String[][] ends = {{r.getFromAddress(), String.valueOf(r.getFromLat()), String.valueOf(r.getFromLon())},
                {r.getToAddress(), String.valueOf(r.getToLat()), String.valueOf(r.getToLon())}};
            for (String[] e : ends) {
                String addr = e[0] == null ? "" : e[0].trim();
                if (addr.isEmpty() || out.size() >= MAX_PER_PERSONAL_GROUP
                    || !PlaceNormalizer.tokenPrefixMatch(nq, addr) || !seen.add(PlaceNormalizer.normalize(addr))) {
                    continue;
                }
                int comma = addr.indexOf(", ");
                String name = comma > 0 ? addr.substring(0, comma) : addr;
                String area = comma > 0 ? addr.substring(comma + 2) : null;
                out.add(withDistance(new PlaceResult("RECENT", null, "RECENT", name, area, addr,
                    Double.parseDouble(e[1]), Double.parseDouble(e[2]), null), ctx));
            }
        }
        return out;
    }

    Context context(List<SavedPlaceEntity> saved, Double lat, Double lon, Double accuracy, Double pickupLat, Double pickupLon) {
        if (validPoint(lat, lon) && (accuracy == null || accuracy < GPS_MAX_ACCURACY_M)) {
            return new Context("GPS", lat, lon);
        }
        if (validPoint(pickupLat, pickupLon)) {
            return new Context("PICKUP", pickupLat, pickupLon);
        }
        for (SavedPlaceEntity s : saved) {
            String l = s.getLabel() == null ? "" : s.getLabel().trim().toLowerCase(Locale.ROOT);
            if (l.equals("hem") || l.equals("home") || l.equals("hemma")) {
                return new Context("HOME", s.getLat(), s.getLon());
            }
        }
        return new Context("DEFAULT", defaultLat, defaultLon);
    }

    private static boolean validPoint(Double lat, Double lon) {
        return lat != null && lon != null && Double.isFinite(lat) && Double.isFinite(lon)
            && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
    }

    static double distanceFactor(double meters) {
        double km = meters / 1000.0;
        if (km <= 20) {
            return 1.0;
        }
        if (km <= 50) {
            return 0.7;
        }
        if (km <= 100) {
            return 0.4;
        }
        return 0.1;
    }

    private PlaceResult withDistance(PlaceResult r, Context ctx) {
        return r.withDistanceKm(round2(Geo.haversineMeters(ctx.lat(), ctx.lon(), r.lat(), r.lon()) / 1000.0));
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static void addIfNew(List<PlaceResult> list, PlaceResult r) {
        String n = PlaceNormalizer.normalize(r.name());
        for (PlaceResult e : list) {
            if (PlaceNormalizer.normalize(e.name()).equals(n)
                && Geo.haversineMeters(e.lat(), e.lon(), r.lat(), r.lon()) <= DEDUPE_METERS) {
                return;
            }
        }
        list.add(r);
    }
}
