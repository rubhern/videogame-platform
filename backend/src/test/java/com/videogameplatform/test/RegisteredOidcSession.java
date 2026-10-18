package com.videogameplatform.test;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.context.support.WebApplicationContextUtils;

/**
 * Signs a MockMvc request in as an OIDC session the BFF accepts, as real sign-in leaves it.
 *
 * <p>Real sign-in registers the session in Spring Security's session registry and its validated
 * ID token always carries {@code iat}; the BFF ends any OIDC session lacking either, because
 * revocation could not reach it and its absolute lifetime would be unknown. The ID token is issued
 * and authenticated now, and the customizer may override any claim, including {@code auth_time}.
 */
public final class RegisteredOidcSession {

    private RegisteredOidcSession() {}

    public static RequestPostProcessor oidcSession(Consumer<OidcIdToken.Builder> idToken) {
        return register(oidcLogin().idToken(authenticatedNow(idToken)));
    }

    /**
     * Signs in with the authorities that sign-in derives from the ID token, so a test proves the
     * BFF's own authority mapping rather than authorities it chose itself.
     */
    public static RequestPostProcessor oidcSession(
            Consumer<OidcIdToken.Builder> idToken, GrantedAuthoritiesMapper signInAuthorities) {
        Consumer<OidcIdToken.Builder> claims = authenticatedNow(idToken);
        OidcIdToken.Builder builder = OidcIdToken.withTokenValue("id-token").subject("user");
        claims.accept(builder);
        OidcIdToken token = builder.build();
        return register(
                oidcLogin()
                        .idToken(claims)
                        .authorities(
                                List.copyOf(
                                        signInAuthorities.mapAuthorities(
                                                List.of(new OidcUserAuthority(token))))));
    }

    public static RequestPostProcessor oidcSession(
            ClientRegistration registration, Consumer<OidcIdToken.Builder> idToken) {
        return register(
                oidcLogin().clientRegistration(registration).idToken(authenticatedNow(idToken)));
    }

    private static Consumer<OidcIdToken.Builder> authenticatedNow(
            Consumer<OidcIdToken.Builder> idToken) {
        return token -> {
            Instant now = Instant.now();
            token.issuedAt(now).expiresAt(now.plus(Duration.ofMinutes(5))).authTime(now);
            idToken.accept(token);
        };
    }

    private static RequestPostProcessor register(OidcLoginRequestPostProcessor login) {
        return request -> {
            MockHttpServletRequest authenticated = login.postProcessRequest(request);
            var session = authenticated.getSession(true);
            Object principal = authentication(session.getAttribute(contextKey())).getPrincipal();
            WebApplicationContextUtils.getRequiredWebApplicationContext(
                            authenticated.getServletContext())
                    .getBean(SessionRegistry.class)
                    .registerNewSession(session.getId(), principal);
            return authenticated;
        };
    }

    private static Authentication authentication(Object storedContext) {
        SecurityContext context =
                storedContext instanceof SecurityContext stored
                        ? stored
                        : TestSecurityContextHolder.getContext();
        return context.getAuthentication();
    }

    private static String contextKey() {
        return HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;
    }
}
