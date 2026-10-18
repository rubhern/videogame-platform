package com.videogameplatform.identity.application;

/**
 * Server-derived product authorities an authenticated session may hold.
 *
 * <p>They are granted at sign-in only from the validated ID token's product role claim and stay
 * with the session until it ends; identity is still only issuer and subject.
 */
public final class ProductAuthorities {
    /** Reads catalogue synchronization status, run history and coverage. */
    public static final String CATALOGUE_OPERATOR = "ROLE_CATALOGUE_OPERATOR";

    private ProductAuthorities() {}
}
