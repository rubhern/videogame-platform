package com.videogameplatform.catalogue.adapter.operator;

import com.videogameplatform.catalogue.adapter.observability.CatalogueSynchronizationMetrics;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationRequest;
import com.videogameplatform.catalogue.application.synchronization.SynchronizeCatalogueUseCase;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;

/** Private management command; deliberately absent from the product HTTP contract. */
@Endpoint(id = "cataloguesync")
public final class CatalogueSynchronizationEndpoint {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(CatalogueSynchronizationEndpoint.class);
    private final SynchronizeCatalogueUseCase synchronizeCatalogue;
    private final CatalogueSynchronizationMetrics metrics;

    public CatalogueSynchronizationEndpoint(
            SynchronizeCatalogueUseCase synchronizeCatalogue,
            CatalogueSynchronizationMetrics metrics) {
        this.synchronizeCatalogue = synchronizeCatalogue;
        this.metrics = metrics;
    }

    @ReadOperation
    public Object lastRun() {
        return synchronizeCatalogue
                .lastRun()
                .<Object>map(value -> value)
                .orElseGet(() -> Map.of("status", "never_run"));
    }

    @WriteOperation
    public Object synchronize(@Nullable String from, @Nullable String to) {
        final CatalogueSynchronizationRequest request;
        try {
            request =
                    new CatalogueSynchronizationRequest(
                            LocalDate.parse(from == null ? "" : from),
                            LocalDate.parse(to == null ? "" : to));
        } catch (DateTimeParseException | IllegalArgumentException failure) {
            return new WebEndpointResponse<>(
                    Map.of(
                            "code",
                            "INVALID_SYNCHRONIZATION_WINDOW",
                            "detail",
                            "from and to must be ISO dates and from must not be after to"),
                    400);
        }
        var report = synchronizeCatalogue.synchronize(request);
        metrics.recordRun(report);
        LOGGER.info(
                "Catalogue synchronization outcome={} code={} counters={}",
                report.outcome(),
                report.outcomeCode(),
                report.counters());
        return report;
    }
}
