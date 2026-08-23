package com.videogameplatform.identity.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.authentication.OAuth2LoginAuthenticationToken;
import org.springframework.security.oauth2.client.oidc.authentication.OidcAuthorizationCodeAuthenticationProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

class OidcIdTokenValidationTest {

    private static final String ISSUER = "https://identity.example/realms/test";
    private static final String CLIENT_ID = "test-bff";

    @Test
    void acceptsTheReviewedIssuerAudienceAndLifetime() {
        assertThat(validator().validate(idToken(ISSUER, List.of(CLIENT_ID), future())).hasErrors())
                .isFalse();
    }

    @Test
    void rejectsAnUnexpectedIssuerAudienceOrExpiredToken() {
        assertThat(
                        validator()
                                .validate(
                                        idToken(
                                                "https://attacker.example/realms/test",
                                                List.of(CLIENT_ID),
                                                future()))
                                .hasErrors())
                .isTrue();
        assertThat(
                        validator()
                                .validate(idToken(ISSUER, List.of("another-client"), future()))
                                .hasErrors())
                .isTrue();
        assertThat(
                        validator()
                                .validate(
                                        idToken(
                                                ISSUER,
                                                List.of(CLIENT_ID),
                                                Instant.now().minusSeconds(300)))
                                .hasErrors())
                .isTrue();
    }

    @Test
    void frameworkDecoderRejectsAnIdTokenWithAnUntrustedSignature() throws Exception {
        KeyPair trusted = rsaKeyPair();
        KeyPair attacker = rsaKeyPair();
        NimbusJwtDecoder decoder =
                NimbusJwtDecoder.withPublicKey((RSAPublicKey) trusted.getPublic()).build();
        decoder.setJwtValidator(validator());

        assertThatThrownBy(() -> decoder.decode(signedToken(attacker)))
                .isInstanceOf(JwtException.class);
        assertThat(decoder.decode(signedToken(trusted)).getSubject()).isEqualTo("test-subject");
    }

    @Test
    void frameworkDecoderFactoryAppliesTheExternallyConfiguredIssuerValidator() {
        var factory =
                new IdentitySecurityConfiguration().oidcIdTokenDecoderFactory(ISSUER);

        assertThat(factory.createDecoder(clientRegistration())).isNotNull();
    }

    @Test
    void frameworkProviderRejectsAnIdTokenWhoseNonceIsNotBoundToTheAuthorizationRequest() {
        OAuth2AccessTokenResponse tokenResponse =
                OAuth2AccessTokenResponse.withToken("server-side-access-token")
                        .tokenType(OAuth2AccessToken.TokenType.BEARER)
                        .expiresIn(300)
                        .scopes(Set.of("openid"))
                        .additionalParameters(
                                Map.of(OidcParameterNames.ID_TOKEN, "encoded-id-token"))
                        .build();
        OidcAuthorizationCodeAuthenticationProvider provider =
                new OidcAuthorizationCodeAuthenticationProvider(
                        ignored -> tokenResponse,
                        ignored -> {
                            throw new AssertionError("Nonce rejection must precede user loading");
                        });
        provider.setJwtDecoderFactory(
                ignored -> token -> idToken(ISSUER, List.of(CLIENT_ID), future(), "wrong-nonce"));

        assertThatThrownBy(() -> provider.authenticate(loginAuthentication("request-nonce")))
                .isInstanceOfSatisfying(
                        OAuth2AuthenticationException.class,
                        exception ->
                                assertThat(exception.getError().getErrorCode())
                                        .isEqualTo("invalid_nonce"));
    }

    private static OAuth2TokenValidator<Jwt> validator() {
        return IdentitySecurityConfiguration.idTokenValidators(clientRegistration(), ISSUER);
    }

    private static ClientRegistration clientRegistration() {
        return ClientRegistration.withRegistrationId("keycloak")
                .clientId(CLIENT_ID)
                .clientSecret("server-only-test-secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid")
                .authorizationUri("http://localhost:8180/realms/test/protocol/openid-connect/auth")
                .tokenUri("http://keycloak:8080/realms/test/protocol/openid-connect/token")
                .jwkSetUri("http://keycloak:8080/realms/test/protocol/openid-connect/certs")
                .userInfoUri("http://keycloak:8080/realms/test/protocol/openid-connect/userinfo")
                .userNameAttributeName("sub")
                .clientName("Test Keycloak")
                .build();
    }

    private static Jwt idToken(String issuer, List<String> audience, Instant expiresAt) {
        return idToken(issuer, audience, expiresAt, null);
    }

    private static Jwt idToken(
            String issuer, List<String> audience, Instant expiresAt, String nonce) {
        Instant now = Instant.now();
        Instant issuedAt =
                expiresAt.isBefore(now) ? expiresAt.minusSeconds(300) : now.minusSeconds(5);
        Jwt.Builder token =
                Jwt.withTokenValue("opaque-test-value")
                        .header("alg", "RS256")
                        .issuer(issuer)
                        .subject("test-subject")
                        .audience(audience)
                        .issuedAt(issuedAt)
                        .expiresAt(expiresAt);
        if (nonce != null) {
            token.claim(OidcParameterNames.NONCE, nonce);
        }
        return token.build();
    }

    private static OAuth2LoginAuthenticationToken loginAuthentication(String requestNonce) {
        String callback = "https://application.example/login/oauth2/code/keycloak";
        OAuth2AuthorizationRequest authorizationRequest =
                OAuth2AuthorizationRequest.authorizationCode()
                        .authorizationUri(ISSUER + "/protocol/openid-connect/auth")
                        .clientId(CLIENT_ID)
                        .redirectUri(callback)
                        .scopes(Set.of("openid"))
                        .state("matching-state")
                        .attributes(
                                attributes ->
                                        attributes.put(OidcParameterNames.NONCE, requestNonce))
                        .build();
        OAuth2AuthorizationResponse authorizationResponse =
                OAuth2AuthorizationResponse.success("authorization-code")
                        .redirectUri(callback)
                        .state("matching-state")
                        .build();
        return new OAuth2LoginAuthenticationToken(
                clientRegistration(),
                new OAuth2AuthorizationExchange(authorizationRequest, authorizationResponse));
    }

    private static Instant future() {
        return Instant.now().plusSeconds(300);
    }

    private static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String signedToken(KeyPair keyPair) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims =
                new JWTClaimsSet.Builder()
                        .issuer(ISSUER)
                        .subject("test-subject")
                        .audience(CLIENT_ID)
                        .issueTime(Date.from(now.minusSeconds(5)))
                        .expirationTime(Date.from(now.plusSeconds(300)))
                        .build();
        SignedJWT token = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        token.sign(new RSASSASigner((RSAPrivateKey) keyPair.getPrivate()));
        return token.serialize();
    }
}
