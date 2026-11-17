package com.videogameplatform.keycloak;

import java.util.List;
import org.keycloak.Config;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel.Requirement;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.provider.ProviderConfigProperty;

/**
 * First-broker-login step: an account that Keycloak has just created from Google or Microsoft
 * never passed the registration form, so it must accept the terms before its first session.
 *
 * <p>Only the note set by the built-in "create user if unique" step selects the account; a
 * provider linked to an existing account is left untouched. Nothing else is read or changed.
 */
public final class BrokeredTermsAuthenticatorFactory implements AuthenticatorFactory, Authenticator {
    static final String ID = "gameometro-brokered-terms";
    // AbstractIdpAuthenticator.BROKER_REGISTERED_NEW_USER, kept literal to avoid a private-SPI type.
    static final String NEW_USER_NOTE = "BROKER_REGISTERED_NEW_USER";

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        if ("true".equals(context.getAuthenticationSession().getAuthNote(NEW_USER_NOTE))) {
            context.getUser().addRequiredAction(UserModel.RequiredAction.TERMS_AND_CONDITIONS);
        }
        context.success();
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        context.success();
    }

    @Override
    public boolean requiresUser() {
        return true;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        return true;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {}

    @Override
    public Authenticator create(KeycloakSession session) {
        return this;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayType() {
        return "Gameometro brokered account terms";
    }

    @Override
    public String getReferenceCategory() {
        return "terms-and-conditions";
    }

    @Override
    public String getHelpText() {
        return "Asks an account created by an identity provider to accept the terms before signing in.";
    }

    @Override
    public boolean isConfigurable() {
        return false;
    }

    @Override
    public Requirement[] getRequirementChoices() {
        return new Requirement[] {Requirement.REQUIRED, Requirement.DISABLED};
    }

    @Override
    public boolean isUserSetupAllowed() {
        return false;
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return List.of();
    }

    @Override
    public void init(Config.Scope config) {}

    @Override
    public void postInit(KeycloakSessionFactory factory) {}

    @Override
    public void close() {}
}
