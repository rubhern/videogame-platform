package com.videogameplatform.identity.adapter.web;

import static com.videogameplatform.test.RegisteredOidcSession.oidcSession;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.videogameplatform.identity.adapter.session.RatingReturnContext.PlatformScore;
import com.videogameplatform.identity.adapter.session.RatingReturnContextStore;
import com.videogameplatform.identity.configuration.AuthenticationProblemEntryPoint;
import com.videogameplatform.identity.configuration.CsrfProblemAccessDeniedHandler;
import com.videogameplatform.identity.configuration.IdentitySecurityConfiguration;
import com.videogameplatform.identity.configuration.RatingIntentAuthenticationFailureHandler;
import com.videogameplatform.identity.configuration.RatingResumeAuthenticationSuccessHandler;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest({RatingIntentController.class, AuthenticationEntryController.class})
@Import({
    IdentitySecurityConfiguration.class,
    AuthenticationProblemEntryPoint.class,
    CsrfProblemAccessDeniedHandler.class,
    RatingResumeAuthenticationSuccessHandler.class,
    RatingIntentAuthenticationFailureHandler.class,
    RatingIntentControllerIntegrationTest.SupportConfiguration.class
})
class RatingIntentControllerIntegrationTest {

    private static final String CLIENT_ID = "test-bff";
    private static final String ISSUER = "https://identity.example/realms/test";
    private static final String GAME_ID = "30000000-0000-4000-8000-000000000005";
    private static final String SLUG = "resident-evil-requiem";
    private static final String GAME_PATH = "/games/" + GAME_ID + "/" + SLUG;

    private final MockMvc mockMvc;

    @Autowired
    RatingIntentControllerIntegrationTest(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    @Test
    void generalAccountEntryStoresOnlyABoundedLocalTargetAndStartsExistingOidc() throws Exception {
        MvcResult started =
                mockMvc.perform(get("/auth/start").queryParam("returnTo", "/search?q=zelda"))
                        .andExpect(status().isFound())
                        .andExpect(redirectedUrl("/auth/login/keycloak"))
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andReturn();
        var store =
                new RatingReturnContextStore(
                        started.getRequest(), Clock.systemUTC(), java.time.Duration.ofMinutes(10));
        org.assertj.core.api.Assertions.assertThat(
                        store.takeAuthenticationContext().orElseThrow().path())
                .isEqualTo("/search?q=zelda");
        org.assertj.core.api.Assertions.assertThat(store.takeAuthenticationContext()).isEmpty();
    }

    @Test
    void registrationStartsTheSamePkceFlowWithOnlySupportedPresentationParameters()
            throws Exception {
        mockMvc.perform(
                        get("/auth/start")
                                .param("intent", "register")
                                .param("returnTo", "/search?q=zelda"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/auth/login/keycloak?intent=register"));
        MvcResult authorization =
                mockMvc.perform(
                                get("/auth/login/keycloak")
                                        .param("intent", "register")
                                        .param("state", "attacker")
                                        .param("nonce", "attacker")
                                        .param("code_challenge", "attacker"))
                        .andExpect(status().isFound())
                        .andReturn();
        var params =
                org.springframework.web.util.UriComponentsBuilder.fromUriString(
                                authorization.getResponse().getRedirectedUrl())
                        .build()
                        .getQueryParams();
        org.assertj.core.api.Assertions.assertThat(params.getFirst("prompt")).isEqualTo("create");
        org.assertj.core.api.Assertions.assertThat(params.getFirst("ui_locales")).isEqualTo("es");
        org.assertj.core.api.Assertions.assertThat(params.getFirst("code_challenge_method"))
                .isEqualTo("S256");
        for (String key : List.of("state", "nonce", "code_challenge")) {
            org.assertj.core.api.Assertions.assertThat(params.getFirst(key))
                    .isNotBlank()
                    .isNotEqualTo("attacker");
        }
    }

    @Test
    void compatibilityLoginOnlyRedirectsAndUnknownIntentNeverBecomesAnOidcParameter()
            throws Exception {
        mockMvc.perform(get("/login").param("returnTo", "//attacker.example"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/auth/login/keycloak"));
        MvcResult result =
                mockMvc.perform(get("/auth/login/keycloak").param("intent", "attacker"))
                        .andExpect(status().isFound())
                        .andReturn();
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getRedirectedUrl())
                .contains("ui_locales=es")
                .doesNotContain("prompt=")
                .doesNotContain("attacker");
    }

    @Test
    void authenticatedAccountEntryNeverRedirectsToAnExternalTarget() throws Exception {
        mockMvc.perform(
                        get("/auth/start")
                                .with(authenticated("subject-a"))
                                .queryParam("returnTo", "//attacker.example"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/"));
        mockMvc.perform(
                        get("/auth/start")
                                .with(authenticated("subject-a"))
                                .queryParam("returnTo", GAME_PATH))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl(GAME_PATH));
    }

    @Test
    void anonymousStartStoresTheContextAndEntersTheOidcFlow() throws Exception {
        mockMvc.perform(
                        get("/auth/rating-intent/start")
                                .queryParam("gameId", GAME_ID)
                                .queryParam("slug", SLUG)
                                .queryParam("value", "8"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/auth/login/keycloak"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void anonymousPlatformStartRequiresSameOriginCsrfAndStoresTheWholeSelection() throws Exception {
        String start = "/auth/rating-intent/start";
        var form =
                post(start)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .formField("gameId", GAME_ID)
                        .formField("slug", SLUG)
                        .formField("platform", "platform_ps5:9", "platform_pc:");
        mockMvc.perform(form).andExpect(status().isForbidden());

        MvcResult proof =
                mockMvc.perform(get(start).param("csrf", ""))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andReturn();
        var session = (MockHttpSession) proof.getRequest().getSession(false);
        form.session(session).header("X-CSRF-Token", proof.getResponse().getContentAsString());
        mockMvc.perform(form.header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isForbidden());
        MvcResult started =
                mockMvc.perform(
                                post(start)
                                        .session(session)
                                        .header(
                                                "X-CSRF-Token",
                                                proof.getResponse().getContentAsString())
                                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                                        .formField("gameId", GAME_ID)
                                        .formField("slug", SLUG)
                                        .formField("platform", "platform_ps5:9", "platform_pc:"))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.alreadyAuthenticated").value(false))
                        .andReturn();
        var store =
                new RatingReturnContextStore(
                        started.getRequest(), Clock.systemUTC(), java.time.Duration.ofMinutes(10));
        var selection = store.takeReturnContext().orElseThrow();
        assertThat(selection.gameId()).isEqualTo(GAME_ID);
        assertThat(selection.value()).isNull();
        assertThat(selection.platforms())
                .containsExactly(
                        new PlatformScore("platform_ps5", 9),
                        new PlatformScore("platform_pc", null));
    }

    @Test
    void platformStartResumesDirectlyWhenTheSessionIsAlreadyAuthenticated() throws Exception {
        String start = "/auth/rating-intent/start";
        var principal = authenticated("subject-a");
        MvcResult proof =
                mockMvc.perform(get(start).param("csrf", "").with(principal))
                        .andExpect(status().isOk())
                        .andReturn();
        var session = (MockHttpSession) proof.getRequest().getSession(false);
        mockMvc.perform(
                        post(start)
                                .session(session)
                                .with(principal)
                                .header("X-CSRF-Token", proof.getResponse().getContentAsString())
                                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                                .formField("gameId", GAME_ID)
                                .formField("slug", SLUG)
                                .formField("platform", "platform_ps5:9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyAuthenticated").value(true));
        mockMvc.perform(get("/auth/rating-intent").session(session).with(principal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gameId").value(GAME_ID))
                .andExpect(jsonPath("$.platforms[0].value").value(9));
    }

    @Test
    void platformStartRejectsDuplicateUnscoredOutOfRangeAndOversizeSelections() throws Exception {
        String start = "/auth/rating-intent/start";
        MvcResult proof =
                mockMvc.perform(get(start).param("csrf", ""))
                        .andExpect(status().isOk())
                        .andReturn();
        var session = (MockHttpSession) proof.getRequest().getSession(false);
        var oversized =
                java.util.stream.IntStream.rangeClosed(1, 51)
                        .mapToObj(index -> "platform_" + index + ":9")
                        .toArray(String[]::new);
        for (String[] platforms :
                List.of(
                        new String[] {"platform_ps5:8", "platform_ps5:"},
                        new String[] {"platform_ps5:"},
                        new String[] {"platform_ps5:11"},
                        oversized)) {
            mockMvc.perform(
                            post(start)
                                    .session(session)
                                    .header(
                                            "X-CSRF-Token",
                                            proof.getResponse().getContentAsString())
                                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                                    .formField("gameId", GAME_ID)
                                    .formField("platform", platforms))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void anInvalidValueNeverStartsAuthenticationAndReturnsSafely() throws Exception {
        mockMvc.perform(
                        get("/auth/rating-intent/start")
                                .queryParam("gameId", GAME_ID)
                                .queryParam("slug", SLUG)
                                .queryParam("value", "42"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl(GAME_PATH + "?rating-intent=invalid"));
    }

    @Test
    void anInvalidGameIdFallsBackToTheHomePageWithoutAnOpenRedirect() throws Exception {
        mockMvc.perform(
                        get("/auth/rating-intent/start")
                                .queryParam("gameId", "https://attacker.example/steal")
                                .queryParam("value", "8"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void authenticatedStartRecoversASingleUseSelectionForTheSameUser() throws Exception {
        MvcResult started =
                mockMvc.perform(
                                get("/auth/rating-intent/start")
                                        .with(authenticated("subject-a"))
                                        .queryParam("gameId", GAME_ID)
                                        .queryParam("slug", SLUG)
                                        .queryParam("value", "8"))
                        .andExpect(status().isFound())
                        .andExpect(redirectedUrl(GAME_PATH + "?rating-intent=resumed"))
                        .andReturn();
        MockHttpSession session = (MockHttpSession) started.getRequest().getSession(false);

        mockMvc.perform(
                        get("/auth/rating-intent")
                                .with(authenticated("subject-a"))
                                .session(session))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.gameId").value(GAME_ID))
                .andExpect(jsonPath("$.slug").value(SLUG))
                .andExpect(jsonPath("$.value").value(8));

        mockMvc.perform(
                        get("/auth/rating-intent")
                                .with(authenticated("subject-a"))
                                .session(session))
                .andExpect(status().isNotFound());
    }

    @Test
    void aRecoveredSelectionIsNotReadableByADifferentUser() throws Exception {
        MvcResult started =
                mockMvc.perform(
                                get("/auth/rating-intent/start")
                                        .with(authenticated("subject-a"))
                                        .queryParam("gameId", GAME_ID)
                                        .queryParam("slug", SLUG)
                                        .queryParam("value", "8"))
                        .andReturn();
        MockHttpSession session = (MockHttpSession) started.getRequest().getSession(false);

        mockMvc.perform(
                        get("/auth/rating-intent")
                                .with(authenticated("subject-b"))
                                .session(session))
                .andExpect(status().isNotFound());
    }

    @Test
    void anAnonymousReadReturnsNoRecoveredSelection() throws Exception {
        mockMvc.perform(get("/auth/rating-intent")).andExpect(status().isNotFound());
    }

    private static RequestPostProcessor authenticated(String subject) {
        return oidcSession(
                clientRegistration(),
                token -> token.issuer(ISSUER).subject(subject).audience(List.of(CLIENT_ID)));
    }

    static ClientRegistration clientRegistration() {
        return ClientRegistration.withRegistrationId("keycloak")
                .clientId(CLIENT_ID)
                .clientSecret("server-only-test-secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid")
                .authorizationUri(ISSUER + "/protocol/openid-connect/auth")
                .tokenUri(ISSUER + "/protocol/openid-connect/token")
                .jwkSetUri(ISSUER + "/protocol/openid-connect/certs")
                .issuerUri(ISSUER)
                .userInfoUri(ISSUER + "/protocol/openid-connect/userinfo")
                .userNameAttributeName("sub")
                .clientName("Test Keycloak")
                .build();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SupportConfiguration {

        @Bean
        RatingReturnContextStore ratingReturnContextStore(
                jakarta.servlet.http.HttpServletRequest request, Clock clock) {
            return new RatingReturnContextStore(request, clock, java.time.Duration.ofMinutes(10));
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        ClientRegistrationRepository clientRegistrationRepository() {
            return new InMemoryClientRegistrationRepository(clientRegistration());
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
