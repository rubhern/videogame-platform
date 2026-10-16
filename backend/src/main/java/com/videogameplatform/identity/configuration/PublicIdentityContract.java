package com.videogameplatform.identity.configuration;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.security.oauth2.client.registration.ClientRegistration;

/**
 * Startup contract for a BFF configured against the public-production realm.
 *
 * <p>Every {@code UserId} derives from the exact issuer URL, so the issuer, every provider
 * endpoint and both browser return addresses must be fixed canonical values rather than anything
 * built from a request's {@code Host} or forwarded headers. The contract applies exactly when the
 * issuer names the production realm; local and private-dev realms are left unchanged. A violation
 * stops the application before it can accept a sign-in. Messages name only configuration, never
 * secrets.
 */
final class PublicIdentityContract {

    static final String PRODUCTION_REALM_PATH = "/realms/gameometro";
    static final String CALLBACK_PATH = "/login/oauth2/code/keycloak";
    static final String SESSION_COOKIE_PREFIX = "__Host-";

    private static final String PROTOCOL = "/protocol/openid-connect/";
    private static final Pattern DNS_NAME =
            Pattern.compile("(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z][a-z0-9-]*");

    private PublicIdentityContract() {}

    /** Whether the configured issuer selects the production realm, and so this contract. */
    static boolean applies(String issuer) {
        return issuer != null && issuer.contains(PRODUCTION_REALM_PATH);
    }

    static void verify(
            String issuer,
            ClientRegistration registration,
            String endSessionUri,
            String postLogoutRedirectUri,
            String sessionCookieName,
            boolean sessionCookieSecure) {
        if (!applies(issuer)) {
            return;
        }
        List<String> violations =
                violations(
                        issuer,
                        registration,
                        endSessionUri,
                        postLogoutRedirectUri,
                        sessionCookieName,
                        sessionCookieSecure);
        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "Public identity contract violated; refusing to start: "
                            + String.join("; ", violations));
        }
    }

    static List<String> violations(
            String issuer,
            ClientRegistration registration,
            String endSessionUri,
            String postLogoutRedirectUri,
            String sessionCookieName,
            boolean sessionCookieSecure) {
        List<String> violations = new ArrayList<>();
        URI issuerUri = canonicalHttpsUri("OIDC_ISSUER_URI", issuer, violations);
        if (issuerUri != null && !PRODUCTION_REALM_PATH.equals(issuerUri.getRawPath())) {
            violations.add("OIDC_ISSUER_URI path must be exactly " + PRODUCTION_REALM_PATH);
        }

        ClientRegistration.ProviderDetails provider = registration.getProviderDetails();
        // The BFF reaches the identity server at the canonical issuer with verified TLS, so the
        // browser-facing and back-channel endpoints are all the issuer's own.
        requireEndpoint(
                "OIDC_AUTHORIZATION_URI",
                provider.getAuthorizationUri(),
                issuer,
                "auth",
                violations);
        requireEndpoint("OIDC_TOKEN_URI", provider.getTokenUri(), issuer, "token", violations);
        requireEndpoint("OIDC_JWK_SET_URI", provider.getJwkSetUri(), issuer, "certs", violations);
        requireEndpoint(
                "OIDC_USER_INFO_URI",
                provider.getUserInfoEndpoint().getUri(),
                issuer,
                "userinfo",
                violations);
        requireEndpoint("OIDC_END_SESSION_URI", endSessionUri, issuer, "logout", violations);

        URI callback =
                canonicalHttpsUri(
                        "APPLICATION_OIDC_REDIRECT_URI", registration.getRedirectUri(), violations);
        if (callback != null && !CALLBACK_PATH.equals(callback.getRawPath())) {
            violations.add("APPLICATION_OIDC_REDIRECT_URI path must be exactly " + CALLBACK_PATH);
        }
        URI postLogout =
                canonicalHttpsUri(
                        "APPLICATION_OIDC_POST_LOGOUT_REDIRECT_URI",
                        postLogoutRedirectUri,
                        violations);
        if (postLogout != null && !"/".equals(postLogout.getRawPath())) {
            violations.add("APPLICATION_OIDC_POST_LOGOUT_REDIRECT_URI path must be exactly /");
        }
        if (callback != null
                && postLogout != null
                && !callback.getHost().equals(postLogout.getHost())) {
            violations.add(
                    "APPLICATION_OIDC_REDIRECT_URI and APPLICATION_OIDC_POST_LOGOUT_REDIRECT_URI"
                            + " must share the application origin");
        }
        if (callback != null
                && issuerUri != null
                && callback.getHost().equals(issuerUri.getHost())) {
            violations.add("the application origin must differ from the identity origin");
        }

        if (!sessionCookieSecure) {
            violations.add("APPLICATION_SESSION_COOKIE_SECURE must be true");
        }
        if (sessionCookieName == null || !sessionCookieName.startsWith(SESSION_COOKIE_PREFIX)) {
            violations.add(
                    "APPLICATION_SESSION_COOKIE_NAME must start with " + SESSION_COOKIE_PREFIX);
        }
        return violations;
    }

    private static void requireEndpoint(
            String name, String actual, String issuer, String endpoint, List<String> violations) {
        String expected = issuer + PROTOCOL + endpoint;
        if (!expected.equals(actual)) {
            violations.add(name + " must be exactly the issuer's " + PROTOCOL + endpoint);
        }
    }

    /**
     * Parses an explicit HTTPS URL on the implicit port 443 of a lower-case DNS name: no template,
     * user information, query, fragment, IP literal, {@code localhost} or trailing slash beyond the
     * root, so that one spelling exists for each value.
     */
    private static URI canonicalHttpsUri(String name, String value, List<String> violations) {
        if (value == null || value.isBlank()) {
            violations.add(name + " must be set");
            return null;
        }
        if (value.contains("{")) {
            violations.add(name + " must be explicit, not built from the request");
            return null;
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException exception) {
            violations.add(name + " must be a valid URI");
            return null;
        }
        String host = uri.getHost();
        boolean canonical =
                "https".equals(uri.getScheme())
                        && uri.getRawUserInfo() == null
                        && uri.getPort() == -1
                        && uri.getRawQuery() == null
                        && uri.getRawFragment() == null
                        && host != null
                        && host.equals(host.toLowerCase(Locale.ROOT))
                        && DNS_NAME.matcher(host).matches()
                        && !host.endsWith(".localhost")
                        && uri.getRawPath() != null
                        && !uri.getRawPath().isEmpty()
                        && (uri.getRawPath().equals("/") || !uri.getRawPath().endsWith("/"));
        if (!canonical) {
            violations.add(
                    name
                            + " must be an https URL on a lower-case DNS name with the implicit"
                            + " port and no user information, query, fragment or trailing slash");
            return null;
        }
        return uri;
    }
}
