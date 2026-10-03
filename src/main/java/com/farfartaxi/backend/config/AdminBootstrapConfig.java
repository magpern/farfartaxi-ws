package com.farfartaxi.backend.config;

import com.farfartaxi.backend.model.Role;
import com.farfartaxi.backend.model.UserEntity;
import com.farfartaxi.backend.repo.UserRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AdminBootstrapConfig {
    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapConfig.class);
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminEmail;
    private final String adminPassword;
    private final String adminName;

    public AdminBootstrapConfig(
        UserRepository userRepository,
        PasswordEncoder passwordEncoder,
        @Value("${app.admin.email}") String adminEmail,
        @Value("${app.admin.password}") String adminPassword,
        @Value("${app.admin.name}") String adminName
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
        this.adminName = adminName;
    }

    @PostConstruct
    public void bootstrap() {
        if (adminPassword == null || adminPassword.isBlank()) {
            log.warn("FARFARTAXI_ADMIN_PASSWORD is not set; skipping admin bootstrap");
            return;
        }
        userRepository.findByEmailIgnoreCase(adminEmail).ifPresentOrElse(existing -> {
            existing.setRole(Role.ADMIN);
            if (existing.getPasswordHash() == null || !passwordEncoder.matches(adminPassword, existing.getPasswordHash())) {
                existing.setPasswordHash(passwordEncoder.encode(adminPassword));
                existing.setCredentialsChangedAt(java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
            }
            existing.setFullName(adminName);
            existing.setEnabled(true);
            existing.setApproved(true);
            userRepository.save(existing);
        }, () -> {
            UserEntity admin = new UserEntity();
            admin.setEmail(adminEmail.toLowerCase());
            admin.setPasswordHash(passwordEncoder.encode(adminPassword));
            admin.setFullName(adminName);
            admin.setRole(Role.ADMIN);
            admin.setEnabled(true);
            admin.setApproved(true);
            admin.setMustChangePassword(false);
            userRepository.save(admin);
        });
    }
}
