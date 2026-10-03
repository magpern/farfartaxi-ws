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

    private final int trustedProxyHops;

    public PublicShareController(RideService rideService, ShareRateLimiter limiter,
                                 @org.springframework.beans.factory.annotation.Value("${app.share.trusted-proxy-hops:2}") int trustedProxyHops) {
        this.trustedProxyHops = trustedProxyHops;
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

    /**
     * Client key for the limiter, always a normalized IP literal (never raw header text): CF-Connecting-IP if valid,
     * else the X-Forwarded-For entry {@code trustedProxyHops} from the right (each trusted proxy appends one entry),
     * else the remote address.
     */
    String clientIp(HttpServletRequest request) {
        String cf = normalizeIp(request.getHeader("CF-Connecting-IP"));
        if (cf != null) {
            return cf;
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank() && trustedProxyHops > 0) {
            String[] parts = xff.split(",");
            int idx = parts.length - trustedProxyHops;
            if (idx >= 0) {
                String ip = normalizeIp(parts[idx]);
                if (ip != null) {
                    return ip;
                }
            }
        }
        String remote = normalizeIp(request.getRemoteAddr());
        return remote != null ? remote : "unknown";
    }

    /** Canonical text of an IPv4/IPv6 literal, or null. Never does a DNS lookup. */
    static String normalizeIp(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty() || s.length() > 45 || !s.matches("[0-9a-fA-F:.]+")) {
            return null;
        }
        if (!s.contains(":") && !s.matches("((25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1?\\d?\\d)")) {
            return null;
        }
        try {
            // a validated literal: no DNS lookup can happen
            return java.net.InetAddress.getByName(s).getHostAddress();
        } catch (java.net.UnknownHostException e) {
            return null;
        }
    }
}
