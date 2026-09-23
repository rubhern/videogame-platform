package com.videogameplatform.catalogue.application.synchronization.port;

/** Stable provider-failure vocabulary shared by UC-009, its telemetry and the run record. */
public enum ProviderFailureCode {
    PROVIDER_NOT_CONFIGURED(false),
    PROVIDER_AUTHENTICATION_FAILED(true),
    PROVIDER_RATE_LIMITED(true),
    PROVIDER_UNAVAILABLE(false),
    PROVIDER_RESPONSE_INVALID(false);

    private final boolean abortsRun;

    ProviderFailureCode(boolean abortsRun) {
        this.abortsRun = abortsRun;
    }

    /**
     * Whether continuing the run would keep failing or keep pressing an already refusing provider.
     * A single unreadable response only isolates its own game.
     */
    public boolean abortsRun() {
        return abortsRun;
    }
}
