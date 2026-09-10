package com.videogameplatform.identity.domain;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * Stable, provider-independent product identity.
 *
 * <p>Derived only from the validated OIDC {@code issuer} and {@code subject}, never from mutable
 * profile data such as email, username, or a client-supplied identifier. The derivation is
 * deterministic so the same authenticated person always maps to the same product identity, while
 * the value itself stays opaque and reveals neither the issuer nor the subject.
 */
public record UserId(String value) implements Serializable {

    public UserId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("UserId value must not be blank");
        }
    }

    /**
     * Maps a validated {@code issuer + subject} pair to a stable product identity.
     *
     * @param issuer the validated OIDC issuer identifier
     * @param subject the validated OIDC subject claim
     */
    public static UserId fromIssuerAndSubject(String issuer, String subject) {
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(subject, "subject");
        if (issuer.isBlank() || subject.isBlank()) {
            throw new IllegalArgumentException("issuer and subject must not be blank");
        }
        byte[] identity = (issuer + '\n' + subject).getBytes(StandardCharsets.UTF_8);
        return new UserId(UUID.nameUUIDFromBytes(identity).toString());
    }
}
