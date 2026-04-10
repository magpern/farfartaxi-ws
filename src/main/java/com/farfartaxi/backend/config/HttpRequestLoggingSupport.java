package com.farfartaxi.backend.config;

import jakarta.servlet.http.HttpServletRequest;

final class HttpRequestLoggingSupport {
    private HttpRequestLoggingSupport() {
    }

    /** Prefer X-Forwarded-For first hop when behind nginx/docker (matches server.forward-headers-strategy). */
    static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return comma < 0 ? forwarded.trim() : forwarded.substring(0, comma).trim();
        }
        return request.getRemoteAddr();
    }
}
