package com.farfartaxi.backend.api;

import com.farfartaxi.backend.api.dto.RideDtos.PublicShareResponse;
import com.farfartaxi.backend.service.AppException;
import com.farfartaxi.backend.service.RideService;
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

    public PublicShareController(RideService rideService, ShareRateLimiter limiter) {
        this.rideService = rideService;
        this.limiter = limiter;
    }

    @GetMapping("/{token}")
    public ResponseEntity<PublicShareResponse> view(@PathVariable String token, HttpServletRequest request) {
        if (!limiter.tryAcquire(clientIp(request))) {
            throw new AppException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests");
        }
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(rideService.publicShare(token));
    }

    /** Behind the reverse proxy the peer is always the proxy, so use the forwarded client address when present. */
    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
