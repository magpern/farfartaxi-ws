package com.farfartaxi.backend.service;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class GoogleIdTokenService {
    private final GoogleIdTokenVerifier verifier;

    public GoogleIdTokenService(@Value("${app.oauth2.google.client-id:}") String clientId) {
        String trimmed = clientId == null ? "" : clientId.trim();
        if (trimmed.isEmpty()) {
            this.verifier = null;
        } else {
            this.verifier = new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
                .setAudience(Collections.singletonList(trimmed))
                .build();
        }
    }

    public boolean isConfigured() {
        return verifier != null;
    }

    /**
     * Verifies a Google Sign-In ID token (JWT) and returns stable user fields.
     */
    public Optional<GoogleProfile> verify(String credentialJwt) {
        if (verifier == null || credentialJwt == null || credentialJwt.isBlank()) {
            return Optional.empty();
        }
        try {
            GoogleIdToken idToken = verifier.verify(credentialJwt);
            if (idToken == null) {
                return Optional.empty();
            }
            GoogleIdToken.Payload p = idToken.getPayload();
            String sub = p.getSubject();
            String email = p.getEmail();
            if (sub == null || email == null || email.isBlank()) {
                return Optional.empty();
            }
            Boolean verified = p.getEmailVerified();
            boolean emailVerified = verified != null && verified;
            String name = displayName(p, email);
            return Optional.of(new GoogleProfile(sub, email.toLowerCase(Locale.ROOT), name, emailVerified));
        } catch (GeneralSecurityException | IOException e) {
            return Optional.empty();
        }
    }

    private static String displayName(GoogleIdToken.Payload p, String email) {
        String name = (String) p.get("name");
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        String given = (String) p.get("given_name");
        String family = (String) p.get("family_name");
        String combined = ((given != null ? given : "") + " " + (family != null ? family : "")).trim();
        if (!combined.isBlank()) {
            return combined;
        }
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }

    public record GoogleProfile(String sub, String email, String fullName, boolean emailVerified) {
    }
}
