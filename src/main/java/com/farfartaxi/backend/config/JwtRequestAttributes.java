package com.farfartaxi.backend.config;

/**
 * Request attributes set by {@link JwtAuthFilter} so security handlers can explain 401/403 in logs.
 */
public final class JwtRequestAttributes {
    public static final String JWT_INVALID = "com.farfartaxi.auth.jwtInvalid";
    public static final String JWT_USER_DISABLED = "com.farfartaxi.auth.jwtUserDisabled";

    private JwtRequestAttributes() {
    }
}
