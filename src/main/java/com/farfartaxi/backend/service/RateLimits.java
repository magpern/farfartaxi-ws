package com.farfartaxi.backend.service;

import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** All the request limiters beyond the share-view one. Limits are configurable (e2e raises the auth ones). */
@Component
public class RateLimits {
    private static final int MAX = 100_000;

    private final FixedWindowLimiter shareRoute;
    private final FixedWindowLimiter userRoute;
    private final FixedWindowLimiter telemetry;
    private final FixedWindowLimiter register;
    private final FixedWindowLimiter loginIp;
    private final FixedWindowLimiter loginFailEmail;
    private final FixedWindowLimiter google;
    private final FixedWindowLimiter forgot;

    public RateLimits(
        Clock clock,
        @Value("${app.ratelimit.share-route-per-minute:30}") int shareRoutePerMin,
        @Value("${app.ratelimit.user-route-per-minute:60}") int userRoutePerMin,
        @Value("${app.ratelimit.telemetry-requests-per-hour:300}") int telemetryPerHour,
        @Value("${app.ratelimit.auth.register-per-hour:20}") int registerPerHour,
        @Value("${app.ratelimit.auth.login-per-15min:300}") int loginPer15,
        @Value("${app.ratelimit.auth.login-failed-per-email-15min:10}") int loginFailPer15,
        @Value("${app.ratelimit.auth.google-per-15min:30}") int googlePer15,
        @Value("${app.ratelimit.auth.forgot-per-hour:20}") int forgotPerHour
    ) {
        Duration minute = Duration.ofMinutes(1);
        Duration quarter = Duration.ofMinutes(15);
        Duration hour = Duration.ofHours(1);
        this.shareRoute = new FixedWindowLimiter(clock, shareRoutePerMin, minute, MAX);
        this.userRoute = new FixedWindowLimiter(clock, userRoutePerMin, minute, MAX);
        this.telemetry = new FixedWindowLimiter(clock, telemetryPerHour, hour, MAX);
        this.register = new FixedWindowLimiter(clock, registerPerHour, hour, MAX);
        this.loginIp = new FixedWindowLimiter(clock, loginPer15, quarter, MAX);
        this.loginFailEmail = new FixedWindowLimiter(clock, loginFailPer15, quarter, MAX);
        this.google = new FixedWindowLimiter(clock, googlePer15, quarter, MAX);
        this.forgot = new FixedWindowLimiter(clock, forgotPerHour, hour, MAX);
    }

    private static void enforce(long retryAfter) {
        if (retryAfter > 0) {
            throw new RateLimitedException(retryAfter);
        }
    }

    public void shareRoute(String ip) { enforce(shareRoute.acquire(ip)); }

    public void userRoute(long userId) { enforce(userRoute.acquire("u" + userId)); }

    public void telemetryRequest(long userId) { enforce(telemetry.acquire("u" + userId)); }

    public void register(String ip) { enforce(register.acquire(ip)); }

    public void google(String ip) { enforce(google.acquire(ip)); }

    public void forgotPassword(String ip) { enforce(forgot.acquire(ip)); }

    /** Per-IP attempt budget plus the per-email failed-attempt lockout; call before verifying the password. */
    public void loginAttempt(String ip, String email) {
        enforce(loginFailEmail.check(emailKey(email)));
        enforce(loginIp.acquire(ip));
    }

    public void loginFailed(String email) { loginFailEmail.hit(emailKey(email)); }

    public void loginSucceeded(String email) { loginFailEmail.reset(emailKey(email)); }

    private static String emailKey(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }
}
