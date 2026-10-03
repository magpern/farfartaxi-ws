package com.farfartaxi.backend.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.farfartaxi.backend.config.JwtService;
import org.junit.jupiter.api.Test;

class JwtServiceTest {
    @Test
    void rejectsBlankSecret() {
        assertThatThrownBy(() -> new JwtService("", 60)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtService("   ", 60)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsShortSecret() {
        assertThatThrownBy(() -> new JwtService("too-short", 60)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsPublicDefaultSecret() {
        assertThatThrownBy(() -> new JwtService("replace-this-in-production-with-a-long-secret-value", 60))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("public default");
    }

    @Test
    void acceptsStrongSecret() {
        assertThat(new JwtService("test-only-unit-secret-0123456789-0123456789-abcdef", 60)).isNotNull();
    }

    @Test
    void rejectsKnownPlaceholders() {
        for (String v : new String[] {
            "change-this-jwt-secret-to-a-long-random-value",
            "CHANGE-THIS-JWT-SECRET-TO-A-LONG-RANDOM-VALUE",
            "CHANGE_ME_generate_with_openssl_rand_base64_48",
            "change_me_generate_with_openssl_rand_base64_48",
            "Change_Me_whatever_long_enough_0123456789abcdef"
        }) {
            assertThatThrownBy(() -> new JwtService(v, 60)).as(v).isInstanceOf(IllegalStateException.class);
        }
    }
}
