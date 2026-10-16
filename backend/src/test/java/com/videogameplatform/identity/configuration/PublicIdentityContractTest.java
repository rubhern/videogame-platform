package com.videogameplatform.identity.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

class PublicIdentityContractTest {

    // Synthetic reserved names: the real public domain is not decided yet.
    private static final String ISSUER = "https://identity.gameometro.test/realms/gameometro";
    private static final String APPLICATION = "https://gameometro.test";
    private static final String END_SESSION = ISSUER + "/protocol/openid-connect/logout";
    private static final String POST_LOGOUT = APPLICATION + "/";
    private static final String COOKIE = "__Host-vgp_session";

    @Test
    void acceptsTheCanonicalProductionConfiguration() {
        assertThat(
                        violations(
                                ISSUER,
                                registration(UnaryOperator.identity()),
                                END_SESSION,
                                POST_LOGOUT))
                .isEmpty();
        assertThatCode(
                        () ->
                                PublicIdentityContract.verify(
                                        ISSUER,
                                        registration(UnaryOperator.identity()),
                                        END_SESSION,
                                        POST_LOGOUT,
                                        COOKIE,
                                        true))
                .doesNotThrowAnyException();
    }

    @Test
    void leavesLocalAndPrivateDevRealmsUnchecked() {
        ClientRegistration local =
                ClientRegistration.withRegistrationId("keycloak")
                        .clientId("videogame-platform-bff")
                        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                        .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                        .authorizationUri(
                                "http://localhost:8180/realms/videogame-platform/protocol/openid-connect/auth")
                        .tokenUri(
                                "http://keycloak:8080/realms/videogame-platform/protocol/openid-connect/token")
                        .build();

        assertThat(
                        PublicIdentityContract.applies(
                                "http://localhost:8180/realms/videogame-platform"))
                .isFalse();
        assertThatCode(
                        () ->
                                PublicIdentityContract.verify(
                                        "http://localhost:8180/realms/videogame-platform",
                                        local,
                                        "http://localhost:8180/realms/videogame-platform/protocol/openid-connect/logout",
                                        "{baseUrl}/",
                                        "vgp_session",
                                        false))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://identity.gameometro.test/realms/gameometro",
                "https://identity.gameometro.test:443/realms/gameometro",
                "https://identity.gameometro.test:8443/realms/gameometro",
                "https://identity.gameometro.test/realms/gameometro/",
                "https://Identity.Gameometro.test/realms/gameometro",
                "https://user@identity.gameometro.test/realms/gameometro",
                "https://identity.gameometro.test/realms/gameometro?x=1",
                "https://identity.gameometro.test/realms/gameometro#x",
                "https://127.0.0.1/realms/gameometro",
                "https://localhost/realms/gameometro",
                "https://identity.gameometro.test/auth/realms/gameometro",
                "https://identity.gameometro.test/realms/gameometro-staging"
            })
    void rejectsANonCanonicalIssuerEvenWhenEveryEndpointFollowsIt(String issuer) {
        ClientRegistration followingIssuer =
                registration(
                        builder ->
                                builder.authorizationUri(issuer + "/protocol/openid-connect/auth")
                                        .tokenUri(issuer + "/protocol/openid-connect/token")
                                        .jwkSetUri(issuer + "/protocol/openid-connect/certs")
                                        .userInfoUri(issuer + "/protocol/openid-connect/userinfo"));

        assertThat(PublicIdentityContract.applies(issuer)).isTrue();
        assertThat(
                        violations(
                                issuer,
                                followingIssuer,
                                issuer + "/protocol/openid-connect/logout",
                                POST_LOGOUT))
                .anySatisfy(violation -> assertThat(violation).startsWith("OIDC_ISSUER_URI"));
    }

    @Test
    void rejectsProviderEndpointsOutsideTheCanonicalIssuer() {
        ClientRegistration internal =
                registration(
                        builder ->
                                builder.authorizationUri(
                                                "https://identity.gameometro.test:443/realms/gameometro/protocol/openid-connect/auth")
                                        .tokenUri(
                                                "http://keycloak:8080/realms/gameometro/protocol/openid-connect/token")
                                        .jwkSetUri(
                                                "https://other.gameometro.test/realms/gameometro/protocol/openid-connect/certs")
                                        .userInfoUri(
                                                "https://identity.gameometro.test/realms/videogame-platform/protocol/openid-connect/userinfo"));

        assertThat(
                        violations(
                                ISSUER,
                                internal,
                                "https://identity.gameometro.test/realms/gameometro/protocol/openid-connect/logout/",
                                POST_LOGOUT))
                .containsExactlyInAnyOrder(
                        "OIDC_AUTHORIZATION_URI must be exactly the issuer's /protocol/openid-connect/auth",
                        "OIDC_TOKEN_URI must be exactly the issuer's /protocol/openid-connect/token",
                        "OIDC_JWK_SET_URI must be exactly the issuer's /protocol/openid-connect/certs",
                        "OIDC_USER_INFO_URI must be exactly the issuer's /protocol/openid-connect/userinfo",
                        "OIDC_END_SESSION_URI must be exactly the issuer's /protocol/openid-connect/logout");
    }

    @Test
    void rejectsReturnAddressesBuiltFromTheRequestOrOutsideTheApplicationOrigin() {
        assertThat(
                        violations(
                                ISSUER,
                                registration(
                                        builder ->
                                                builder.redirectUri(
                                                        "{baseUrl}/login/oauth2/code/{registrationId}")),
                                END_SESSION,
                                "{baseUrl}/"))
                .containsExactlyInAnyOrder(
                        "APPLICATION_OIDC_REDIRECT_URI must be explicit, not built from the request",
                        "APPLICATION_OIDC_POST_LOGOUT_REDIRECT_URI must be explicit, not built from the request");

        assertThat(
                        violations(
                                ISSUER,
                                registration(
                                        builder ->
                                                builder.redirectUri(
                                                        APPLICATION + "/login/oauth2/code/other")),
                                END_SESSION,
                                "https://other.gameometro.test/"))
                .containsExactlyInAnyOrder(
                        "APPLICATION_OIDC_REDIRECT_URI path must be exactly /login/oauth2/code/keycloak",
                        "APPLICATION_OIDC_REDIRECT_URI and APPLICATION_OIDC_POST_LOGOUT_REDIRECT_URI must share the application origin");

        assertThat(
                        violations(
                                ISSUER,
                                registration(UnaryOperator.identity()),
                                END_SESSION,
                                APPLICATION + "/games"))
                .containsExactly(
                        "APPLICATION_OIDC_POST_LOGOUT_REDIRECT_URI path must be exactly /");
        assertThat(
                        violations(
                                ISSUER,
                                registration(UnaryOperator.identity()),
                                END_SESSION,
                                "http://gameometro.test/"))
                .singleElement()
                .asString()
                .startsWith("APPLICATION_OIDC_POST_LOGOUT_REDIRECT_URI must be an https URL");
    }

    @Test
    void rejectsSharingTheIdentityOrigin() {
        assertThat(
                        violations(
                                ISSUER,
                                registration(
                                        builder ->
                                                builder.redirectUri(
                                                        "https://identity.gameometro.test/login/oauth2/code/keycloak")),
                                END_SESSION,
                                "https://identity.gameometro.test/"))
                .containsExactly("the application origin must differ from the identity origin");
    }

    @Test
    void requiresAHostOnlySecureSessionCookieAndRefusesToStart() {
        assertThat(
                        PublicIdentityContract.violations(
                                ISSUER,
                                registration(UnaryOperator.identity()),
                                END_SESSION,
                                POST_LOGOUT,
                                "vgp_session",
                                false))
                .containsExactlyInAnyOrder(
                        "APPLICATION_SESSION_COOKIE_SECURE must be true",
                        "APPLICATION_SESSION_COOKIE_NAME must start with __Host-");

        assertThatThrownBy(
                        () ->
                                PublicIdentityContract.verify(
                                        ISSUER,
                                        registration(UnaryOperator.identity()),
                                        END_SESSION,
                                        POST_LOGOUT,
                                        "vgp_session",
                                        true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "Public identity contract violated; refusing to start:"
                                + " APPLICATION_SESSION_COOKIE_NAME must start with __Host-");
    }

    private static java.util.List<String> violations(
            String issuer, ClientRegistration registration, String endSession, String postLogout) {
        return PublicIdentityContract.violations(
                issuer, registration, endSession, postLogout, COOKIE, true);
    }

    private static ClientRegistration registration(
            UnaryOperator<ClientRegistration.Builder> customization) {
        return customization
                .apply(
                        ClientRegistration.withRegistrationId("keycloak")
                                .clientId("videogame-platform-bff")
                                .clientSecret("synthetic")
                                .clientAuthenticationMethod(
                                        ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                                .scope("openid")
                                .redirectUri(APPLICATION + "/login/oauth2/code/keycloak")
                                .authorizationUri(ISSUER + "/protocol/openid-connect/auth")
                                .tokenUri(ISSUER + "/protocol/openid-connect/token")
                                .jwkSetUri(ISSUER + "/protocol/openid-connect/certs")
                                .userInfoUri(ISSUER + "/protocol/openid-connect/userinfo")
                                .userNameAttributeName("sub"))
                .build();
    }
}
