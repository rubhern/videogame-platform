package com.videogameplatform.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class UserIdTest {

    private static final String ISSUER = "https://identity.example/realms/videogame-platform";
    private static final String SUBJECT = "4e8c1f2a-0000-4000-8000-000000000001";

    @Test
    void derivesAStableIdentityFromTheSameIssuerAndSubject() {
        assertThat(UserId.fromIssuerAndSubject(ISSUER, SUBJECT))
                .isEqualTo(UserId.fromIssuerAndSubject(ISSUER, SUBJECT));
    }

    @Test
    void separatesIdentityByIssuerAndBySubject() {
        UserId reference = UserId.fromIssuerAndSubject(ISSUER, SUBJECT);

        assertThat(UserId.fromIssuerAndSubject("https://other.example/realms/x", SUBJECT))
                .isNotEqualTo(reference);
        assertThat(UserId.fromIssuerAndSubject(ISSUER, "another-subject")).isNotEqualTo(reference);
    }

    @Test
    void keepsTheDerivedValueOpaqueTowardsTheIssuerAndSubject() {
        String value = UserId.fromIssuerAndSubject(ISSUER, SUBJECT).value();

        assertThat(value).doesNotContain(ISSUER, SUBJECT);
    }

    @Test
    void rejectsBlankIssuerSubjectOrValue() {
        assertThatThrownBy(() -> UserId.fromIssuerAndSubject(" ", SUBJECT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UserId.fromIssuerAndSubject(ISSUER, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UserId(" ")).isInstanceOf(IllegalArgumentException.class);
    }
}
