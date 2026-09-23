package com.videogameplatform.catalogue.application.synchronization.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.catalogue.application.synchronization.port.ProviderReleaseSignal;
import com.videogameplatform.catalogue.domain.ReleaseStatus;
import org.junit.jupiter.api.Test;

/**
 * Synchronization persists only provider evidence. Whether a known date has already happened is a
 * time-dependent product decision derived per request, never baked in at synchronization time.
 */
class ReleaseStatusPolicyTest {

    @Test
    void projectsTheProviderSignalWithoutConsultingTheClock() {
        assertThat(ReleaseStatusPolicy.persistedStatus(ProviderReleaseSignal.CANCELLED))
                .isEqualTo(ReleaseStatus.CANCELLED);
        assertThat(ReleaseStatusPolicy.persistedStatus(ProviderReleaseSignal.DELAYED))
                .isEqualTo(ReleaseStatus.DELAYED);
        assertThat(ReleaseStatusPolicy.persistedStatus(ProviderReleaseSignal.NONE))
                .isEqualTo(ReleaseStatus.ANNOUNCED);
    }
}
