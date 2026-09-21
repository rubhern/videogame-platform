package com.videogameplatform.catalogue.application.synchronization.internal;

import com.videogameplatform.catalogue.application.synchronization.port.ProviderReleaseSignal;
import com.videogameplatform.catalogue.domain.ReleaseStatus;

/**
 * Projects the trusted provider signal onto the persisted release status.
 *
 * <p>The provider is trusted only for cancellation and delay. Whether a known release date has
 * already happened is a time-dependent product decision and is derived per request from the release
 * date and the trusted evaluation date (see {@link
 * com.videogameplatform.catalogue.domain.EffectiveReleaseStatusPolicy}), never baked into the
 * persisted status at synchronization time. Synchronization therefore refreshes evidence and never
 * acts as the application clock (REL-011).
 */
public final class ReleaseStatusPolicy {

    private ReleaseStatusPolicy() {}

    public static ReleaseStatus persistedStatus(ProviderReleaseSignal signal) {
        return switch (signal) {
            case CANCELLED -> ReleaseStatus.CANCELLED;
            case DELAYED -> ReleaseStatus.DELAYED;
            case NONE -> ReleaseStatus.ANNOUNCED;
        };
    }
}
