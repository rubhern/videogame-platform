package com.videogameplatform.identity.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.identity.adapter.session.RatingReturnContext;
import com.videogameplatform.identity.adapter.session.RatingReturnContextStore;
import com.videogameplatform.identity.adapter.session.ResumedRatingIntent;
import com.videogameplatform.identity.domain.UserId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

class RatingResumeAuthenticationHandlerTest {

    private static final String ISSUER = "https://identity.example/realms/test";
    private static final String SUBJECT = "provider-subject";
    private static final String GAME_ID = "30000000-0000-4000-8000-000000000005";
    private static final String SLUG = "resident-evil-requiem";
    private static final Instant NOW = Instant.parse("2026-09-09T10:00:00Z");

    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final RatingReturnContextStore store =
            new RatingReturnContextStore(request, clock, Duration.ofMinutes(10));

    @Test
    void resumesTheSameGameWithASingleUseSelectionBoundToTheAuthenticatedUser() throws Exception {
        store.saveReturnContext(new RatingReturnContext(GAME_ID, SLUG, 8, NOW.minusSeconds(30)));

        new RatingResumeAuthenticationSuccessHandler(store)
                .onAuthenticationSuccess(request, response, authentication());

        assertThat(response.getRedirectedUrl())
                .isEqualTo("/games/" + GAME_ID + "/" + SLUG + "?rating-intent=resumed");
        assertThat(store.takeReturnContext()).isEmpty();
        Optional<ResumedRatingIntent> resumed = store.takeResumedIntent();
        assertThat(resumed).isPresent();
        assertThat(resumed.get().gameId()).isEqualTo(GAME_ID);
        assertThat(resumed.get().value()).isEqualTo(8);
        assertThat(resumed.get().userId())
                .isEqualTo(UserId.fromIssuerAndSubject(ISSUER, SUBJECT).value());
    }

    @Test
    void returnsToTheGameWithoutASelectionWhenTheContextHasExpired() throws Exception {
        store.saveReturnContext(new RatingReturnContext(GAME_ID, SLUG, 8, NOW.minusSeconds(601)));

        new RatingResumeAuthenticationSuccessHandler(store)
                .onAuthenticationSuccess(request, response, authentication());

        assertThat(response.getRedirectedUrl())
                .isEqualTo("/games/" + GAME_ID + "/" + SLUG + "?rating-intent=expired");
        assertThat(store.takeResumedIntent()).isEmpty();
    }

    @Test
    void landsOnHomeForAPlainLoginWithoutAPendingContext() throws Exception {
        new RatingResumeAuthenticationSuccessHandler(store)
                .onAuthenticationSuccess(request, response, authentication());

        assertThat(response.getRedirectedUrl()).isEqualTo("/");
        assertThat(store.takeResumedIntent()).isEmpty();
    }

    @Test
    void cancellationDiscardsTheContextAndReturnsToTheGameSafely() throws Exception {
        store.saveReturnContext(new RatingReturnContext(GAME_ID, SLUG, 8, NOW.minusSeconds(30)));

        new RatingIntentAuthenticationFailureHandler(store)
                .onAuthenticationFailure(
                        request,
                        response,
                        new org.springframework.security.authentication.BadCredentialsException(
                                "cancelled"));

        assertThat(response.getRedirectedUrl())
                .isEqualTo("/games/" + GAME_ID + "/" + SLUG + "?rating-intent=cancelled");
        assertThat(store.takeReturnContext()).isEmpty();
        assertThat(store.takeResumedIntent()).isEmpty();
    }

    @Test
    void failureWithoutAContextLandsOnHome() throws Exception {
        new RatingIntentAuthenticationFailureHandler(store)
                .onAuthenticationFailure(
                        request,
                        response,
                        new org.springframework.security.authentication.BadCredentialsException(
                                "failed"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/");
    }

    private static OAuth2AuthenticationToken authentication() {
        OidcIdToken idToken =
                new OidcIdToken(
                        "opaque-id-token",
                        NOW.minusSeconds(60),
                        NOW.plusSeconds(300),
                        Map.of(
                                OidcParameterNames.ID_TOKEN,
                                "opaque",
                                "iss",
                                ISSUER,
                                "sub",
                                SUBJECT));
        DefaultOidcUser user =
                new DefaultOidcUser(AuthorityUtils.createAuthorityList("ROLE_USER"), idToken);
        return new OAuth2AuthenticationToken(user, List.of(), "keycloak");
    }
}
