package com.farfartaxi.backend.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * Logs why a protected endpoint was not authenticated, then returns 401 (same as {@link HttpStatusEntryPoint}).
 */
public class LoggingAuthenticationEntryPoint implements AuthenticationEntryPoint {
    private static final Logger log = LoggerFactory.getLogger(LoggingAuthenticationEntryPoint.class);
    private final AuthenticationEntryPoint delegate = new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED);

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
        throws IOException, ServletException {
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        boolean hasBearer = authHeader != null && authHeader.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length());
        Boolean jwtInvalid = (Boolean) request.getAttribute(JwtRequestAttributes.JWT_INVALID);
        Boolean userDisabled = (Boolean) request.getAttribute(JwtRequestAttributes.JWT_USER_DISABLED);
        log.warn(
            "Security 401 not authenticated: {} {} | ip={} | hasBearer={} | jwtInvalid={} | jwtUserDisabled={} | exception={}",
            request.getMethod(),
            request.getRequestURI(),
            HttpRequestLoggingSupport.clientIp(request),
            hasBearer,
            Boolean.TRUE.equals(jwtInvalid),
            Boolean.TRUE.equals(userDisabled),
            authException != null ? authException.getClass().getSimpleName() + ": " + authException.getMessage() : "none"
        );
        delegate.commence(request, response, authException);
    }
}
