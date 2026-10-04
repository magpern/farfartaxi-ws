package com.farfartaxi.backend.api;

import com.farfartaxi.backend.api.dto.RideDtos.PublicShareResponse;
import com.farfartaxi.backend.service.AppException;
import com.farfartaxi.backend.service.ClientIp;
import com.farfartaxi.backend.service.RateLimits;
import com.farfartaxi.backend.service.RideService;
import com.farfartaxi.backend.service.RouteLookupService;
import com.farfartaxi.backend.service.ShareRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Unauthenticated share-link view (permitted by SecurityConfig under /api/public/**). */
@RestController
@RequestMapping("/api/public/share")
public class PublicShareController {
    private final RideService rideService;
    private final ShareRateLimiter limiter;

    private final ClientIp clientIp;
    private final RateLimits rateLimits;
    private final RouteLookupService routes;

    public PublicShareController(RideService rideService, ShareRateLimiter limiter, ClientIp clientIp,
                                 RateLimits rateLimits, RouteLookupService routes) {
        this.rideService = rideService;
        this.limiter = limiter;
        this.clientIp = clientIp;
        this.rateLimits = rateLimits;
        this.routes = routes;
    }

    @GetMapping("/{token}")
    public ResponseEntity<PublicShareResponse> view(@PathVariable String token, HttpServletRequest request) {
        if (!limiter.tryAcquire(clientIp.of(request))) {
            throw new com.farfartaxi.backend.service.RateLimitedException(limiter.retryAfterSeconds());
        }
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(rideService.publicShare(token));
    }

    /** The pickup to destination route of this shared ride only (30 requests per minute per client IP). */
    @GetMapping(value = "/{token}/route", produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> route(@PathVariable String token, HttpServletRequest request) {
        rateLimits.shareRoute(clientIp.of(request));
        double[] c = rideService.shareRouteEndpoints(token);
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(routes.driving(c[0], c[1], c[2], c[3]));
    }
}
