package com.videogameplatform.api.delivery;

import com.videogameplatform.catalogue.application.CatalogueNotReadyException;
import com.videogameplatform.catalogue.application.CatalogueReadException;
import com.videogameplatform.catalogue.application.ReleaseQueryValidationException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** Bounded-cardinality telemetry for the release query. */
@Component
final class ReleaseApiMetrics {

    private final MeterRegistry registry;

    ReleaseApiMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (String view : new String[] {"recent", "upcoming"}) {
            DistributionSummary.builder("catalogue.releases.result.count")
                    .description("Number of release items returned by a successful request")
                    .tag("view", view)
                    .register(registry);
        }
    }

    long start() {
        return System.nanoTime();
    }

    void complete(String view, Outcome outcome, Integer resultCount, long startedAt) {
        String safeView = safeView(view);
        Counter.builder("catalogue.releases.requests")
                .tag("view", safeView)
                .tag("outcome", outcome.value)
                .register(registry)
                .increment();
        if (resultCount != null) {
            DistributionSummary.builder("catalogue.releases.result.count")
                    .tag("view", safeView)
                    .register(registry)
                    .record(resultCount);
        }
        recordDuration(safeView, outcome.value, startedAt);
    }

    void failure(String view, RuntimeException exception, long startedAt) {
        String safeView = safeView(view);
        String code = failureCode(exception);
        Outcome outcome = failureOutcome(exception);
        Counter.builder("catalogue.releases.requests")
                .tag("view", safeView)
                .tag("outcome", outcome.value)
                .register(registry)
                .increment();
        Counter.builder("catalogue.releases.failures")
                .tag("code", code)
                .register(registry)
                .increment();
        recordDuration(safeView, outcome.value, startedAt);
    }

    void validationFailure(String view, RuntimeException exception) {
        failure(view, exception, start());
    }

    private void recordDuration(String view, String outcome, long startedAt) {
        Timer.builder("catalogue.releases.latency")
                .tag("view", view)
                .tag("outcome", outcome)
                .register(registry)
                .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
    }

    private static String safeView(String view) {
        if (view == null) {
            return "invalid";
        }
        String normalized = view.toLowerCase(Locale.ROOT);
        return "recent".equals(normalized) || "upcoming".equals(normalized)
                ? normalized
                : "invalid";
    }

    private static String failureCode(RuntimeException exception) {
        if (exception instanceof ApiRequestException requestException) {
            return requestException.code();
        }
        if (exception instanceof ReleaseQueryValidationException validationException) {
            return validationException.code().name();
        }
        if (exception instanceof CatalogueNotReadyException) {
            return "CATALOGUE_NOT_READY";
        }
        if (exception instanceof CatalogueReadException) {
            return "CATALOGUE_READ_FAILED";
        }
        return "INTERNAL_ERROR";
    }

    private static Outcome failureOutcome(RuntimeException exception) {
        if (exception instanceof ApiRequestException
                || exception instanceof ReleaseQueryValidationException) {
            return Outcome.VALIDATION_ERROR;
        }
        if (exception instanceof CatalogueNotReadyException
                || exception instanceof CatalogueReadException) {
            return Outcome.READ_FAILURE;
        }
        return Outcome.INTERNAL_ERROR;
    }

    enum Outcome {
        SUCCESS("success"),
        EMPTY("empty"),
        NOT_MODIFIED("not_modified"),
        VALIDATION_ERROR("validation_error"),
        READ_FAILURE("read_failure"),
        INTERNAL_ERROR("internal_error");

        private final String value;

        Outcome(String value) {
            this.value = value;
        }
    }
}
