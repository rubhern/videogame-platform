package com.videogameplatform.identity.adapter.web;

import com.videogameplatform.identity.application.ProductAuthorities;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;

/**
 * Grants product authorities at sign-in from the identity provider's client roles.
 *
 * <p>The roles are read only from the validated ID token that this BFF client received, never from
 * user info, a request or the browser, and only the reviewed role names map; any other value is
 * ignored. The identity provider's realm configuration owns who holds a role.
 */
public final class ProductRoleAuthorities implements GrantedAuthoritiesMapper {
    /** The ID token claim the BFF client's role mapper writes. */
    public static final String ROLE_CLAIM = "product_roles";

    private static final Map<String, String> AUTHORITIES =
            Map.of("catalogue-operator", ProductAuthorities.CATALOGUE_OPERATOR);

    @Override
    public Collection<? extends GrantedAuthority> mapAuthorities(
            Collection<? extends GrantedAuthority> authorities) {
        Set<GrantedAuthority> mapped = new LinkedHashSet<>(authorities);
        for (GrantedAuthority authority : authorities) {
            if (authority instanceof OidcUserAuthority oidc) {
                List<String> roles = roles(oidc.getIdToken().getClaims().get(ROLE_CLAIM));
                for (String role : roles) {
                    String granted = AUTHORITIES.get(role);
                    if (granted != null) {
                        mapped.add(new SimpleGrantedAuthority(granted));
                    }
                }
            }
        }
        return mapped;
    }

    private static List<String> roles(Object claim) {
        if (claim instanceof String single) {
            return List.of(single);
        }
        if (claim instanceof Collection<?> values) {
            return values.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
        }
        return List.of();
    }
}
