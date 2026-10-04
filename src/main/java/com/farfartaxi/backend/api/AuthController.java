package com.farfartaxi.backend.api;

import com.farfartaxi.backend.api.dto.AuthDtos.AuthResponse;
import com.farfartaxi.backend.api.dto.AuthDtos.ChangePasswordRequest;
import com.farfartaxi.backend.api.dto.AuthDtos.ForgotPasswordRequest;
import com.farfartaxi.backend.api.dto.AuthDtos.GoogleLoginRequest;
import com.farfartaxi.backend.api.dto.AuthDtos.LoginRequest;
import com.farfartaxi.backend.api.dto.AuthDtos.RegisterRequest;
import com.farfartaxi.backend.api.dto.AuthDtos.SetPasswordRequest;
import com.farfartaxi.backend.api.dto.AuthDtos.UserView;
import com.farfartaxi.backend.service.AppException;
import com.farfartaxi.backend.service.AuthService;
import com.farfartaxi.backend.service.ClientIp;
import com.farfartaxi.backend.service.RateLimits;
import org.springframework.http.HttpStatus;
import jakarta.validation.Valid;
import com.farfartaxi.backend.service.AuthService.AuthSession;
import com.farfartaxi.backend.service.RefreshTokenService;
import com.farfartaxi.backend.service.RefreshTokenService.RefreshResult;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    static final String REFRESH_COOKIE = "ft_refresh";
    private final AuthService authService;
    private final RefreshTokenService refreshTokenService;
    private final boolean cookieSecure;
    private final RateLimits rateLimits;
    private final ClientIp clientIp;

    public AuthController(
        AuthService authService,
        RefreshTokenService refreshTokenService,
        RateLimits rateLimits,
        ClientIp clientIp,
        @Value("${app.auth.refresh-cookie-secure:true}") boolean cookieSecure
    ) {
        this.authService = authService;
        this.refreshTokenService = refreshTokenService;
        this.cookieSecure = cookieSecure;
        this.rateLimits = rateLimits;
        this.clientIp = clientIp;
    }

    private ResponseCookie cookie(String value, long maxAgeSeconds) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
            .httpOnly(true)
            .secure(cookieSecure)
            .sameSite("Strict")
            .path("/api/auth")
            .maxAge(maxAgeSeconds)
            .build();
    }

    private ResponseEntity<AuthResponse> withCookie(AuthSession session) {
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, cookie(session.refreshToken(), refreshTokenService.lifetime().toSeconds()).toString())
            .body(session.response());
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(
        @CookieValue(name = REFRESH_COOKIE, required = false) String raw,
        HttpServletRequest request
    ) {
        RefreshResult result = refreshTokenService.refresh(raw, request.getHeader(HttpHeaders.USER_AGENT));
        if (result instanceof RefreshResult.Success ok) {
            return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie(ok.newToken(), refreshTokenService.lifetime().toSeconds()).toString())
                .body(authService.buildResponse(ok.user()));
        }
        if (result instanceof RefreshResult.Race) {
            // Another tab already rotated; it holds the new cookie in the shared jar. Do not clear it.
            return ResponseEntity.status(401).body(Map.of("error", "Refresh already in progress", "code", "REFRESH_RACE"));
        }
        return ResponseEntity.status(401)
            .header(HttpHeaders.SET_COOKIE, cookie("", 0).toString())
            .body(Map.of("error", "Session expired", "code", "REFRESH_INVALID"));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String raw) {
        refreshTokenService.revokeByRawToken(raw);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", 0).toString()).build();
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
        rateLimits.register(clientIp.of(http));
        return withCookie(authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        rateLimits.loginAttempt(clientIp.of(http), request.email());
        AuthSession session;
        try {
            session = authService.login(request);
        } catch (AppException e) {
            if (e.getStatus() == HttpStatus.UNAUTHORIZED) {
                rateLimits.loginFailed(request.email());
            }
            throw e;
        }
        rateLimits.loginSucceeded(request.email());
        return withCookie(session);
    }

    @PostMapping("/google")
    public ResponseEntity<AuthResponse> google(@Valid @RequestBody GoogleLoginRequest request, HttpServletRequest http) {
        rateLimits.google(clientIp.of(http));
        return withCookie(authService.loginWithGoogle(request.credential()));
    }

    @PostMapping("/set-password")
    public ResponseEntity<AuthResponse> setPassword(@Valid @RequestBody SetPasswordRequest request) {
        return withCookie(authService.setLocalPassword(request));
    }

    @PostMapping("/forgot-password")
    public void forgotPassword(@Valid @RequestBody ForgotPasswordRequest request, HttpServletRequest http) {
        rateLimits.forgotPassword(clientIp.of(http));
        authService.forgotPassword(request.email());
    }

    @PostMapping("/change-password")
    public ResponseEntity<AuthResponse> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        return withCookie(authService.changePassword(request));
    }

    @GetMapping("/me")
    public UserView me() {
        return authService.me();
    }
}
