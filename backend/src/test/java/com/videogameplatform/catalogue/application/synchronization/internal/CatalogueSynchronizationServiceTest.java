package com.videogameplatform.catalogue.application.synchronization.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationRequest;
import com.videogameplatform.catalogue.application.synchronization.SynchronizationOutcome;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class CatalogueSynchronizationServiceTest {
    @Test
    void disabledProviderDoesNotTouchPersistence() {
        var store = mock(CatalogueSynchronizationStore.class);
        var provider = mock(CatalogueProviderPort.class);
        var service =
                new CatalogueSynchronizationService(
                        store,
                        provider,
                        Clock.systemUTC(),
                        new SynchronizationPolicy(500, 25, 50, Duration.ofMinutes(30)),
                        new CoverSelectionPolicy("/fallback.svg", "Product"));
        var request =
                new CatalogueSynchronizationRequest(
                        LocalDate.parse("2026-01-01"), LocalDate.parse("2026-12-31"));
        assertThat(service.synchronize(request).outcome())
                .isEqualTo(SynchronizationOutcome.SKIPPED);
        verifyNoInteractions(store);
    }
}
