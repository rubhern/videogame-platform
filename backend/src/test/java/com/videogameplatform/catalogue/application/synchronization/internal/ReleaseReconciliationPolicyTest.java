package com.videogameplatform.catalogue.application.synchronization.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderRelease;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.PlannedRelease;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.PublishedRelease;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.ReleaseIdentity;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderReleaseSignal;
import com.videogameplatform.catalogue.domain.ReleaseDate;
import com.videogameplatform.catalogue.domain.ReleaseStatus;
import com.videogameplatform.catalogue.domain.ReviewStatus;
import com.videogameplatform.catalogue.domain.VerificationLevel;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReleaseReconciliationPolicyTest {
    private static final UUID GAME = UUID.randomUUID(),
            PC = UUID.randomUUID(),
            PS = UUID.randomUUID(),
            REGION = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-08T08:00:00Z");
    private static final ReleaseDate DATE = new ReleaseDate.Day(LocalDate.parse("2026-10-01"));

    private static PublishedRelease previous(VerificationLevel verification) {
        return new PublishedRelease(
                UUID.randomUUID(),
                new ReleaseIdentity(GAME, PC, REGION),
                DATE,
                ReleaseStatus.SCHEDULED,
                verification == VerificationLevel.VERIFIED ? NOW : null,
                verification,
                ReviewStatus.NOT_REQUIRED);
    }

    private static Optional<PlannedRelease> plan(
            String platform, ReleaseDate date, PublishedRelease old) {
        return ReleaseReconciliationPolicy.reconcile(
                GAME,
                new ProviderRelease("10", platform, "worldwide", date, ProviderReleaseSignal.NONE),
                old,
                Map.of("pc", PC, "ps", PS),
                Map.of("worldwide", REGION),
                LocalDate.parse("2026-09-08"),
                NOW,
                NOW,
                "IGDB");
    }

    @Test
    void createsProviderEvidenceWithoutHumanApproval() {
        assertThat(plan("pc", DATE, null).orElseThrow().reviewStatus())
                .isEqualTo(ReviewStatus.NOT_REQUIRED);
    }

    @Test
    void keepsVerificationForAnUnchangedRelease() {
        var release = plan("pc", DATE, previous(VerificationLevel.VERIFIED)).orElseThrow();
        assertThat(release.verificationLevel()).isEqualTo(VerificationLevel.VERIFIED);
        assertThat(release.lastVerifiedAt()).isEqualTo(NOW);
    }

    @Test
    void changedDateDoesNotOverwriteVerifiedEvidence() {
        assertThat(
                        plan(
                                "pc",
                                new ReleaseDate.Day(LocalDate.parse("2028-10-01")),
                                previous(VerificationLevel.VERIFIED)))
                .isEmpty();
    }

    @Test
    void changedPlatformDoesNotBypassVerifiedEvidence() {
        assertThat(plan("ps", DATE, previous(VerificationLevel.VERIFIED))).isEmpty();
    }

    @Test
    void changedPlatformReconcilesTheMatchedProviderOnlyRelease() {
        var release = plan("ps", DATE, previous(VerificationLevel.PROVIDER_ONLY)).orElseThrow();
        assertThat(release.identity().platformId()).isEqualTo(PS);
        assertThat(release.reviewStatus()).isEqualTo(ReviewStatus.REQUIRED);
    }

    @Test
    void changedDateAndUnknownDateRequireReviewWithoutBlockingAutomaticPublication() {
        assertThat(
                        plan(
                                        "pc",
                                        new ReleaseDate.Day(LocalDate.parse("2028-10-01")),
                                        previous(VerificationLevel.PROVIDER_ONLY))
                                .orElseThrow()
                                .reviewStatus())
                .isEqualTo(ReviewStatus.REQUIRED);
        assertThat(plan("pc", new ReleaseDate.Unknown(), null).orElseThrow().reviewStatus())
                .isEqualTo(ReviewStatus.REQUIRED);
    }

    @Test
    void rejectsUnsupportedMappingInsteadOfMergingReleases() {
        assertThatThrownBy(() -> plan("unmapped", DATE, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
