package com.videogameplatform.catalogue.adapter.operator;

import com.videogameplatform.catalogue.application.synchronization.SynchronizationCoverage;
import com.videogameplatform.catalogue.application.synchronization.SynchronizationCoverageQuery;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;

/**
 * Private management read of durable synchronization coverage; absent from the product contract.
 *
 * <p>It reads PostgreSQL only and never calls the provider.
 */
@Endpoint(id = "cataloguecoverage")
public final class CatalogueCoverageEndpoint {
    private static final String INVALID_RANGE = "INVALID_COVERAGE_RANGE";
    private final SynchronizationCoverageQuery coverage;

    public CatalogueCoverageEndpoint(SynchronizationCoverageQuery coverage) {
        this.coverage = coverage;
    }

    @ReadOperation
    public Object coverage(@Nullable String from, @Nullable String to) {
        final SynchronizationCoverage answer;
        try {
            answer =
                    coverage.coverage(
                            LocalDate.parse(from == null ? "" : from),
                            LocalDate.parse(to == null ? "" : to));
        } catch (DateTimeParseException | IllegalArgumentException failure) {
            return new WebEndpointResponse<>(
                    Map.of(
                            "code",
                            INVALID_RANGE,
                            "detail",
                            "from and to must be ISO dates and from must not be after to"),
                    400);
        }
        return new CoverageView(
                answer.from(),
                answer.to(),
                answer.continuesFrom().orElse(null),
                answer.periods().stream().map(PeriodView::of).toList());
    }

    /** {@code continuesFrom} is present only when the answer stopped before {@code to}. */
    public record CoverageView(
            LocalDate from,
            LocalDate to,
            @Nullable LocalDate continuesFrom,
            List<PeriodView> periods) {}

    /**
     * {@code status} is {@code covered} or {@code never_covered}; only covered has a scan, and its
     * {@code failedGames} counts the covering run's Games that kept their last valid state.
     */
    public record PeriodView(
            LocalDate from,
            LocalDate to,
            String status,
            @Nullable UUID runId,
            @Nullable Instant scanStartedAt,
            @Nullable Instant scanCompletedAt,
            @Nullable Long failedGames) {
        static PeriodView of(SynchronizationCoverage.Period period) {
            return period.lastScan()
                    .map(
                            scan ->
                                    new PeriodView(
                                            period.from(),
                                            period.to(),
                                            "covered",
                                            scan.runId(),
                                            scan.scanStartedAt(),
                                            scan.scanCompletedAt(),
                                            scan.failedGames()))
                    .orElseGet(
                            () ->
                                    new PeriodView(
                                            period.from(),
                                            period.to(),
                                            "never_covered",
                                            null,
                                            null,
                                            null,
                                            null));
        }
    }
}
