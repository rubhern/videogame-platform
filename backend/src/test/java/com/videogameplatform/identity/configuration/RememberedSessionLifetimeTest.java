package com.videogameplatform.identity.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

class RememberedSessionLifetimeTest {
    private static final Instant ORIGINAL = Instant.parse("2026-10-25T08:00:00Z");
    private static final Duration ABSOLUTE = Duration.ofHours(8);
    private static final Duration MAXIMUM = Duration.ofDays(30);

    @Test
    void onlyABooleanTrueInTheIdTokenCanExtendTheOriginalBound() {
        var renewed = new MockHttpSession();
        Instant now = ORIGINAL.plus(Duration.ofDays(2));
        SessionLifetimeFilter.created(renewed, now);
        for (Object value :
                List.of(false, "true", "TRUE", "on", 1, List.of(true), Map.of("value", true))) {
            assertThat(within(renewed, user(value), now))
                    .as("unrecognized claim %s", value)
                    .isFalse();
            assertThat(within(renewed, user(value), ORIGINAL.plus(Duration.ofHours(1)))).isTrue();
        }
        assertThat(within(renewed, user(null), now)).isFalse();
        assertThat(within(renewed, user(true), now)).isTrue();
        var merged =
                new DefaultOidcUser(
                        List.of(),
                        user(false).getIdToken(),
                        new OidcUserInfo(Map.of("sub", "alice", "gameometro_remember_me", true)));
        assertThat(within(renewed, merged, now)).isFalse();
    }

    @Test
    void ordinaryReentryNeverResetsEightHoursFromOriginalAuthentication() {
        var renewed = new MockHttpSession();
        SessionLifetimeFilter.created(renewed, ORIGINAL.plus(Duration.ofHours(7)));
        assertThat(within(renewed, user(false), ORIGINAL.plus(ABSOLUTE).minusNanos(1))).isTrue();
        assertThat(within(renewed, user(false), ORIGINAL.plus(ABSOLUTE))).isFalse();
    }

    @Test
    void rememberedSessionsEndAtCreationBoundOrOriginalThirtyDayBoundWhicheverComesFirst() {
        var renewed = new MockHttpSession();
        Instant created = ORIGINAL.plus(Duration.ofDays(2));
        SessionLifetimeFilter.created(renewed, created);
        assertThat(within(renewed, user(true), created.plus(ABSOLUTE).minusNanos(1))).isTrue();
        assertThat(within(renewed, user(true), created.plus(ABSOLUTE))).isFalse();

        created = ORIGINAL.plus(MAXIMUM).minus(Duration.ofHours(1));
        SessionLifetimeFilter.created(renewed, created);
        assertThat(within(renewed, user(true), ORIGINAL.plus(MAXIMUM).minusNanos(1))).isTrue();
        assertThat(within(renewed, user(true), ORIGINAL.plus(MAXIMUM))).isFalse();
    }

    @Test
    void rememberedAuthenticationRequiresOriginalAuthTimeAndServerCreationTime() {
        var session = new MockHttpSession();
        assertThat(within(session, user(true), ORIGINAL)).isFalse();
        SessionLifetimeFilter.created(session, ORIGINAL);
        var missing =
                OidcIdToken.withTokenValue("token")
                        .subject("alice")
                        .issuedAt(ORIGINAL)
                        .expiresAt(ORIGINAL.plusSeconds(300))
                        .claim("gameometro_remember_me", true)
                        .build();
        assertThat(within(session, new DefaultOidcUser(List.of(), missing), ORIGINAL)).isFalse();
    }

    @Test
    void aBackwardClockCorrectionDoesNotInvalidateFreshAuthenticationOrResetItsDeadline() {
        var session = new MockHttpSession();
        SessionLifetimeFilter.created(session, ORIGINAL);
        // This host reproduced a two-second backward correction between successful sign-in
        // and the next request. Trusted timestamps keep their original deadlines.
        assertThat(within(session, user(true), ORIGINAL.minusSeconds(2))).isTrue();
        assertThat(within(session, user(false), ORIGINAL.minusSeconds(2))).isTrue();
        assertThat(within(session, user(true), ORIGINAL.plus(ABSOLUTE))).isFalse();
        assertThat(within(session, user(false), ORIGINAL.plus(ABSOLUTE))).isFalse();
    }

    private static boolean within(MockHttpSession session, OidcUser user, Instant now) {
        return SessionLifetimeFilter.withinLifetime(session, user, now, ABSOLUTE, MAXIMUM);
    }

    private static OidcUser user(Object mode) {
        var token =
                OidcIdToken.withTokenValue("token")
                        .subject("alice")
                        .issuedAt(ORIGINAL)
                        .authTime(ORIGINAL)
                        .expiresAt(ORIGINAL.plusSeconds(300));
        if (mode != null) token.claim("gameometro_remember_me", mode);
        return new DefaultOidcUser(List.of(), token.build());
    }
}
