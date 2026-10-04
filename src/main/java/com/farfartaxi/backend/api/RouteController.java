package com.farfartaxi.backend.api;

import com.farfartaxi.backend.service.CurrentUserService;
import com.farfartaxi.backend.service.RateLimits;
import com.farfartaxi.backend.service.RouteLookupService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated (approved users) driving-route proxy; 60 requests per minute per user. */
@RestController
@RequestMapping("/api/route")
public class RouteController {
    private final RouteLookupService routes;
    private final RateLimits rateLimits;
    private final CurrentUserService currentUser;

    public RouteController(RouteLookupService routes, RateLimits rateLimits, CurrentUserService currentUser) {
        this.routes = routes;
        this.rateLimits = rateLimits;
        this.currentUser = currentUser;
    }

    @GetMapping(value = "/driving", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> driving(
            @RequestParam double fromLat,
            @RequestParam double fromLon,
            @RequestParam double toLat,
            @RequestParam double toLon) {
        rateLimits.userRoute(currentUser.requireUser().getId());
        return ResponseEntity.ok().header("Cache-Control", "private, no-store").body(routes.driving(fromLat, fromLon, toLat, toLon));
    }
}
