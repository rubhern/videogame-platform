package com.videogameplatform.identity.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DeletionCompletionPolicyTest {
    @Test
    void productionAlwaysRequiresIndependentEvidenceEvenWithThePrivateProfile() {
        assertThatThrownBy(
                        () ->
                                DeletionCompletionPolicy.validate(
                                        "private-dev",
                                        "https://gameometro.com/realms/gameometro",
                                        new String[] {"private-dev"}))
                .isInstanceOf(IllegalStateException.class);
        assertThat(
                        DeletionCompletionPolicy.validate(
                                "independent",
                                "https://gameometro.com/realms/gameometro",
                                new String[] {}))
                .isEqualTo("independent");
    }

    @Test
    void privateModeRequiresBothExplicitProfileAndNonProductionRealm() {
        String issuer = "https://vgpdev.example.ts.net:8443/realms/videogame-platform";
        assertThatThrownBy(
                        () ->
                                DeletionCompletionPolicy.validate(
                                        "private-dev", issuer, new String[] {"oidc"}))
                .isInstanceOf(IllegalStateException.class);
        assertThat(
                        DeletionCompletionPolicy.validate(
                                "private-dev", issuer, new String[] {"oidc", "private-dev"}))
                .isEqualTo("private-dev");
        assertThatThrownBy(
                        () ->
                                DeletionCompletionPolicy.validate(
                                        "disabled-evidence", issuer, new String[] {"private-dev"}))
                .isInstanceOf(IllegalStateException.class);
    }
}
