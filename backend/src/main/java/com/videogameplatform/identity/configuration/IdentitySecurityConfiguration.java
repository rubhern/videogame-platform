package com.videogameplatform.identity.configuration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/** Composition root for the same-origin BFF security boundary. */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class IdentitySecurityConfiguration {

    static final String AUTHORIZATION_BASE_URI = "/auth/login";
    static final String SESSION_PATH = "/api/v1/session";

    @Bean
    SecurityFilterChain applicationSecurity(
            HttpSecurity http,
            ObjectProvider<ClientRegistrationRepository> registrations,
            CsrfProblemAccessDeniedHandler csrfProblemAccessDeniedHandler,
            @Value("${server.servlet.session.cookie.name:vgp_session}") String sessionCookieName)
            throws Exception {
        HttpSessionCsrfTokenRepository csrfTokens = new HttpSessionCsrfTokenRepository();
        csrfTokens.setHeaderName("X-CSRF-Token");

        http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .requestCache(cache -> cache.disable())
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokens))
                .addFilterBefore(
                        new SameOriginStateChangeFilter(csrfProblemAccessDeniedHandler),
                        CsrfFilter.class)
                .exceptionHandling(
                        exceptions ->
                                exceptions.accessDeniedHandler(csrfProblemAccessDeniedHandler))
                .logout(
                        logout ->
                                logout.logoutRequestMatcher(
                                                PathPatternRequestMatcher.pathPattern(
                                                        HttpMethod.POST, SESSION_PATH))
                                        .clearAuthentication(true)
                                        .invalidateHttpSession(true)
                                        .deleteCookies(sessionCookieName)
                                        .logoutSuccessHandler(
                                                (request, response, authentication) -> {
                                                    response.setHeader("Cache-Control", "no-store");
                                                    response.setStatus(
                                                            HttpStatus.NO_CONTENT.value());
                                                }))
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));

        ClientRegistrationRepository repository = registrations.getIfAvailable();
        if (repository != null) {
            DefaultOAuth2AuthorizationRequestResolver authorizationRequests =
                    new DefaultOAuth2AuthorizationRequestResolver(
                            repository, AUTHORIZATION_BASE_URI);
            authorizationRequests.setAuthorizationRequestCustomizer(
                    OAuth2AuthorizationRequestCustomizers.withPkce());
            HttpSessionOAuth2AuthorizedClientRepository authorizedClients =
                    new HttpSessionOAuth2AuthorizedClientRepository();
            http.oauth2Login(
                    oauth2 ->
                            oauth2.authorizedClientRepository(authorizedClients)
                                    .authorizationEndpoint(
                                            endpoint ->
                                                    endpoint.authorizationRequestResolver(
                                                            authorizationRequests))
                                    .defaultSuccessUrl("/", true)
                                    .failureUrl("/"));
        }

        return http.build();
    }

    @Bean
    JwtDecoderFactory<ClientRegistration> oidcIdTokenDecoderFactory() {
        OidcIdTokenDecoderFactory factory = new OidcIdTokenDecoderFactory();
        factory.setJwtValidatorFactory(IdentitySecurityConfiguration::idTokenValidators);
        return factory;
    }

    static DelegatingOAuth2TokenValidator<org.springframework.security.oauth2.jwt.Jwt>
            idTokenValidators(ClientRegistration registration) {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(
                        registration.getProviderDetails().getIssuerUri()),
                new OidcIdTokenValidator(registration));
    }
}
