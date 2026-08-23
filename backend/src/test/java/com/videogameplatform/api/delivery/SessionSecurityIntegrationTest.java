package com.videogameplatform.api.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.videogameplatform.identity.configuration.CsrfProblemAccessDeniedHandler;
import com.videogameplatform.identity.configuration.IdentitySecurityConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(SessionController.class)
@Import({
    IdentitySecurityConfiguration.class,
    CsrfProblemAccessDeniedHandler.class,
    ReleaseApiMetrics.class,
    SessionSecurityIntegrationTest.ClientRegistrationConfiguration.class
})
class SessionSecurityIntegrationTest {

    private static final String CLIENT_ID = "test-bff";
    private static final String ISSUER = "https://identity.example/realms/test";

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;

    @Autowired
    SessionSecurityIntegrationTest(MockMvc mockMvc, ObjectMapper objectMapper) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
    }

    @Test
    void returnsOnlyTheAnonymousSessionRepresentationWithoutCreatingCsrfMaterial()
            throws Exception {
        mockMvc.perform(get("/api/v1/session").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(content().json("{\"authenticated\":false}", JsonCompareMode.STRICT));
    }

    @Test
    void returnsOnlyAuthenticationStateAndSessionBoundCsrfMaterialForAnOidcSession()
            throws Exception {
        MvcResult result =
                mockMvc.perform(
                                get("/api/v1/session")
                                        .with(
                                                oidcLogin()
                                                        .clientRegistration(clientRegistration())
                                                        .idToken(
                                                                token ->
                                                                        token.subject(
                                                                                        "provider-subject")
                                                                                .audience(
                                                                                        List.of(
                                                                                                CLIENT_ID))))
                                        .accept(MediaType.APPLICATION_JSON))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.authenticated").value(true))
                        .andExpect(jsonPath("$.csrfToken").isNotEmpty())
                        .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.propertyNames()).containsExactly("authenticated", "csrfToken");
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(
                        "access_token",
                        "refresh_token",
                        "id_token",
                        "provider-subject",
                        ISSUER,
                        CLIENT_ID);
    }

    @Test
    void rejectsLogoutWithoutCsrfAndKeepsTheAuthenticatedSession() throws Exception {
        MvcResult sessionResult = authenticatedSession();
        MockHttpSession session = (MockHttpSession) sessionResult.getRequest().getSession(false);

        mockMvc.perform(post("/api/v1/session").session(session))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().exists("X-Correlation-ID"))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CSRF_VALIDATION_FAILED"));

        mockMvc.perform(get("/api/v1/session").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true));
    }

    @Test
    void logoutWithCurrentCsrfInvalidatesTheApplicationSessionAndClearsItsCookie()
            throws Exception {
        MvcResult sessionResult = authenticatedSession();
        MockHttpSession session = (MockHttpSession) sessionResult.getRequest().getSession(false);
        String csrfToken =
                objectMapper
                        .readTree(sessionResult.getResponse().getContentAsString())
                        .path("csrfToken")
                        .stringValue();

        mockMvc.perform(post("/api/v1/session").session(session).header("X-CSRF-Token", csrfToken))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(
                        header().string(
                                        "Set-Cookie",
                                        org.hamcrest.Matchers.containsString("vgp_session=;")));

        assertThat(session.isInvalid()).isTrue();
        mockMvc.perform(get("/api/v1/session"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"authenticated\":false}", JsonCompareMode.STRICT));
    }

    @Test
    void logoutRejectsAnUntrustedOriginEvenWithCurrentCsrfMaterial() throws Exception {
        MvcResult sessionResult = authenticatedSession();
        MockHttpSession session = (MockHttpSession) sessionResult.getRequest().getSession(false);
        String csrfToken =
                objectMapper
                        .readTree(sessionResult.getResponse().getContentAsString())
                        .path("csrfToken")
                        .stringValue();

        mockMvc.perform(
                        post("/api/v1/session")
                                .session(session)
                                .header("X-CSRF-Token", csrfToken)
                                .header("Origin", "https://attacker.example"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_VALIDATION_FAILED"));
    }

    @Test
    void logoutRejectsCrossSiteFetchMetadataEvenWithCurrentCsrfMaterial() throws Exception {
        MvcResult sessionResult = authenticatedSession();
        MockHttpSession session = (MockHttpSession) sessionResult.getRequest().getSession(false);
        String csrfToken =
                objectMapper
                        .readTree(sessionResult.getResponse().getContentAsString())
                        .path("csrfToken")
                        .stringValue();

        mockMvc.perform(
                        post("/api/v1/session")
                                .session(session)
                                .header("X-CSRF-Token", csrfToken)
                                .header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_VALIDATION_FAILED"));
    }

    @Test
    void authorizationRequestUsesStateNoncePkceAndOnlyTheAllowlistedCallback() throws Exception {
        MvcResult result =
                mockMvc.perform(
                                get("/auth/login/keycloak")
                                        .queryParam("returnUrl", "https://attacker.example/steal"))
                        .andExpect(status().is3xxRedirection())
                        .andReturn();

        String location = result.getResponse().getRedirectedUrl();
        assertThat(location).isNotNull().doesNotContain("attacker.example", "returnUrl", "secret");
        MultiValueMap<String, String> query =
                UriComponentsBuilder.fromUriString(location).build().getQueryParams();
        assertThat(query.getFirst("client_id")).isEqualTo(CLIENT_ID);
        assertThat(query.getFirst("redirect_uri"))
                .isEqualTo("http://localhost/login/oauth2/code/keycloak");
        assertThat(query.getFirst("response_type")).isEqualTo("code");
        assertThat(query.getFirst("scope")).contains("openid");
        assertThat(query.getFirst("state")).isNotBlank();
        assertThat(query.getFirst("nonce")).isNotBlank();
        assertThat(query.getFirst("code_challenge")).isNotBlank();
        assertThat(query.getFirst("code_challenge_method")).isEqualTo("S256");
    }

    @Test
    void callbackWithMismatchedStateNeverCreatesAnAuthenticatedSession() throws Exception {
        MvcResult authorization =
                mockMvc.perform(get("/auth/login/keycloak"))
                        .andExpect(status().is3xxRedirection())
                        .andReturn();
        MockHttpSession session = (MockHttpSession) authorization.getRequest().getSession(false);

        mockMvc.perform(
                        get("/login/oauth2/code/keycloak")
                                .session(session)
                                .queryParam("code", "opaque-code")
                                .queryParam("state", "mismatched-state"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));

        mockMvc.perform(get("/api/v1/session").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false));
    }

    private MvcResult authenticatedSession() throws Exception {
        return mockMvc.perform(
                        get("/api/v1/session")
                                .with(oidcLogin().clientRegistration(clientRegistration())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andReturn();
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
    static class ClientRegistrationConfiguration {

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
