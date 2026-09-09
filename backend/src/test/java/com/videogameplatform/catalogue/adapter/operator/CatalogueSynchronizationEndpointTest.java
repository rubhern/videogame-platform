package com.videogameplatform.catalogue.adapter.operator;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.catalogue.adapter.observability.CatalogueSynchronizationMetrics;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationReport;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationRequest;
import com.videogameplatform.catalogue.application.synchronization.SynchronizationOutcome;
import com.videogameplatform.catalogue.application.synchronization.SynchronizeCatalogueUseCase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;

class CatalogueSynchronizationEndpointTest {
    @Test
    void oneCommandRequiresAnInclusiveDateWindowAndHasNoGameLimit() {
        var captured = new AtomicReference<CatalogueSynchronizationRequest>();
        var registry = new SimpleMeterRegistry();
        SynchronizeCatalogueUseCase useCase =
                new SynchronizeCatalogueUseCase() {
                    public Optional<CatalogueSynchronizationReport> lastRun() {
                        return Optional.empty();
                    }

                    public CatalogueSynchronizationReport synchronize(
                            CatalogueSynchronizationRequest request) {
                        captured.set(request);
                        return new CatalogueSynchronizationReport(
                                null,
                                request.from(),
                                request.to(),
                                Instant.EPOCH,
                                Instant.EPOCH,
                                SynchronizationOutcome.SKIPPED,
                                "SYNCHRONIZATION_DISABLED",
                                new CatalogueSynchronizationReport.Counters(
                                        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
                    }
                };
        var endpoint =
                new CatalogueSynchronizationEndpoint(
                        useCase, new CatalogueSynchronizationMetrics(registry));
        assertThat(endpoint.lastRun()).isEqualTo(Map.of("status", "never_run"));
        endpoint.synchronize("2026-01-01", "2026-12-31");
        assertThat(captured.get())
                .isEqualTo(
                        new CatalogueSynchronizationRequest(
                                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31")));
        endpoint.synchronize("2026-09-08", "2026-09-08");
        assertThat(captured.get())
                .isEqualTo(
                        new CatalogueSynchronizationRequest(
                                LocalDate.parse("2026-09-08"), LocalDate.parse("2026-09-08")));
        assertThat(registry.getMeters())
                .allSatisfy(meter -> assertThat(meter.getId().getTag("mode")).isNull());
    }

    @Test
    void returnsBadRequestForMissingMalformedOrReversedDates() {
        var useCase = org.mockito.Mockito.mock(SynchronizeCatalogueUseCase.class);
        var endpoint =
                new CatalogueSynchronizationEndpoint(
                        useCase, new CatalogueSynchronizationMetrics(new SimpleMeterRegistry()));
        for (var response :
                java.util.List.of(
                        endpoint.synchronize(null, "2026-01-01"),
                        endpoint.synchronize("wrong", "2026-01-01"),
                        endpoint.synchronize("2027-01-01", "2026-01-01"))) {
            assertThat(response).isInstanceOf(WebEndpointResponse.class);
            assertThat(((WebEndpointResponse<?>) response).getStatus()).isEqualTo(400);
        }
        org.mockito.Mockito.verifyNoInteractions(useCase);
    }
}
