package com.videogameplatform.catalogue.application.synchronization.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderPlatform;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderRegion;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderRelease;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.PlannedRelease;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.PublishedRelease;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderReleaseSignal;
import com.videogameplatform.catalogue.domain.ReleaseDate;
import com.videogameplatform.catalogue.domain.ReleaseStatus;
import com.videogameplatform.catalogue.domain.ReviewStatus;
import com.videogameplatform.catalogue.domain.VerificationLevel;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReleaseReconciliationPolicyTest {
    // Reconciliation compares stable provider taxonomy references, not product UUIDs, so a mutable
    // slug or name change never counts as a taxonomy change.
    private static final String PC_REF = "6", PS_REF = "167", REGION_REF = "8";
    private static final Instant NOW = Instant.parse("2026-09-08T08:00:00Z");
    private static final ReleaseDate DATE = new ReleaseDate.Day(LocalDate.parse("2026-10-01"));

    private static PublishedRelease previous(VerificationLevel verification) {
        return new PublishedRelease(
                UUID.randomUUID(),
                UUID.randomUUID(),
                PC_REF,
                REGION_REF,
                DATE,
                ReleaseStatus.ANNOUNCED,
                verification == VerificationLevel.VERIFIED ? NOW : null,
                verification,
                ReviewStatus.NOT_REQUIRED);
    }

    private static Optional<PlannedRelease> plan(
            String platformRef, ReleaseDate date, PublishedRelease old) {
        return ReleaseReconciliationPolicy.reconcile(
                new ProviderRelease(
                        "10",
                        new ProviderPlatform(platformRef, "Platform " + platformRef, "platform"),
                        Optional.of(new ProviderRegion(REGION_REF, "worldwide")),
                        date,
                        ProviderReleaseSignal.NONE),
                old,
                NOW,
                NOW,
                "IGDB");
    }

    @Test
    void createsProviderEvidenceWithoutHumanApproval() {
        assertThat(plan(PC_REF, DATE, null).orElseThrow().reviewStatus())
                .isEqualTo(ReviewStatus.NOT_REQUIRED);
    }

    @Test
    void keepsVerificationForAnUnchangedRelease() {
        var release = plan(PC_REF, DATE, previous(VerificationLevel.VERIFIED)).orElseThrow();
        assertThat(release.verificationLevel()).isEqualTo(VerificationLevel.VERIFIED);
        assertThat(release.lastVerifiedAt()).isEqualTo(NOW);
    }

    @Test
    void aChangedProviderSlugOrNameDoesNotCountAsATaxonomyChange() {
        // Same provider platform reference with a renamed slug/name stays unchanged and keeps
        // verified evidence: identity is the reference, not the mutable descriptor.
        var renamed =
                ReleaseReconciliationPolicy.reconcile(
                        new ProviderRelease(
                                "10",
                                new ProviderPlatform(PC_REF, "PC (renamed)", "pc-renamed"),
                                Optional.of(new ProviderRegion(REGION_REF, "Worldwide (renamed)")),
                                DATE,
                                ProviderReleaseSignal.NONE),
                        previous(VerificationLevel.VERIFIED),
                        NOW,
                        NOW,
                        "IGDB");
        assertThat(renamed.orElseThrow().verificationLevel()).isEqualTo(VerificationLevel.VERIFIED);
    }

    @Test
    void changedDateDoesNotOverwriteVerifiedEvidence() {
        assertThat(
                        plan(
                                PC_REF,
                                new ReleaseDate.Day(LocalDate.parse("2028-10-01")),
                                previous(VerificationLevel.VERIFIED)))
                .isEmpty();
    }

    @Test
    void changedPlatformReferenceDoesNotBypassVerifiedEvidence() {
        assertThat(plan(PS_REF, DATE, previous(VerificationLevel.VERIFIED))).isEmpty();
    }

    @Test
    void changedPlatformReferenceReconcilesTheMatchedProviderOnlyRelease() {
        var release = plan(PS_REF, DATE, previous(VerificationLevel.PROVIDER_ONLY)).orElseThrow();
        assertThat(release.platform().providerId()).isEqualTo(PS_REF);
        assertThat(release.reviewStatus()).isEqualTo(ReviewStatus.REQUIRED);
    }

    @Test
    void changedDateAndUnknownDateRequireReviewWithoutBlockingAutomaticPublication() {
        assertThat(
                        plan(
                                        PC_REF,
                                        new ReleaseDate.Day(LocalDate.parse("2028-10-01")),
                                        previous(VerificationLevel.PROVIDER_ONLY))
                                .orElseThrow()
                                .reviewStatus())
                .isEqualTo(ReviewStatus.REQUIRED);
        assertThat(plan(PC_REF, new ReleaseDate.Unknown(), null).orElseThrow().reviewStatus())
                .isEqualTo(ReviewStatus.REQUIRED);
    }
}
