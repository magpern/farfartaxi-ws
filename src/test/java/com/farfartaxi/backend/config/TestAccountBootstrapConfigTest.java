package com.farfartaxi.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.farfartaxi.backend.model.Role;
import com.farfartaxi.backend.model.UserEntity;
import com.farfartaxi.backend.repo.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class TestAccountBootstrapConfigTest {
    private final UserRepository repo = mock(UserRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);

    private UserEntity existing(String email) {
        UserEntity u = new UserEntity();
        u.setEmail(email);
        u.setEnabled(true);
        u.setTest(true);
        u.setRole(Role.USER);
        when(repo.findByEmailIgnoreCase(email)).thenReturn(Optional.of(u));
        return u;
    }

    @Test
    void notConfiguredDisablesExistingTestAccounts() {
        UserEntity p = existing(TestAccountBootstrapConfig.PASSENGER_EMAIL);
        UserEntity d = existing(TestAccountBootstrapConfig.DRIVER_EMAIL);
        new TestAccountBootstrapConfig(repo, encoder, "", "").bootstrap();
        assertThat(p.isEnabled()).isFalse();
        assertThat(d.isEnabled()).isFalse();
        verify(repo).save(p);
        verify(repo).save(d);
    }

    @Test
    void notConfiguredWithNoAccountsDoesNothing() {
        when(repo.findByEmailIgnoreCase(any())).thenReturn(Optional.empty());
        new TestAccountBootstrapConfig(repo, encoder, "", "x").bootstrap();
        verify(repo, never()).save(any());
    }

    @Test
    void configuredReEnablesAndEnsuresAccounts() {
        UserEntity p = existing(TestAccountBootstrapConfig.PASSENGER_EMAIL);
        p.setEnabled(false);
        existing(TestAccountBootstrapConfig.DRIVER_EMAIL);
        when(encoder.encode(any())).thenReturn("hash");
        new TestAccountBootstrapConfig(repo, encoder, "pw1", "pw2").bootstrap();
        assertThat(p.isEnabled()).isTrue();
        assertThat(p.isTest()).isTrue();
    }
}
