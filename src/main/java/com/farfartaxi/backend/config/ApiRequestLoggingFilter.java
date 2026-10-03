package com.farfartaxi.backend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * One line per HTTP request: method, path, status, duration. Uses WARN/ERROR for 4xx/5xx so errors stand out in the console.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ApiRequestLoggingFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(ApiRequestLoggingFilter.class);

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String MDC_REQUEST_ID = "requestId";
    private static final Pattern SANE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final SecureRandom RANDOM = new SecureRandom();

    static String resolveRequestId(String incoming) {
        if (incoming != null && SANE_ID.matcher(incoming).matches()) {
            return incoming;
        }
        byte[] b = new byte[6];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder(12);
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        long start = System.currentTimeMillis();
        String requestId = resolveRequestId(request.getHeader(REQUEST_ID_HEADER));
        MDC.put(MDC_REQUEST_ID, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            long ms = System.currentTimeMillis() - start;
            String uri = request.getRequestURI();
            int status = response.getStatus();
            if (uri.startsWith("/actuator/health")) {
                log.debug("{} {} -> {} {}ms", request.getMethod(), uri, status, ms);
            } else if (status >= 500) {
                log.error("{} {} -> {} {}ms", request.getMethod(), uri, status, ms);
            } else if (status >= 400) {
                log.warn("{} {} -> {} {}ms", request.getMethod(), uri, status, ms);
            } else {
                log.info("{} {} -> {} {}ms", request.getMethod(), uri, status, ms);
            }
            MDC.clear();
        }
    }
}
