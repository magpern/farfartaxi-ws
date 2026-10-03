package com.farfartaxi.backend.places;

import com.farfartaxi.backend.api.dto.PlaceDtos.PlaceResult;
import com.farfartaxi.backend.model.RideEntity;
import com.farfartaxi.backend.model.SavedPlaceEntity;
import com.farfartaxi.backend.model.UserEntity;
import com.farfartaxi.backend.repo.RideRepository;
import com.farfartaxi.backend.repo.SavedPlaceRepository;
import com.farfartaxi.backend.service.Geo;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/** Distinct recent pickups/destinations of the caller's own rides (own world), newest first. */
@Service
public class RecentPlacesService {
    public static final int DEFAULT_LIMIT = 6;
    public static final int MAX_LIMIT = 20;
    static final double DEDUPE_METERS = 50;

    private final RideRepository rides;
    private final SavedPlaceRepository savedPlaces;
    private final Clock clock;

    public RecentPlacesService(RideRepository rides, SavedPlaceRepository savedPlaces, Clock clock) {
        this.rides = rides;
        this.savedPlaces = savedPlaces;
        this.clock = clock;
    }

    public List<PlaceResult> recent(UserEntity user, Integer limitParam) {
        int limit = limitParam == null ? DEFAULT_LIMIT : Math.clamp(limitParam, 1, MAX_LIMIT);
        List<SavedPlaceEntity> saved = savedPlaces.findByUserIdOrderBySortOrderAscLabelAsc(user.getId());
        List<RideEntity> list = rides.findRecentForPlaces(user.getId(), user.isTest(),
            clock.instant().minus(Duration.ofDays(90)), PageRequest.of(0, 300));
        List<PlaceResult> out = new ArrayList<>();
        for (RideEntity r : list) {
            Object[][] ends = {{r.getToAddress(), r.getToLat(), r.getToLon()}, {r.getFromAddress(), r.getFromLat(), r.getFromLon()}};
            for (Object[] e : ends) {
                if (out.size() >= limit) {
                    return out;
                }
                String addr = e[0] == null ? "" : ((String) e[0]).trim();
                double lat = ((Number) e[1]).doubleValue();
                double lon = ((Number) e[2]).doubleValue();
                if (addr.isEmpty() || nearSaved(saved, lat, lon) || duplicate(out, addr, lat, lon)) {
                    continue;
                }
                int comma = addr.indexOf(", ");
                String name = comma > 0 ? addr.substring(0, comma) : addr;
                String area = comma > 0 ? addr.substring(comma + 2) : null;
                out.add(new PlaceResult("RECENT", null, "RECENT", name, area, addr, lat, lon, null));
            }
        }
        return out;
    }

    private static boolean nearSaved(List<SavedPlaceEntity> saved, double lat, double lon) {
        return saved.stream().anyMatch(s -> Geo.haversineMeters(s.getLat(), s.getLon(), lat, lon) <= DEDUPE_METERS);
    }

    private static boolean duplicate(List<PlaceResult> out, String addr, double lat, double lon) {
        String n = PlaceNormalizer.normalize(addr);
        return out.stream().anyMatch(p -> Geo.haversineMeters(p.lat(), p.lon(), lat, lon) <= DEDUPE_METERS
            || PlaceNormalizer.normalize(p.formattedAddress()).equals(n));
    }
}
