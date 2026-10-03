package com.farfartaxi.backend.config;

import com.farfartaxi.backend.model.Role;
import com.farfartaxi.backend.model.UserEntity;
import com.farfartaxi.backend.repo.UserRepository;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds the production test identities (is_test=true) when their passwords are configured via
 * FARFARTAXI_TEST_PASSENGER_PASSWORD / FARFARTAXI_TEST_DRIVER_PASSWORD. Otherwise disables any existing test accounts.
 */
@Component
public class TestAccountBootstrapConfig {
    public static final String PASSENGER_EMAIL = "test-passenger@farfartaxi.invalid";
    public static final String DRIVER_EMAIL = "test-driver@farfartaxi.invalid";
    /** PTS-reserved fictitious Swedish mobile numbers. */
    public static final String PASSENGER_PHONE = "+46701740605";
    public static final String DRIVER_PHONE = "+46701740606";
    private static final Logger log = LoggerFactory.getLogger(TestAccountBootstrapConfig.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String passengerPassword;
    private final String driverPassword;

    public TestAccountBootstrapConfig(
        UserRepository userRepository,
        PasswordEncoder passwordEncoder,
        @Value("${farfartaxi.test.passenger-password:${FARFARTAXI_TEST_PASSENGER_PASSWORD:}}") String passengerPassword,
        @Value("${farfartaxi.test.driver-password:${FARFARTAXI_TEST_DRIVER_PASSWORD:}}") String driverPassword
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.passengerPassword = passengerPassword;
        this.driverPassword = driverPassword;
    }

    @PostConstruct
    public void bootstrap() {
        if (isBlank(passengerPassword) || isBlank(driverPassword)) {
            for (String email : new String[] {PASSENGER_EMAIL, DRIVER_EMAIL}) {
                userRepository.findByEmailIgnoreCase(email).ifPresent(u -> {
                    if (u.isEnabled()) {
                        u.setEnabled(false);
                        userRepository.save(u);
                    }
                });
            }
            log.info("test accounts disabled: not configured");
            return;
        }
        upsert(PASSENGER_EMAIL, "Test Passagerare", Role.USER, PASSENGER_PHONE, passengerPassword);
        upsert(DRIVER_EMAIL, "Test Förare", Role.DRIVER, DRIVER_PHONE, driverPassword);
    }

    private void upsert(String email, String name, Role role, String phone, String password) {
        UserEntity user = userRepository.findByEmailIgnoreCase(email).orElseGet(UserEntity::new);
        user.setEmail(email);
        user.setFullName(name);
        user.setRole(role);
        user.setPhone(phone);
        user.setTest(true);
        user.setEnabled(true);
        user.setApproved(true);
        user.setMustChangePassword(false);
        if (user.getPasswordHash() == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            user.setPasswordHash(passwordEncoder.encode(password));
            user.setCredentialsChangedAt(Instant.now().truncatedTo(ChronoUnit.MILLIS));
        }
        userRepository.save(user);
        log.info("Test account ensured: {}", email);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
