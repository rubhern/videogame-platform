package com.videogameplatform.keycloak;

import java.util.List;
import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.oidc.mappers.AbstractOIDCProtocolMapper;
import org.keycloak.protocol.oidc.mappers.OIDCIDTokenMapper;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.representations.IDToken;

/** Exposes only Keycloak's native session mode, only in the BFF's ID token. */
public final class RememberMeMapper extends AbstractOIDCProtocolMapper implements OIDCIDTokenMapper {
    @Override
    public String getId() {
        return "gameometro-remember-me";
    }

    @Override
    public String getDisplayType() {
        return "Gameometro Remember Me";
    }

    @Override
    public String getDisplayCategory() {
        return TOKEN_MAPPER_CATEGORY;
    }

    @Override
    public String getHelpText() {
        return "Expose native Remember Me in the BFF ID token only.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return List.of();
    }

    @Override
    public IDToken transformIDToken(
            IDToken token,
            ProtocolMapperModel model,
            KeycloakSession session,
            UserSessionModel userSession,
            ClientSessionContext clientSession) {
        if (userSession != null
                && "videogame-platform-bff".equals(
                        clientSession.getClientSession().getClient().getClientId())) {
            token.getOtherClaims().put("gameometro_remember_me", userSession.isRememberMe());
        }
        return token;
    }
}
