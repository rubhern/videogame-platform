package com.videogameplatform.catalogue.application.synchronization.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderPlatform;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderRegion;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderRelease;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.PlannedRelease;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.PublishedRelease;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderReleaseSignal;
import com.videogameplatform.catalogue.domain.ReleaseDate;
import com.videogameplatform.catalogue.domain.ReleaseStage;
import com.videogameplatform.catalogue.domain.ReleaseStatus;
import com.videogameplatform.catalogue.domain.ReviewStatus;
import com.videogameplatform.catalogue.domain.VerificationLevel;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
                ReviewStatus.NOT_REQUIRED,
                ReleaseStage.UNKNOWN);
    }

    private static Optional<PlannedRelease> plan(
            String platformRef, ReleaseDate date, PublishedRelease old) {
        return ReleaseReconciliationPolicy.reconcile(
                new ProviderRelease(
                        "10",
                        new ProviderPlatform(platformRef, "Platform " + platformRef, "platform"),
                        Optional.of(new ProviderRegion(REGION_REF, "worldwide")),
                        date,
                        ProviderReleaseSignal.NONE,
                        ReleaseStage.UNKNOWN),
                old,
                NOW,
                NOW,
                "IGDB");
    }

    @Test
    void enrichesAnUnknownStageWithoutRevokingAcceptedDateEvidenceAndPreservesKnownStageOnMiss() {
        var old = previous(VerificationLevel.VERIFIED);
        var provider =
                new ProviderRelease(
                        "10",
                        new ProviderPlatform(PC_REF, "PC", "pc"),
                        Optional.of(new ProviderRegion(REGION_REF, "worldwide")),
                        DATE,
                        ProviderReleaseSignal.NONE,
                        ReleaseStage.FULL_RELEASE);
        var enriched =
                ReleaseReconciliationPolicy.reconcile(provider, old, NOW, NOW, "IGDB")
                        .orElseThrow();
        assertThat(enriched.stage()).isEqualTo(ReleaseStage.FULL_RELEASE);
        assertThat(enriched.verificationLevel()).isEqualTo(VerificationLevel.VERIFIED);
        assertThat(enriched.reviewStatus()).isEqualTo(old.reviewStatus());
        var known =
                new PublishedRelease(
                        old.releaseId(),
                        old.gameId(),
                        PC_REF,
                        REGION_REF,
                        DATE,
                        old.status(),
                        old.lastVerifiedAt(),
                        old.verificationLevel(),
                        old.reviewStatus(),
                        enriched.stage());
        var absent =
                new ProviderRelease(
                        "10",
                        provider.platform(),
                        provider.region(),
                        DATE,
                        ProviderReleaseSignal.NONE,
                        ReleaseStage.UNKNOWN);
        assertThat(
                        ReleaseReconciliationPolicy.reconcile(absent, known, NOW, NOW, "IGDB")
                                .orElseThrow()
                                .stage())
                .isEqualTo(enriched.stage());
        var conflicting =
                new ProviderRelease(
                        "10",
                        provider.platform(),
                        provider.region(),
                        DATE,
                        ProviderReleaseSignal.NONE,
                        ReleaseStage.BETA);
        assertThat(ReleaseReconciliationPolicy.reconcile(conflicting, known, NOW, NOW, "IGDB"))
                .isEmpty();
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

    @ParameterizedTest
    @CsvSource({
        "PROVIDER_ONLY,NOT_REQUIRED,NOT_REQUIRED",
        "VERIFIED,NOT_REQUIRED,NOT_REQUIRED",
        "PROVIDER_ONLY,REQUIRED,NOT_REQUIRED",
        "VERIFIED,REQUIRED,REQUIRED"
    })
    void aLegacyKnownDateOccurrenceIsNotAProviderEvidenceChange(
            VerificationLevel verification, ReviewStatus review, ReviewStatus expectedReview) {
        var old = previous(verification);
        var legacy =
                new PublishedRelease(
                        old.releaseId(),
                        old.gameId(),
                        PC_REF,
                        REGION_REF,
                        DATE,
                        ReleaseStatus.RELEASED,
                        old.lastVerifiedAt(),
                        verification,
                        review,
                        ReleaseStage.UNKNOWN);

        var release = plan(PC_REF, DATE, legacy).orElseThrow();

        assertThat(release.status()).isEqualTo(ReleaseStatus.ANNOUNCED);
        assertThat(release.reviewStatus()).isEqualTo(expectedReview);
        assertThat(release.verificationLevel()).isEqualTo(verification);
        assertThat(release.lastVerifiedAt()).isEqualTo(old.lastVerifiedAt());
    }

    @ParameterizedTest
    @CsvSource({"CANCELLED,CANCELLED", "DELAYED,DELAYED"})
    void aNegativeProviderSignalIsExplicitLifecycleEvidenceRatherThanAmbiguity(
            ProviderReleaseSignal signal, ReleaseStatus expected) {
        var old =
                new PublishedRelease(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        PC_REF,
                        REGION_REF,
                        DATE,
                        ReleaseStatus.RELEASED,
                        null,
                        VerificationLevel.PROVIDER_ONLY,
                        ReviewStatus.NOT_REQUIRED,
                        ReleaseStage.UNKNOWN);
        var release =
                ReleaseReconciliationPolicy.reconcile(
                                new ProviderRelease(
                                        "10",
                                        new ProviderPlatform(PC_REF, "PC", "pc"),
                                        Optional.of(new ProviderRegion(REGION_REF, "worldwide")),
                                        DATE,
                                        signal,
                                        com.videogameplatform.catalogue.domain.ReleaseStage
                                                .UNKNOWN),
                                old,
                                NOW,
                                NOW,
                                "IGDB")
                        .orElseThrow();

        assertThat(release.reviewStatus()).isEqualTo(ReviewStatus.NOT_REQUIRED);
        assertThat(release.status()).isEqualTo(expected);
    }

    @Test
    void explicitVerifiedUnknownDateOccurrenceCannotBeNormalizedAway() {
        var unknown = new ReleaseDate.Unknown();
        var old =
                new PublishedRelease(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        PC_REF,
                        REGION_REF,
                        unknown,
                        ReleaseStatus.RELEASED,
                        NOW,
                        VerificationLevel.VERIFIED,
                        ReviewStatus.NOT_REQUIRED,
                        ReleaseStage.UNKNOWN);

        assertThat(plan(PC_REF, unknown, old)).isEmpty();
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
                                ProviderReleaseSignal.NONE,
                                ReleaseStage.UNKNOWN),
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
    void aCoherentDateCorrectionClearsReviewButANewUnknownDateRequiresReview() {
        assertThat(
                        plan(
                                        PC_REF,
                                        new ReleaseDate.Day(LocalDate.parse("2028-10-01")),
                                        previous(VerificationLevel.PROVIDER_ONLY))
                                .orElseThrow()
                                .reviewStatus())
                .isEqualTo(ReviewStatus.NOT_REQUIRED);
        assertThat(plan(PC_REF, new ReleaseDate.Unknown(), null).orElseThrow().reviewStatus())
                .isEqualTo(ReviewStatus.REQUIRED);
    }

    @ParameterizedTest
    @CsvSource({"NOT_REQUIRED", "REQUIRED"})
    void unchangedDayPrecisionFullReleaseRecomputesProviderReviewAndIsIdempotent(
            ReviewStatus previousReview) {
        var provider = fullRelease(DATE, ProviderReleaseSignal.NONE);
        var old = published(provider, previousReview);
        var corrected = reconcile(provider, old);
        assertThat(corrected.reviewStatus()).isEqualTo(ReviewStatus.NOT_REQUIRED);
        assertThat(reconcile(provider, published(provider, corrected.reviewStatus())))
                .isEqualTo(corrected);
    }

    @Test
    void unknownDateRefinementClearsRequiredImmediately() {
        var unknown = fullRelease(new ReleaseDate.Unknown(), ProviderReleaseSignal.NONE);
        var old = published(unknown, ReviewStatus.REQUIRED);
        var refined = fullRelease(DATE, ProviderReleaseSignal.NONE);
        assertThat(reconcile(refined, old).reviewStatus()).isEqualTo(ReviewStatus.NOT_REQUIRED);
    }

    @ParameterizedTest
    @CsvSource({"NONE", "CANCELLED", "DELAYED"})
    void lifecycleSignalsDoNotDuplicateReviewAndUnknownDatesStillRequireIt(
            ProviderReleaseSignal signal) {
        var known = fullRelease(DATE, signal);
        assertThat(reconcile(known, null).reviewStatus()).isEqualTo(ReviewStatus.NOT_REQUIRED);
        assertThat(reconcile(known, published(known, ReviewStatus.REQUIRED)).reviewStatus())
                .isEqualTo(ReviewStatus.NOT_REQUIRED);
        var unknown = fullRelease(new ReleaseDate.Unknown(), signal);
        assertThat(reconcile(unknown, null).reviewStatus()).isEqualTo(ReviewStatus.REQUIRED);
        assertThat(reconcile(unknown, published(unknown, ReviewStatus.REQUIRED)).reviewStatus())
                .isEqualTo(ReviewStatus.REQUIRED);
    }

    @Test
    void knownStageConflictStillRequiresReview() {
        var full = fullRelease(DATE, ProviderReleaseSignal.NONE);
        var beta =
                new ProviderRelease(
                        full.providerId(),
                        full.platform(),
                        full.region(),
                        full.date(),
                        full.signal(),
                        ReleaseStage.BETA);
        assertThat(reconcile(full, published(beta, ReviewStatus.NOT_REQUIRED)).reviewStatus())
                .isEqualTo(ReviewStatus.REQUIRED);
        assertThat(reconcile(full, published(beta, ReviewStatus.REQUIRED)).reviewStatus())
                .isEqualTo(ReviewStatus.REQUIRED);
    }

    @Test
    void changedRegionEvidenceStillRequiresReview() {
        var full = fullRelease(DATE, ProviderReleaseSignal.NONE);
        var regional =
                new ProviderRelease(
                        full.providerId(),
                        full.platform(),
                        Optional.of(new ProviderRegion("1", "Europe")),
                        full.date(),
                        full.signal(),
                        full.stage());
        assertThat(reconcile(regional, published(full, ReviewStatus.NOT_REQUIRED)).reviewStatus())
                .isEqualTo(ReviewStatus.REQUIRED);
    }

    private static ProviderRelease fullRelease(ReleaseDate date, ProviderReleaseSignal signal) {
        return new ProviderRelease(
                "10",
                new ProviderPlatform(PC_REF, "PC", "pc"),
                Optional.of(new ProviderRegion(REGION_REF, "worldwide")),
                date,
                signal,
                ReleaseStage.FULL_RELEASE);
    }

    private static PublishedRelease published(ProviderRelease provider, ReviewStatus review) {
        return new PublishedRelease(
                UUID.randomUUID(),
                UUID.randomUUID(),
                PC_REF,
                REGION_REF,
                provider.date(),
                ReleaseStatusPolicy.persistedStatus(provider.signal()),
                null,
                VerificationLevel.PROVIDER_ONLY,
                review,
                provider.stage());
    }

    private static PlannedRelease reconcile(ProviderRelease provider, PublishedRelease old) {
        return ReleaseReconciliationPolicy.reconcile(provider, old, NOW, NOW, "IGDB").orElseThrow();
    }
}
