package com.farfartaxi.backend.service;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Accepts only https push endpoints on known push-service hosts (SSRF guard). Entries are exact hosts or
 * {@code *.suffix}; an empty allowlist disables the check entirely (tests / e2e stubs only).
 */
@Component
public class PushEndpointPolicy {
    private final List<String> allowed;

    public PushEndpointPolicy(@Value("${app.push.endpoint-allowlist:}") String allowlist) {
        this.allowed = Arrays.stream((allowlist == null ? "" : allowlist).split(","))
            .map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).toList();
    }

    public void require(String endpoint) {
        if (allowed.isEmpty()) {
            return;
        }
        String host;
        try {
            URI uri = new URI(endpoint);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null || uri.getHost() == null) {
                throw invalid();
            }
            host = uri.getHost().toLowerCase(Locale.ROOT);
        } catch (java.net.URISyntaxException e) {
            throw invalid();
        }
        for (String a : allowed) {
            if (a.startsWith("*.") ? host.endsWith(a.substring(1)) && host.length() > a.length() - 1 : host.equals(a)) {
                return;
            }
        }
        throw invalid();
    }

    private static AppException invalid() {
        return new AppException(HttpStatus.BAD_REQUEST, "endpoint: not an accepted push service endpoint");
    }
}
