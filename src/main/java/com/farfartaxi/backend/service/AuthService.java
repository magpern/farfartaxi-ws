package com.farfartaxi.backend.service;

import com.farfartaxi.backend.api.dto.AuthDtos.AuthResponse;
import com.farfartaxi.backend.api.dto.AuthDtos.ChangePasswordRequest;
import com.farfartaxi.backend.api.dto.AuthDtos.LoginRequest;
import com.farfartaxi.backend.api.dto.AuthDtos.RegisterRequest;
import com.farfartaxi.backend.api.dto.AuthDtos.SetPasswordRequest;
import com.farfartaxi.backend.api.dto.AuthDtos.UserView;
import com.farfartaxi.backend.config.JwtService;
import com.farfartaxi.backend.model.Role;
import com.farfartaxi.backend.model.UserEntity;
import com.farfartaxi.backend.repo.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AuthService.class);
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final CurrentUserService currentUserService;
    private final GoogleIdTokenService googleIdTokenService;
    private final RefreshTokenService refreshTokenService;

    /** Access-token response plus the raw refresh token (cookie only, never serialized in the body). */
    public record AuthSession(AuthResponse response, String refreshToken) { }

    public AuthService(
        UserRepository userRepository,
        PasswordEncoder passwordEncoder,
        JwtService jwtService,
        CurrentUserService currentUserService,
        GoogleIdTokenService googleIdTokenService,
        RefreshTokenService refreshTokenService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.currentUserService = currentUserService;
        this.googleIdTokenService = googleIdTokenService;
        this.refreshTokenService = refreshTokenService;
    }

    public AuthSession register(RegisterRequest request) {
        userRepository.findByEmailIgnoreCase(request.email()).ifPresent(existing -> {
            throw new AppException(HttpStatus.CONFLICT, "Email already registered");
        });
        UserEntity user = new UserEntity();
        user.setEmail(request.email().toLowerCase());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setFullName(request.fullName());
        user.setRole(Role.USER);
        user.setEnabled(true);
        user.setApproved(false);
        user.setMustChangePassword(false);
        user = userRepository.save(user);
        return toSession(user, false);
    }

    public AuthSession login(LoginRequest request) {
        UserEntity user = userRepository.findByEmailIgnoreCase(request.email())
            .orElseThrow(() -> new AppException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));
        if (user.getPasswordHash() == null) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "Use Google sign-in or set a password in the app");
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }
        if (!user.isEnabled()) {
            throw new AppException(HttpStatus.FORBIDDEN, "Account disabled");
        }
        return toSession(user, false);
    }

    @Transactional
    public AuthSession loginWithGoogle(String credentialJwt) {
        if (!googleIdTokenService.isConfigured()) {
            throw new AppException(HttpStatus.SERVICE_UNAVAILABLE, "Google sign-in is not configured");
        }
        GoogleIdTokenService.GoogleProfile gp = googleIdTokenService.verify(credentialJwt).orElseThrow(() ->
            new AppException(HttpStatus.UNAUTHORIZED, "Invalid Google credential"));
        if (!gp.emailVerified()) {
            throw new AppException(HttpStatus.FORBIDDEN, "Google email must be verified");
        }

        UserEntity user = userRepository.findByGoogleSub(gp.sub()).map(u -> refreshGoogleProfile(u, gp)).orElse(null);
        if (user != null) {
            if (!user.isEnabled()) {
                throw new AppException(HttpStatus.FORBIDDEN, "Account disabled");
            }
            return toSession(userRepository.save(user), false);
        }

        UserEntity byEmail = userRepository.findByEmailIgnoreCase(gp.email()).orElse(null);
        if (byEmail != null) {
            if (byEmail.getGoogleSub() != null && !byEmail.getGoogleSub().equals(gp.sub())) {
                throw new AppException(HttpStatus.CONFLICT, "Email is linked to a different Google account");
            }
            if (!byEmail.isEnabled()) {
                throw new AppException(HttpStatus.FORBIDDEN, "Account disabled");
            }
            boolean revokeOthers = false;
            if (!byEmail.isApproved() && byEmail.getPasswordHash() != null) {
                // Pre-hijack defence: a pending account with a local password may have been registered by someone
                // other than the verified email owner. Only the verified Google identity may sign in from now on.
                byEmail.setPasswordHash(null);
                byEmail.setCredentialsChangedAt(java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
                byEmail.setMustChangePassword(false);
                log.info("Cleared local password of pending user id={} on Google link", byEmail.getId());
                revokeOthers = true;
            }
            byEmail.setGoogleSub(gp.sub());
            refreshGoogleProfile(byEmail, gp);
            return toSession(userRepository.save(byEmail), revokeOthers);
        }

        UserEntity created = new UserEntity();
        created.setEmail(gp.email());
        created.setGoogleSub(gp.sub());
        created.setFullName(gp.fullName());
        created.setRole(Role.USER);
        created.setEnabled(true);
        created.setApproved(false);
        created.setMustChangePassword(false);
        return toSession(userRepository.save(created), false);
    }

    private static UserEntity refreshGoogleProfile(UserEntity user, GoogleIdTokenService.GoogleProfile gp) {
        if (gp.fullName() != null && !gp.fullName().isBlank()) {
            user.setFullName(gp.fullName());
        }
        return user;
    }

    @Transactional
    public AuthSession setLocalPassword(SetPasswordRequest request) {
        UserEntity user = currentUserService.requireUser();
        if (user.getPasswordHash() != null) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Password already set; use change password");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setCredentialsChangedAt(java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        return toSession(userRepository.save(user), true);
    }

    public void forgotPassword(String email) {
        // Placeholder for SMTP/token flow.
    }

    @Transactional
    public AuthSession changePassword(ChangePasswordRequest request) {
        UserEntity user = currentUserService.requireUser();
        if (user.getPasswordHash() == null) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Use set-password to add a password first");
        }
        if (!passwordEncoder.matches(request.oldPassword(), user.getPasswordHash())) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Old password mismatch");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setMustChangePassword(false);
        user.setCredentialsChangedAt(java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        return toSession(userRepository.save(user), true);
    }

    public UserView me() {
        return toUserView(currentUserService.requireUser());
    }

    public UserView toUserView(UserEntity user) {
        boolean hasLocal = user.getPasswordHash() != null;
        boolean mustPw = hasLocal && user.isMustChangePassword();
        return new UserView(user.getId(), user.getEmail(), user.getFullName(), user.getRole().name(), mustPw, hasLocal, user.isApproved());
    }

    private AuthSession toSession(UserEntity user, boolean revokeOthers) {
        if (revokeOthers) {
            refreshTokenService.revokeAllForUser(user.getId());
        }
        String refresh = refreshTokenService.issueNewFamily(user.getId(), currentUserAgent());
        return new AuthSession(buildResponse(user), refresh);
    }

    /** Access token + user view for an already-authenticated user (used by refresh). */
    public AuthResponse buildResponse(UserEntity user) {
        return new AuthResponse(jwtService.generateToken(user), toUserView(user));
    }

    static String currentUserAgent() {
        var attrs = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (attrs instanceof org.springframework.web.context.request.ServletRequestAttributes sra) {
            return sra.getRequest().getHeader("User-Agent");
        }
        return null;
    }
}
