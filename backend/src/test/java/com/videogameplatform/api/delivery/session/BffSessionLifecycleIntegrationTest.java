package com.videogameplatform.api.delivery.session;

import static com.videogameplatform.test.RegisteredOidcSession.oidcSession;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.videogameplatform.identity.adapter.session.PendingIdentityProviderLogout;
import com.videogameplatform.identity.adapter.session.RatingReturnContextStore;
import com.videogameplatform.identity.adapter.web.LogoutCompletionController;
import com.videogameplatform.identity.adapter.web.SessionRevocation;
import com.videogameplatform.identity.configuration.AuthenticationProblemEntryPoint;
import com.videogameplatform.identity.configuration.CsrfProblemAccessDeniedHandler;
import com.videogameplatform.identity.configuration.IdentitySecurityConfiguration;
import com.videogameplatform.identity.configuration.RatingIntentAuthenticationFailureHandler;
import com.videogameplatform.identity.configuration.RatingResumeAuthenticationSuccessHandler;
import com.videogameplatform.identity.domain.UserId;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

/**
 * BFF session lifecycle: the absolute lifetime, sessions revocation cannot reach, revoked sessions
 * and the logout that continues through the identity provider (ADR-0027).
 */
@WebMvcTest({SessionController.class, LogoutCompletionController.class})
@Import({
    IdentitySecurityConfiguration.class,
    AuthenticationProblemEntryPoint.class,
    CsrfProblemAccessDeniedHandler.class,
    RatingResumeAuthenticationSuccessHandler.class,
    RatingIntentAuthenticationFailureHandler.class,
    BffSessionLifecycleIntegrationTest.SupportConfiguration.class
})
@TestPropertySource(
        properties = {
            "identity.session.absolute-timeout=8h",
            "identity.oidc.end-session-uri=" + BffSessionLifecycleIntegrationTest.END_SESSION,
            "identity.oidc.post-logout-redirect-uri={baseUrl}/"
        })
class BffSessionLifecycleIntegrationTest {

    static final String END_SESSION =
            "https://identity.example/realms/test/protocol/openid-connect/logout";
    private static final String ISSUER = "https://identity.example/realms/test";
    private static final Instant SIGN_IN = Instant.parse("2026-10-15T08:00:00Z");

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final MutableClock clock;
    private final SessionRevocation revocation;

    @Autowired
    BffSessionLifecycleIntegrationTest(
            MockMvc mockMvc,
            ObjectMapper objectMapper,
            MutableClock clock,
            SessionRevocation revocation) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.revocation = revocation;
    }

    // The registry is application state shared by every test in this context.
    private final String alice = "alice-" + UUID.randomUUID();
    private final String bob = "bob-" + UUID.randomUUID();

    @BeforeEach
    void startAtSignIn() {
        clock.set(SIGN_IN.plusSeconds(1));
    }

    @Test
    void sessionEndsEightHoursAfterAuthenticationEvenWithContinuousActivity() throws Exception {
        MockHttpSession session = signIn(alice, SIGN_IN);

        for (Duration activity :
                List.of(Duration.ofMinutes(25), Duration.ofHours(4), Duration.ofHours(7))) {
            clock.set(SIGN_IN.plus(activity));
            assertAuthenticated(session, true);
        }
        clock.set(SIGN_IN.plus(Duration.ofHours(8)).minusSeconds(1));
        assertAuthenticated(session, true);

        clock.set(SIGN_IN.plus(Duration.ofHours(8)));
        assertAuthenticated(session, false);
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void lifetimeFallsBackToIssuedAtAndRefusesATokenWithNeither() throws Exception {
        MockHttpSession issuedOnly =
                signIn(
                        token ->
                                token.issuer(ISSUER)
                                        .subject(alice)
                                        .claims(claims -> claims.remove("auth_time"))
                                        .issuedAt(SIGN_IN));
        clock.set(SIGN_IN.plus(Duration.ofHours(8)));
        assertAuthenticated(issuedOnly, false);

        clock.set(SIGN_IN.plusSeconds(1));
        mockMvc.perform(
                        get("/api/v1/session")
                                .with(
                                        oidcSession(
                                                SessionSecurityIntegrationTest.clientRegistration(),
                                                token ->
                                                        token.issuer(ISSUER)
                                                                .subject(alice)
                                                                .claims(
                                                                        claims -> {
                                                                            claims.remove(
                                                                                    "auth_time");
                                                                            claims.remove("iat");
                                                                        }))))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"authenticated\":false}", JsonCompareMode.STRICT));
    }

    @Test
    void anOidcSessionRevocationCannotReachIsEnded() throws Exception {
        MockHttpSession unregistered = new MockHttpSession();

        mockMvc.perform(
                        get("/api/v1/session")
                                .session(unregistered)
                                .with(
                                        oidcLogin()
                                                .idToken(
                                                        token ->
                                                                token.issuer(ISSUER)
                                                                        .subject(alice)
                                                                        .issuedAt(SIGN_IN)
                                                                        .authTime(SIGN_IN))))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"authenticated\":false}", JsonCompareMode.STRICT));

        assertThat(unregistered.isInvalid()).isTrue();
    }

    @Test
    void revokedSessionsOfOneUserBecomeAnonymousOnTheirNextRequestWhileOthersContinue()
            throws Exception {
        MockHttpSession aliceLaptop = signIn(alice, SIGN_IN);
        MockHttpSession alicePhone = signIn(alice, SIGN_IN);
        MockHttpSession bobBrowser = signIn(bob, SIGN_IN);

        assertThat(revocation.revoke(UserId.fromIssuerAndSubject(ISSUER, alice)))
                .isEqualTo(new SessionRevocation.Result(2, true));

        assertAuthenticated(aliceLaptop, false);
        assertAuthenticated(alicePhone, false);
        assertThat(aliceLaptop.isInvalid()).isTrue();
        assertThat(alicePhone.isInvalid()).isTrue();
        assertAuthenticated(bobBrowser, true);
    }

    @Test
    void logoutEndsTheApplicationSessionThenOnlyThatBrowsersIdentityProviderSession()
            throws Exception {
        MockHttpSession aliceBrowser = signIn(alice, SIGN_IN);
        MockHttpSession aliceOtherDevice = signIn(alice, SIGN_IN);
        MockHttpSession bobBrowser = signIn(bob, SIGN_IN);

        MvcResult logout =
                mockMvc.perform(
                                post("/api/v1/session")
                                        .session(aliceBrowser)
                                        .header("X-CSRF-Token", csrfToken(aliceBrowser)))
                        .andExpect(status().isNoContent())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(content().string(""))
                        .andExpect(
                                header().string(
                                                "Set-Cookie",
                                                Matchers.containsString("vgp_session=;")))
                        .andReturn();
        assertThat(aliceBrowser.isInvalid()).isTrue();
        MockHttpSession carrier = (MockHttpSession) logout.getRequest().getSession(false);
        assertThat(carrier).isNotNull().isNotSameAs(aliceBrowser);
        assertAuthenticated(carrier, false);

        MvcResult completion =
                mockMvc.perform(get("/auth/logout").session(carrier))
                        .andExpect(status().isFound())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(
                                header().string(
                                                "Set-Cookie",
                                                Matchers.containsString("vgp_session=;")))
                        .andReturn();
        String location = completion.getResponse().getRedirectedUrl();
        assertThat(location).startsWith(END_SESSION + "?");
        MultiValueMap<String, String> query =
                UriComponentsBuilder.fromUriString(location).build().getQueryParams();
        assertThat(query.keySet())
                .containsExactlyInAnyOrder("id_token_hint", "post_logout_redirect_uri");
        assertThat(query.getFirst("id_token_hint")).isEqualTo("id-token");
        assertThat(query.getFirst("post_logout_redirect_uri")).isEqualTo("http://localhost/");
        assertThat(carrier.isInvalid()).isTrue();

        // The pending logout is single-use and other sessions, also of the same user, continue.
        mockMvc.perform(get("/auth/logout").session(carrier))
                .andExpect(status().isFound())
                .andExpect(
                        redirectedUrl(
                                END_SESSION
                                        + "?client_id=test-bff&post_logout_redirect_uri=http://localhost/"));
        assertAuthenticated(aliceOtherDevice, true);
        assertAuthenticated(bobBrowser, true);
    }

    @Test
    void completingLogoutWithoutAPendingLogoutChangesNothing() throws Exception {
        MockHttpSession aliceBrowser = signIn(alice, SIGN_IN);

        mockMvc.perform(get("/auth/logout").session(aliceBrowser))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        mockMvc.perform(get("/auth/logout"))
                .andExpect(status().isFound())
                .andExpect(
                        redirectedUrl(
                                END_SESSION
                                        + "?client_id=test-bff&post_logout_redirect_uri=http://localhost/"));

        assertAuthenticated(aliceBrowser, true);
    }

    @Test
    void expiredBffCanRequestNativeConfirmationWithoutCsrfOrRetainingAnIdToken() throws Exception {
        MockHttpSession expired = signIn(alice, SIGN_IN);
        clock.set(SIGN_IN.plus(Duration.ofHours(8)));
        mockMvc.perform(
                        get("/auth/logout")
                                .session(expired)
                                .param("returnTo", "https://attacker.example"))
                .andExpect(status().isFound())
                .andExpect(
                        redirectedUrl(
                                END_SESSION
                                        + "?client_id=test-bff&post_logout_redirect_uri=http://localhost/"));
        assertThat(expired.isInvalid()).isTrue();
    }

    @Test
    void browserModeFlagsCannotExtendAnOrdinaryAuthentication() throws Exception {
        clock.set(SIGN_IN.plus(Duration.ofHours(9)));
        mockMvc.perform(
                        get("/api/v1/session")
                                .param("gameometro_remember_me", "true")
                                .header("gameometro_remember_me", "true")
                                .cookie(
                                        new jakarta.servlet.http.Cookie(
                                                "gameometro_remember_me", "true"))
                                .with(
                                        oidcSession(
                                                token ->
                                                        token.issuer(ISSUER)
                                                                .subject(alice)
                                                                .authTime(SIGN_IN)
                                                                .claim(
                                                                        "gameometro_remember_me",
                                                                        false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false));
    }

    @Test
    void anOldPendingLogoutCannotEndANewAuthenticationThroughGet() throws Exception {
        MockHttpSession session = signIn(alice, SIGN_IN);
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setSession(session);
        // A logout carrier may be reused by a different tab's new OIDC sign-in before
        // the first tab follows /auth/logout. That GET must not end the new authentication.
        new PendingIdentityProviderLogout()
                .sendRedirect(
                        request,
                        new org.springframework.mock.web.MockHttpServletResponse(),
                        END_SESSION + "?id_token_hint=old-token");
        mockMvc.perform(get("/auth/logout").session(session))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/"));
        assertThat(session.isInvalid()).isFalse();
        assertAuthenticated(session, true);
    }

    private MockHttpSession signIn(String subject, Instant authenticatedAt) throws Exception {
        return signIn(
                token ->
                        token.issuer(ISSUER)
                                .subject(subject)
                                .audience(List.of("test-bff"))
                                .authTime(authenticatedAt));
    }

    private MockHttpSession signIn(Consumer<OidcIdToken.Builder> idToken) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                get("/api/v1/session")
                                        .with(
                                                oidcSession(
                                                        SessionSecurityIntegrationTest
                                                                .clientRegistration(),
                                                        idToken)))
                        .andExpect(status().isOk())
                        .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private void assertAuthenticated(MockHttpSession session, boolean authenticated)
            throws Exception {
        MvcResult result =
                mockMvc.perform(get("/api/v1/session").session(session))
                        .andExpect(status().isOk())
                        .andReturn();
        if (authenticated) {
            assertThat(
                            objectMapper
                                    .readTree(result.getResponse().getContentAsString())
                                    .path("authenticated")
                                    .asBoolean())
                    .isTrue();
        } else {
            assertThat(result.getResponse().getContentAsString())
                    .isEqualTo("{\"authenticated\":false}");
        }
    }

    private String csrfToken(MockHttpSession session) throws Exception {
        return objectMapper
                .readTree(
                        mockMvc.perform(get("/api/v1/session").session(session))
                                .andExpect(jsonPath("$.authenticated").value(true))
                                .andReturn()
                                .getResponse()
                                .getContentAsString())
                .path("csrfToken")
                .stringValue();
    }

    static final class MutableClock extends Clock {
        private volatile Instant instant = SIGN_IN;

        void set(Instant value) {
            instant = value;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SupportConfiguration {

        @Bean
        ClientRegistrationRepository clientRegistrationRepository() {
            return new InMemoryClientRegistrationRepository(
                    SessionSecurityIntegrationTest.clientRegistration());
        }

        @Bean
        MutableClock clock() {
            return new MutableClock();
        }

        @Bean
        RatingReturnContextStore ratingReturnContextStore(
                jakarta.servlet.http.HttpServletRequest request, Clock clock) {
            return new RatingReturnContextStore(request, clock, Duration.ofMinutes(10));
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
