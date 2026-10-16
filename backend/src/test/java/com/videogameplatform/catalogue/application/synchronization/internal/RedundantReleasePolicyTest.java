package com.videogameplatform.catalogue.application.synchronization.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderPlatform;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderRegion;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderRelease;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.PublishedRelease;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderReleaseSignal;
import com.videogameplatform.catalogue.domain.ReleaseDate;
import com.videogameplatform.catalogue.domain.ReleaseStage;
import com.videogameplatform.catalogue.domain.ReleaseStatus;
import com.videogameplatform.catalogue.domain.ReviewStatus;
import com.videogameplatform.catalogue.domain.VerificationLevel;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RedundantReleasePolicyTest {
    private static final ReleaseDate DAY = new ReleaseDate.Day(LocalDate.parse("2026-10-01"));

    @Test
    void keepsTheOnlyStagedReferenceOfATupleInProviderOrder() {
        var stageless = release("2", Optional.of("8"), ProviderReleaseSignal.NONE, UNKNOWN);
        var other = release("3", Optional.of("1"), ProviderReleaseSignal.NONE, UNKNOWN);
        var staged = release("1", Optional.of("8"), ProviderReleaseSignal.NONE, FULL);

        var normalized =
                RedundantReleasePolicy.normalize(List.of(stageless, other, staged), Map.of());

        assertThat(normalized.releases()).containsExactly(other, staged);
        assertThat(normalized.references()).containsExactlyInAnyOrder("3", "1");
        assertThat(normalized.redundant()).isEqualTo(1);
    }

    @Test
    void anotherRegionOrLifecycleSignalIsAnotherRelease() {
        var releases =
                List.of(
                        release("1", Optional.of("8"), ProviderReleaseSignal.NONE, FULL),
                        release("2", Optional.empty(), ProviderReleaseSignal.NONE, UNKNOWN),
                        release("3", Optional.of("8"), ProviderReleaseSignal.CANCELLED, UNKNOWN),
                        release("4", Optional.of("8"), ProviderReleaseSignal.DELAYED, UNKNOWN));

        var normalized = RedundantReleasePolicy.normalize(releases, Map.of());

        assertThat(normalized.releases()).isEqualTo(releases);
        assertThat(normalized.redundant()).isZero();
    }

    @Test
    void anEstablishedStageCountsEvenWhenTheProviderNoLongerStatesIt() {
        // Reconciliation would keep the published stage, so two staged references share the tuple.
        var restated = release("2", Optional.of("8"), ProviderReleaseSignal.NONE, UNKNOWN);
        var staged = release("1", Optional.of("8"), ProviderReleaseSignal.NONE, FULL);

        assertThatThrownBy(
                        () ->
                                RedundantReleasePolicy.normalize(
                                        List.of(staged, restated),
                                        Map.of(
                                                "2",
                                                published(FULL, VerificationLevel.PROVIDER_ONLY))))
                .isInstanceOf(RedundantReleasePolicy.ConflictingReleaseEvidence.class);
    }

    @Test
    void aVerifiedStagedReferenceStandsForItsStagelessRestatement() {
        var staged = release("1", Optional.of("8"), ProviderReleaseSignal.NONE, UNKNOWN);
        var stageless = release("2", Optional.of("8"), ProviderReleaseSignal.NONE, UNKNOWN);

        var normalized =
                RedundantReleasePolicy.normalize(
                        List.of(staged, stageless),
                        Map.of("1", published(FULL, VerificationLevel.VERIFIED)));

        assertThat(normalized.releases()).containsExactly(staged);
        assertThat(normalized.redundant()).isEqualTo(1);
    }

    private static final ReleaseStage FULL = ReleaseStage.FULL_RELEASE;
    private static final ReleaseStage UNKNOWN = ReleaseStage.UNKNOWN;

    private static ProviderRelease release(
            String id, Optional<String> region, ProviderReleaseSignal signal, ReleaseStage stage) {
        return new ProviderRelease(
                id,
                new ProviderPlatform("508", "Nintendo Switch 2", "switch-2"),
                region.map(reference -> new ProviderRegion(reference, "Region " + reference)),
                DAY,
                signal,
                stage);
    }

    private static PublishedRelease published(ReleaseStage stage, VerificationLevel verification) {
        return new PublishedRelease(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "508",
                "8",
                DAY,
                ReleaseStatus.ANNOUNCED,
                null,
                verification,
                ReviewStatus.NOT_REQUIRED,
                stage);
    }
}
