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

/**
 * Private management command; deliberately absent from the product HTTP contract.
 *
 * <p>The run itself logs its lifecycle through the synchronization progress port; this command
 * records the run metrics and logs only a rejected window, without echoing the raw input.
 */
@Endpoint(id = "cataloguesync")
public final class CatalogueSynchronizationEndpoint {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(CatalogueSynchronizationEndpoint.class);
    private static final String INVALID_WINDOW = "INVALID_SYNCHRONIZATION_WINDOW";
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
            LOGGER.atInfo()
                    .addKeyValue("sync.code", INVALID_WINDOW)
                    .log("Catalogue synchronization rejected: code={}", INVALID_WINDOW);
            return new WebEndpointResponse<>(
                    Map.of(
                            "code",
                            INVALID_WINDOW,
                            "detail",
                            "from and to must be ISO dates and from must not be after to"),
                    400);
        }
        var report = synchronizeCatalogue.synchronize(request);
        metrics.recordRun(report);
        return report;
    }
}
