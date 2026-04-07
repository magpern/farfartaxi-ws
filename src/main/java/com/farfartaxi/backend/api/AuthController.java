package com.farfartaxi.backend.api;

import com.farfartaxi.backend.api.dto.AuthDtos.AuthResponse;
import com.farfartaxi.backend.api.dto.AuthDtos.ChangePasswordRequest;
import com.farfartaxi.backend.api.dto.AuthDtos.ForgotPasswordRequest;
import com.farfartaxi.backend.api.dto.AuthDtos.GoogleLoginRequest;
import com.farfartaxi.backend.api.dto.AuthDtos.LoginRequest;
import com.farfartaxi.backend.api.dto.AuthDtos.RegisterRequest;
import com.farfartaxi.backend.api.dto.AuthDtos.SetPasswordRequest;
import com.farfartaxi.backend.api.dto.AuthDtos.UserView;
import com.farfartaxi.backend.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/google")
    public AuthResponse google(@Valid @RequestBody GoogleLoginRequest request) {
        return authService.loginWithGoogle(request.credential());
    }

    @PostMapping("/set-password")
    public void setPassword(@Valid @RequestBody SetPasswordRequest request) {
        authService.setLocalPassword(request);
    }

    @PostMapping("/forgot-password")
    public void forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request.email());
    }

    @PostMapping("/change-password")
    public void changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(request);
    }

    @GetMapping("/me")
    public UserView me() {
        return authService.me();
    }
}
