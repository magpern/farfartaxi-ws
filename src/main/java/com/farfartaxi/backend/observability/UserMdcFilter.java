package com.farfartaxi.backend.observability;

import com.farfartaxi.backend.config.SecurityUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts the numeric user id (never email) in the MDC. Ordered after the Spring Security filter chain (-100), so the
 * authentication is already populated. MDC is cleared by {@code ApiRequestLoggingFilter} after it logs the request line.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class UserMdcFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof SecurityUser u && u.getId() != null) {
            MDC.put("userId", String.valueOf(u.getId()));
        }
        chain.doFilter(request, response);
    }
}
