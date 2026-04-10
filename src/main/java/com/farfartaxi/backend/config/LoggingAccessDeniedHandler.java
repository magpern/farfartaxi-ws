package com.farfartaxi.backend.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;

/**
 * Logs access-denied (403) with principal context, then delegates to Spring default (403).
 */
public class LoggingAccessDeniedHandler implements AccessDeniedHandler {
    private static final Logger log = LoggerFactory.getLogger(LoggingAccessDeniedHandler.class);
    private final AccessDeniedHandler delegate = new AccessDeniedHandlerImpl();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
        throws IOException, ServletException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String principal = auth != null ? String.valueOf(auth.getPrincipal()) : "none";
        boolean authenticated = auth != null && auth.isAuthenticated();
        log.warn(
            "Security 403 access denied: {} {} | ip={} | authenticated={} | principal={} | authorities={} | message={}",
            request.getMethod(),
            request.getRequestURI(),
            HttpRequestLoggingSupport.clientIp(request),
            authenticated,
            principal,
            auth != null ? auth.getAuthorities() : "[]",
            accessDeniedException.getMessage()
        );
        delegate.handle(request, response, accessDeniedException);
    }
}
