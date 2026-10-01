package com.videogameplatform.identity.configuration;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/** Adds only supported presentation parameters to Spring's existing state/nonce/PKCE request. */
final class AccountEntryAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {
    private final OAuth2AuthorizationRequestResolver delegate;

    AccountEntryAuthorizationRequestResolver(OAuth2AuthorizationRequestResolver delegate) {
        this.delegate = delegate;
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return customize(delegate.resolve(request), request);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String registrationId) {
        return customize(delegate.resolve(request, registrationId), request);
    }

    private static OAuth2AuthorizationRequest customize(
            OAuth2AuthorizationRequest authorization, HttpServletRequest request) {
        if (authorization == null) return null;
        return OAuth2AuthorizationRequest.from(authorization)
                .additionalParameters(
                        parameters -> {
                            parameters.put("ui_locales", "es");
                            if ("register".equals(request.getParameter("intent"))) {
                                parameters.put("prompt", "create");
                            }
                        })
                .build();
    }
}
