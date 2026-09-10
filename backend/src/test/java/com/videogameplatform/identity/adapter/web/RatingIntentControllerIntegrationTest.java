package com.videogameplatform.identity.adapter.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.videogameplatform.identity.adapter.session.RatingReturnContextStore;
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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(RatingIntentController.class)
@Import({
    IdentitySecurityConfiguration.class,
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
        return oidcLogin()
                .clientRegistration(clientRegistration())
                .idToken(
                        token ->
                                token.issuer(ISSUER).subject(subject).audience(List.of(CLIENT_ID)));
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
