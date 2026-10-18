package com.videogameplatform.identity.configuration;

import com.videogameplatform.identity.adapter.operator.SessionRevocationEndpoint;
import com.videogameplatform.identity.adapter.session.PendingIdentityProviderLogout;
import com.videogameplatform.identity.adapter.web.ProductRoleAuthorities;
import com.videogameplatform.identity.adapter.web.SecurityContextCurrentUser;
import com.videogameplatform.identity.adapter.web.SessionRevocation;
import com.videogameplatform.identity.application.CurrentUser;
import com.videogameplatform.identity.application.ProductAuthorities;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.web.server.ConditionalOnManagementPort;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.CompositeLogoutHandler;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.SessionLimit;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfLogoutHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.session.ConcurrentSessionFilter;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.security.web.session.SessionInformationExpiredEvent;

/** Composition root for the same-origin BFF security boundary. */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class IdentitySecurityConfiguration {

    static final String AUTHORIZATION_BASE_URI = "/auth/login";
    static final String SESSION_PATH = "/api/v1/session";
    static final String ADMINISTRATION_PATHS = "/api/v1/admin/**";
    private static final String CONTENT_SECURITY_POLICY =
            "default-src 'self'; "
                    + "base-uri 'self'; "
                    + "object-src 'none'; "
                    + "frame-ancestors 'none'; "
                    + "form-action 'self'; "
                    + "script-src 'self'; "
                    + "style-src 'self'; "
                    + "img-src 'self' data: https://images.igdb.com; "
                    + "connect-src 'self'";

    @Bean
    SecurityFilterChain applicationSecurity(
            HttpSecurity http,
            ObjectProvider<ClientRegistrationRepository> registrations,
            CsrfProblemAccessDeniedHandler csrfProblemAccessDeniedHandler,
            AuthenticationProblemEntryPoint authenticationProblemEntryPoint,
            CurrentUser currentUser,
            RatingResumeAuthenticationSuccessHandler ratingResumeSuccessHandler,
            RatingIntentAuthenticationFailureHandler ratingIntentFailureHandler,
            SessionRegistry sessionRegistry,
            Clock clock,
            @Value("${identity.session.absolute-timeout}") Duration absoluteSessionTimeout,
            @Value("${identity.oidc.issuer-uri}") String issuer,
            @Value("${identity.oidc.end-session-uri}") String endSessionUri,
            @Value("${identity.oidc.post-logout-redirect-uri}") String postLogoutRedirectUri,
            @Value("${server.servlet.session.cookie.name:vgp_session}") String sessionCookieName,
            @Value("${server.servlet.session.cookie.secure:false}") boolean sessionCookieSecure,
            @Value("${platform.http-security.hsts.enabled:false}") boolean hstsEnabled)
            throws Exception {
        HttpSessionCsrfTokenRepository csrfTokens = new HttpSessionCsrfTokenRepository();
        csrfTokens.setHeaderName("X-CSRF-Token");
        ClientRegistrationRepository repository = registrations.getIfAvailable();
        if (repository != null) {
            ClientRegistration keycloak = repository.findByRegistrationId("keycloak");
            if (keycloak != null) {
                PublicIdentityContract.verify(
                        issuer,
                        keycloak,
                        endSessionUri,
                        postLogoutRedirectUri,
                        sessionCookieName,
                        sessionCookieSecure);
            } else if (PublicIdentityContract.applies(issuer)) {
                throw new IllegalStateException(
                        "Public identity contract violated; refusing to start: no keycloak client"
                                + " registration");
            }
        }
        LogoutSuccessHandler logoutSuccess =
                repository == null
                        ? IdentitySecurityConfiguration::endApplicationSessionOnly
                        : identityProviderLogout(repository, endSessionUri, postLogoutRedirectUri);

        http.authorizeHttpRequests(
                        authorize ->
                                authorize
                                        .requestMatchers("/api/v1/me/**")
                                        .authenticated()
                                        // Catalogue administration needs the operator
                                        // authority; an anonymous request still gets 401.
                                        .requestMatchers(ADMINISTRATION_PATHS)
                                        .hasAuthority(ProductAuthorities.CATALOGUE_OPERATOR)
                                        .anyRequest()
                                        .permitAll())
                .requestCache(cache -> cache.disable())
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokens))
                .addFilterBefore(
                        new SameOriginStateChangeFilter(
                                csrfProblemAccessDeniedHandler,
                                authenticationProblemEntryPoint,
                                currentUser),
                        CsrfFilter.class)
                .exceptionHandling(
                        exceptions ->
                                exceptions
                                        .authenticationEntryPoint(authenticationProblemEntryPoint)
                                        .accessDeniedHandler(csrfProblemAccessDeniedHandler))
                .logout(
                        logout ->
                                logout.logoutRequestMatcher(
                                                PathPatternRequestMatcher.pathPattern(
                                                        HttpMethod.POST, SESSION_PATH))
                                        .clearAuthentication(true)
                                        .invalidateHttpSession(true)
                                        .deleteCookies(sessionCookieName)
                                        .logoutSuccessHandler(logoutSuccess))
                // Registers every OIDC session so revocation can find all of a user's devices.
                // A revoked session continues anonymously: personal APIs answer 401 and public
                // reads keep working.
                .sessionManagement(
                        session ->
                                session.sessionConcurrency(
                                        concurrency ->
                                                concurrency
                                                        .maximumSessions(SessionLimit.UNLIMITED)
                                                        .sessionRegistry(sessionRegistry)
                                                        .expiredSessionStrategy(
                                                                IdentitySecurityConfiguration
                                                                        ::continueAnonymously)))
                .addFilterAfter(
                        new SessionLifetimeFilter(
                                sessionRegistry,
                                clock,
                                absoluteSessionTimeout,
                                new CompositeLogoutHandler(
                                        new CookieClearingLogoutHandler(sessionCookieName),
                                        new CsrfLogoutHandler(csrfTokens),
                                        new SecurityContextLogoutHandler())),
                        ConcurrentSessionFilter.class)
                .headers(
                        headers -> {
                            headers.contentSecurityPolicy(
                                            policy ->
                                                    policy.policyDirectives(
                                                            CONTENT_SECURITY_POLICY))
                                    .contentTypeOptions(Customizer.withDefaults())
                                    .frameOptions(frame -> frame.deny())
                                    .referrerPolicy(
                                            referrer ->
                                                    referrer.policy(
                                                            ReferrerPolicy
                                                                    .STRICT_ORIGIN_WHEN_CROSS_ORIGIN));
                            if (hstsEnabled) {
                                // Tailscale Serve terminates private HTTPS. The private-dev
                                // profile enables this explicitly; local loopback HTTP does not.
                                headers.httpStrictTransportSecurity(
                                        hsts ->
                                                hsts.requestMatcher(request -> true)
                                                        .includeSubDomains(true)
                                                        .preload(false));
                            } else {
                                headers.httpStrictTransportSecurity(hsts -> hsts.disable());
                            }
                        });

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
                            oauth2.loginPage("/auth/start")
                                    .authorizedClientRepository(authorizedClients)
                                    .userInfoEndpoint(
                                            userInfo ->
                                                    userInfo.userAuthoritiesMapper(
                                                            new ProductRoleAuthorities()))
                                    .authorizationEndpoint(
                                            endpoint ->
                                                    endpoint.authorizationRequestResolver(
                                                            new AccountEntryAuthorizationRequestResolver(
                                                                    authorizationRequests)))
                                    .successHandler(ratingResumeSuccessHandler)
                                    .failureHandler(ratingIntentFailureHandler));
        }

        return http.build();
    }

    @Bean
    SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    /** Lets the registry forget sessions the container destroys or renumbers. */
    @Bean
    HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    @Bean
    SessionRevocation sessionRevocation(
            SessionRegistry sessionRegistry,
            @Value("${identity.session.revocation-settle-timeout}") Duration settleTimeout) {
        return new SessionRevocation(sessionRegistry, settleTimeout);
    }

    /** Exists only on a separate management server, never on the product port. */
    @Bean
    @ConditionalOnManagementPort(ManagementPortType.DIFFERENT)
    SessionRevocationEndpoint sessionRevocationEndpoint(SessionRevocation sessionRevocation) {
        return new SessionRevocationEndpoint(sessionRevocation);
    }

    @Bean
    CurrentUser currentUser(SessionRevocation sessionRevocation) {
        return new SecurityContextCurrentUser(sessionRevocation);
    }

    private static void endApplicationSessionOnly(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) {
        response.setHeader("Cache-Control", "no-store");
        response.setStatus(HttpStatus.NO_CONTENT.value());
    }

    /**
     * Ends the browser's identity-provider session after the application session, with Spring
     * Security's standard RP-initiated logout: the ended session's ID token as {@code
     * id_token_hint} and the realm's exact post-logout URI. The provider is configured by explicit
     * endpoints rather than discovery, so its end-session endpoint is supplied the same way.
     */
    private static LogoutSuccessHandler identityProviderLogout(
            ClientRegistrationRepository registrations,
            String endSessionUri,
            String postLogoutRedirectUri) {
        OidcClientInitiatedLogoutSuccessHandler handler =
                new OidcClientInitiatedLogoutSuccessHandler(
                        registrationId ->
                                withEndSessionEndpoint(
                                        registrations.findByRegistrationId(registrationId),
                                        endSessionUri));
        handler.setPostLogoutRedirectUri(postLogoutRedirectUri);
        handler.setRedirectStrategy(new PendingIdentityProviderLogout());
        return handler;
    }

    private static ClientRegistration withEndSessionEndpoint(
            ClientRegistration registration, String endSessionUri) {
        if (registration == null) {
            return null;
        }
        Map<String, Object> metadata =
                new HashMap<>(registration.getProviderDetails().getConfigurationMetadata());
        metadata.put("end_session_endpoint", endSessionUri);
        return ClientRegistration.withClientRegistration(registration)
                .providerConfigurationMetadata(metadata)
                .build();
    }

    private static void continueAnonymously(SessionInformationExpiredEvent event)
            throws IOException, ServletException {
        Objects.requireNonNull(event.getFilterChain(), "filterChain")
                .doFilter(event.getRequest(), event.getResponse());
    }

    @Bean
    JwtDecoderFactory<ClientRegistration> oidcIdTokenDecoderFactory(
            @Value("${identity.oidc.issuer-uri}") String expectedIssuer) {
        OidcIdTokenDecoderFactory factory = new OidcIdTokenDecoderFactory();
        factory.setJwtValidatorFactory(
                registration -> idTokenValidators(registration, expectedIssuer));
        return factory;
    }

    static DelegatingOAuth2TokenValidator<org.springframework.security.oauth2.jwt.Jwt>
            idTokenValidators(ClientRegistration registration, String expectedIssuer) {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(expectedIssuer),
                new OidcIdTokenValidator(registration));
    }
}
