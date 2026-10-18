package com.videogameplatform.identity.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.identity.application.ProductAuthorities;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;

class ProductRoleAuthoritiesTest {
    private final ProductRoleAuthorities mapper = new ProductRoleAuthorities();

    @Test
    void grantsTheOperatorAuthorityFromTheValidatedIdTokenRoleClaim() {
        var signIn = new OidcUserAuthority(idToken(List.of("catalogue-operator")));

        assertThat(names(mapper.mapAuthorities(List.of(signIn))))
                .containsExactlyInAnyOrder("OIDC_USER", ProductAuthorities.CATALOGUE_OPERATOR);
    }

    @Test
    void keepsTheSignInAuthoritiesAndGrantsNothingWithoutAReviewedRole() {
        var none = new OidcUserAuthority(idToken(null));
        var other = new OidcUserAuthority(idToken(List.of("admin", "CATALOGUE-OPERATOR")));
        var scope = new SimpleGrantedAuthority("SCOPE_openid");

        assertThat(names(mapper.mapAuthorities(List.of(none, scope))))
                .containsExactlyInAnyOrder("OIDC_USER", "SCOPE_openid");
        assertThat(names(mapper.mapAuthorities(List.of(other)))).containsExactly("OIDC_USER");
    }

    @Test
    void ignoresARoleClaimThatOnlyUserInfoStates() {
        var userInfo =
                new OidcUserInfo(
                        Map.of(
                                "sub",
                                "subject",
                                ProductRoleAuthorities.ROLE_CLAIM,
                                List.of("catalogue-operator")));
        var signIn = new OidcUserAuthority(idToken(null), userInfo);

        assertThat(names(mapper.mapAuthorities(List.of(signIn)))).containsExactly("OIDC_USER");
    }

    @Test
    void acceptsASingleValuedClaimAndIgnoresNonTextValues() {
        var single = new OidcUserAuthority(idToken("catalogue-operator"));
        var mixed =
                new OidcUserAuthority(idToken(List.of(7, Map.of("role", "catalogue-operator"))));

        assertThat(names(mapper.mapAuthorities(List.of(single))))
                .contains(ProductAuthorities.CATALOGUE_OPERATOR);
        assertThat(names(mapper.mapAuthorities(List.of(mixed)))).containsExactly("OIDC_USER");
    }

    private static OidcIdToken idToken(Object roles) {
        var token =
                OidcIdToken.withTokenValue("id-token")
                        .issuer("https://identity.example/realms/test")
                        .subject("subject")
                        .audience(List.of("videogame-platform-bff"))
                        .issuedAt(Instant.parse("2026-10-18T10:00:00Z"))
                        .expiresAt(Instant.parse("2026-10-18T10:05:00Z"));
        if (roles != null) {
            token.claim(ProductRoleAuthorities.ROLE_CLAIM, roles);
        }
        return token.build();
    }

    private static List<String> names(Collection<? extends GrantedAuthority> authorities) {
        return authorities.stream().map(GrantedAuthority::getAuthority).toList();
    }
}
