package com.videogameplatform.catalogue.application.synchronization.port;

/**
 * A provider call that produced no usable normalized result.
 *
 * <p>The message is the stable code alone: provider payloads, response bodies, URLs and
 * credentials never reach a log, a metric or the run record through this exception.
 */
public final class ProviderRequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ProviderFailureCode code;
    private final transient ProviderCallStatistics statistics;

    public ProviderRequestException(ProviderFailureCode code, ProviderCallStatistics statistics) {
        super(code.name());
        this.code = code;
        this.statistics = statistics;
    }

    public ProviderFailureCode code() {
        return code;
    }

    public ProviderCallStatistics statistics() {
        return statistics == null ? ProviderCallStatistics.none() : statistics;
    }
}
