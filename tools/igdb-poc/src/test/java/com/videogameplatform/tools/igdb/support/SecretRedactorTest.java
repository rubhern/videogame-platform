package com.videogameplatform.tools.igdb.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class SecretRedactorTest {

    @Test
    void redactsKnownSecretsAndTokenFields() {
        String secret = "super-secret-value";
        String value = "client_secret=" + secret
                + " access_token=\"token-value\" Authorization: Bearer-value";

        String redacted = SecretRedactor.redact(value, List.of(secret, "token-value"));

        assertThat(redacted)
                .doesNotContain(secret)
                .doesNotContain("token-value")
                .contains("[REDACTED]");
    }
}
