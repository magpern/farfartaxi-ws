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
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final CurrentUserService currentUserService;
    private final GoogleIdTokenService googleIdTokenService;

    public AuthService(
        UserRepository userRepository,
        PasswordEncoder passwordEncoder,
        JwtService jwtService,
        CurrentUserService currentUserService,
        GoogleIdTokenService googleIdTokenService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.currentUserService = currentUserService;
        this.googleIdTokenService = googleIdTokenService;
    }

    public AuthResponse register(RegisterRequest request) {
        userRepository.findByEmailIgnoreCase(request.email()).ifPresent(existing -> {
            throw new AppException(HttpStatus.CONFLICT, "Email already registered");
        });
        UserEntity user = new UserEntity();
        user.setEmail(request.email().toLowerCase());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setFullName(request.fullName());
        user.setRole(Role.USER);
        user.setEnabled(true);
        user.setMustChangePassword(false);
        user = userRepository.save(user);
        return toAuthResponse(user);
    }

    public AuthResponse login(LoginRequest request) {
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
        return toAuthResponse(user);
    }

    @Transactional
    public AuthResponse loginWithGoogle(String credentialJwt) {
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
            return toAuthResponse(userRepository.save(user));
        }

        UserEntity byEmail = userRepository.findByEmailIgnoreCase(gp.email()).orElse(null);
        if (byEmail != null) {
            if (byEmail.getGoogleSub() != null && !byEmail.getGoogleSub().equals(gp.sub())) {
                throw new AppException(HttpStatus.CONFLICT, "Email is linked to a different Google account");
            }
            if (!byEmail.isEnabled()) {
                throw new AppException(HttpStatus.FORBIDDEN, "Account disabled");
            }
            byEmail.setGoogleSub(gp.sub());
            refreshGoogleProfile(byEmail, gp);
            return toAuthResponse(userRepository.save(byEmail));
        }

        UserEntity created = new UserEntity();
        created.setEmail(gp.email());
        created.setGoogleSub(gp.sub());
        created.setFullName(gp.fullName());
        created.setRole(Role.USER);
        created.setEnabled(true);
        created.setMustChangePassword(false);
        return toAuthResponse(userRepository.save(created));
    }

    private static UserEntity refreshGoogleProfile(UserEntity user, GoogleIdTokenService.GoogleProfile gp) {
        if (gp.fullName() != null && !gp.fullName().isBlank()) {
            user.setFullName(gp.fullName());
        }
        return user;
    }

    @Transactional
    public void setLocalPassword(SetPasswordRequest request) {
        UserEntity user = currentUserService.requireUser();
        if (user.getPasswordHash() != null) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Password already set; use change password");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
    }

    public void forgotPassword(String email) {
        // Placeholder for SMTP/token flow.
    }

    public void changePassword(ChangePasswordRequest request) {
        UserEntity user = currentUserService.requireUser();
        if (user.getPasswordHash() == null) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Use set-password to add a password first");
        }
        if (!passwordEncoder.matches(request.oldPassword(), user.getPasswordHash())) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Old password mismatch");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setMustChangePassword(false);
        userRepository.save(user);
    }

    public UserView me() {
        return toUserView(currentUserService.requireUser());
    }

    public UserView toUserView(UserEntity user) {
        return new UserView(user.getId(), user.getEmail(), user.getFullName(), user.getRole().name(), user.isMustChangePassword());
    }

    private AuthResponse toAuthResponse(UserEntity user) {
        return new AuthResponse(jwtService.generateToken(user), toUserView(user));
    }
}
