package com.farfartaxi.backend.service;

import com.farfartaxi.backend.api.dto.AuthDtos.AuthResponse;
import com.farfartaxi.backend.api.dto.AuthDtos.ChangePasswordRequest;
import com.farfartaxi.backend.api.dto.AuthDtos.LoginRequest;
import com.farfartaxi.backend.api.dto.AuthDtos.RegisterRequest;
import com.farfartaxi.backend.api.dto.AuthDtos.UserView;
import com.farfartaxi.backend.config.JwtService;
import com.farfartaxi.backend.model.Role;
import com.farfartaxi.backend.model.UserEntity;
import com.farfartaxi.backend.repo.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final CurrentUserService currentUserService;

    public AuthService(
        UserRepository userRepository,
        PasswordEncoder passwordEncoder,
        JwtService jwtService,
        CurrentUserService currentUserService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.currentUserService = currentUserService;
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
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }
        if (!user.isEnabled()) {
            throw new AppException(HttpStatus.FORBIDDEN, "Account disabled");
        }
        return toAuthResponse(user);
    }

    public void forgotPassword(String email) {
        // Placeholder for SMTP/token flow.
    }

    public void changePassword(ChangePasswordRequest request) {
        UserEntity user = currentUserService.requireUser();
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
