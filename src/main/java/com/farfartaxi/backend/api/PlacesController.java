package com.farfartaxi.backend.api;

import com.farfartaxi.backend.api.dto.PlaceDtos.NearestStopResponse;
import com.farfartaxi.backend.api.dto.PlaceDtos.PlaceResult;
import com.farfartaxi.backend.api.dto.PlaceDtos.PlaceSearchResponse;
import com.farfartaxi.backend.api.dto.PlaceDtos.PlaceSelectionRequest;
import com.farfartaxi.backend.places.NearestStopService;
import com.farfartaxi.backend.places.PlaceSearchService;
import com.farfartaxi.backend.places.PlaceSelectionService;
import com.farfartaxi.backend.places.RecentPlacesService;
import com.farfartaxi.backend.places.ReverseGeocodeService;
import java.util.List;
import com.farfartaxi.backend.service.CurrentUserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated place search (M3). Everything under /api/places requires an approved user. */
@RestController
@RequestMapping("/api/places")
public class PlacesController {
    private final PlaceSearchService search;
    private final PlaceSelectionService selections;
    private final NearestStopService nearestStops;
    private final ReverseGeocodeService reverse;
    private final CurrentUserService currentUser;
    private final RecentPlacesService recent;
    private final com.farfartaxi.backend.service.SavedPlaceService savedPlaces;

    public PlacesController(PlaceSearchService search, PlaceSelectionService selections, NearestStopService nearestStops,
                            ReverseGeocodeService reverse, CurrentUserService currentUser, RecentPlacesService recent,
                            com.farfartaxi.backend.service.SavedPlaceService savedPlaces) {
        this.savedPlaces = savedPlaces;
        this.recent = recent;
        this.search = search;
        this.selections = selections;
        this.nearestStops = nearestStops;
        this.reverse = reverse;
        this.currentUser = currentUser;
    }

    @GetMapping("/search")
    public PlaceSearchResponse search(
            @RequestParam(required = false, defaultValue = "") String q,
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lon,
            @RequestParam(required = false) Double accuracy,
            @RequestParam(required = false) Double pickupLat,
            @RequestParam(required = false) Double pickupLon,
            @RequestParam(required = false) Integer limit) {
        return search.search(currentUser.requireUser(), q, lat, lon, accuracy, pickupLat, pickupLon, limit);
    }

    @GetMapping("/recent")
    public List<PlaceResult> recent(@RequestParam(required = false) Integer limit, @RequestParam(required = false) Long userId) {
        return recent.recent(savedPlaces.resolveTargetHidingExistence(userId), limit);
    }

    @GetMapping("/nearest-stop")
    public ResponseEntity<NearestStopResponse> nearestStop(@RequestParam double lat, @RequestParam double lon) {
        boolean test = currentUser.requireUser().isTest();
        return nearestStops.nearest(lat, lon, test).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/selections")
    public ResponseEntity<Void> select(@Valid @RequestBody PlaceSelectionRequest request) {
        selections.record(currentUser.requireUser(), request);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/reverse")
    public ResponseEntity<PlaceResult> reverse(@RequestParam double lat, @RequestParam double lon) {
        boolean test = currentUser.requireUser().isTest();
        return reverse.reverse(lat, lon, test).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }
}
