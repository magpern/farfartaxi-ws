package com.farfartaxi.backend.places;

import com.farfartaxi.backend.api.dto.PlaceDtos.PlaceSelectionRequest;
import com.farfartaxi.backend.model.PlaceSelectionEntity;
import com.farfartaxi.backend.model.UserEntity;
import com.farfartaxi.backend.repo.PlaceSelectionRepository;
import jakarta.transaction.Transactional;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Learned ranking: selections decay with a 90 day half-life; the same user counts double. */
@Service
public class PlaceSelectionService {
    private static final Logger log = LoggerFactory.getLogger(PlaceSelectionService.class);
    static final double HALF_LIFE_DAYS = 90.0;
    static final double SAME_USER_WEIGHT = 2.0;
    static final double MAX_BOOST = 3.0;
    static final double PRUNE_BELOW = 0.05;

    private final PlaceSelectionRepository repo;
    private final Clock clock;

    public PlaceSelectionService(PlaceSelectionRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    /** score * 0.5^(days/90) */
    public static double decayed(double score, Instant last, Instant now) {
        double days = Math.max(0, Duration.between(last, now).toMillis() / 86_400_000.0);
        return score * Math.pow(0.5, days / HALF_LIFE_DAYS);
    }

    @Transactional
    public void record(UserEntity user, PlaceSelectionRequest req) {
        String provider = req.provider() == null ? "" : req.provider().trim().toUpperCase(java.util.Locale.ROOT);
        String pid = req.providerPlaceId() == null ? "" : req.providerPlaceId().trim();
        String nq = PlaceNormalizer.normalize(req.query());
        if (nq.length() < 2 || pid.isEmpty() || pid.length() > 128
            || !(provider.equals("SL") || provider.equals("NOMINATIM")) || req.lat() == null || req.lon() == null) {
            return; // favorites/recents and malformed picks are not learned
        }
        if (nq.length() > 100) {
            nq = nq.substring(0, 100);
        }
        Instant now = clock.instant();
        PlaceSelectionEntity e = repo.findByUserIdAndNormalizedQueryAndProviderAndProviderPlaceId(user.getId(), nq, provider, pid)
            .orElse(null);
        if (e == null) {
            e = new PlaceSelectionEntity();
            e.setUser(user);
            e.setNormalizedQuery(nq);
            e.setProvider(provider);
            e.setProviderPlaceId(pid);
            e.setScore(1.0);
        } else {
            e.setScore(decayed(e.getScore(), e.getLastSelectedAt(), now) + 1.0);
        }
        String name = req.name() == null ? "" : req.name().trim();
        e.setName(name.length() > 256 ? name.substring(0, 256) : name);
        e.setLat(req.lat());
        e.setLon(req.lon());
        e.setLastSelectedAt(now);
        repo.save(e);
    }

    /** Boost per "provider|providerPlaceId" for places learned under a query starting with {@code normalizedQuery}. */
    @Transactional
    public Map<String, Double> boosts(UserEntity user, String normalizedQuery) {
        Instant now = clock.instant();
        Map<String, Double> out = new HashMap<>();
        for (PlaceSelectionEntity s : repo.findForWorldByQueryPrefix(user.isTest(), normalizedQuery)) {
            double w = s.getUser().getId().equals(user.getId()) ? SAME_USER_WEIGHT : 1.0;
            out.merge(key(s.getProvider(), s.getProviderPlaceId()), w * decayed(s.getScore(), s.getLastSelectedAt(), now), Double::sum);
        }
        out.replaceAll((k, v) -> Math.min(MAX_BOOST, v));
        return out;
    }

    public static String key(String provider, String id) {
        return provider + "|" + id;
    }

    @Scheduled(cron = "0 45 3 * * *", zone = "Europe/Stockholm")
    @Transactional
    public int prune() {
        Instant now = clock.instant();
        List<PlaceSelectionEntity> stale = repo.findAll().stream()
            .filter(s -> decayed(s.getScore(), s.getLastSelectedAt(), now) < PRUNE_BELOW).toList();
        repo.deleteAll(stale);
        log.info("Pruned {} stale place selections", stale.size());
        return stale.size();
    }
}
