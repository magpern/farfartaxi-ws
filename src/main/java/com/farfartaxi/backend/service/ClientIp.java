package com.farfartaxi.backend.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Client key for the limiters, always a normalized IP literal (never raw header text): CF-Connecting-IP if valid,
 * else the X-Forwarded-For entry {@code trustedProxyHops} from the right (each trusted proxy appends one entry),
 * else the remote address.
 */
@Component
public class ClientIp {
    private final int trustedProxyHops;

    public ClientIp(@Value("${app.share.trusted-proxy-hops:2}") int trustedProxyHops) {
        this.trustedProxyHops = trustedProxyHops;
    }

    public String of(HttpServletRequest request) {
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
            return java.net.InetAddress.getByName(s).getHostAddress();
        } catch (java.net.UnknownHostException e) {
            return null;
        }
    }
}
