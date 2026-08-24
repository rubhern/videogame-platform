package com.videogameplatform.catalogue.application.internal;

import com.videogameplatform.catalogue.application.BrowseReleasesResult;
import com.videogameplatform.catalogue.application.BrowseReleasesUseCase;
import com.videogameplatform.catalogue.application.CatalogueNotReadyException;
import com.videogameplatform.catalogue.application.ReleaseQueryValidationException;
import com.videogameplatform.catalogue.application.port.ReleaseBrowseReadPort;
import com.videogameplatform.catalogue.application.port.ReleaseBrowseReadPort.Item;
import com.videogameplatform.catalogue.application.port.ReleaseBrowseReadPort.ProductCoverReference;
import com.videogameplatform.catalogue.application.port.ReleaseBrowseReadPort.ProviderCoverReference;
import com.videogameplatform.catalogue.application.port.ReleaseBrowseReadPort.Result;
import com.videogameplatform.catalogue.application.port.ReleaseBrowseReadPort.UnavailableCoverReference;
import com.videogameplatform.catalogue.domain.FreshnessStatus;
import com.videogameplatform.catalogue.domain.ReleaseDate;
import com.videogameplatform.catalogue.domain.ReleaseStatus;
import com.videogameplatform.catalogue.domain.ReviewStatus;
import com.videogameplatform.catalogue.domain.SourceKind;
import com.videogameplatform.catalogue.domain.VerificationLevel;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Framework-independent implementation of UC-001. */
public final class ReleaseCatalogueService implements BrowseReleasesUseCase {

    private static final String FALLBACK_COVER_PATH = "/assets/covers/fallback.svg";
    private static final String FALLBACK_ALTERNATIVE_TEXT = "Carátula oficial no disponible";

    private final ReleaseBrowseReadPort readPort;
    private final Clock clock;
    private final ReleaseBrowsePolicy browsePolicy;
    private final CatalogueFreshnessPolicy freshnessPolicy;

    public ReleaseCatalogueService(
            ReleaseBrowseReadPort readPort,
            Clock clock,
            ReleaseBrowsePolicy browsePolicy,
            CatalogueFreshnessPolicy freshnessPolicy) {
        this.readPort = readPort;
        this.clock = clock;
        this.browsePolicy = browsePolicy;
        this.freshnessPolicy = freshnessPolicy;
    }

    @Override
    public BrowseReleasesResult browse(Query query) {
        Instant evaluatedAt = clock.instant();
        LocalDate evaluatedOn = LocalDate.ofInstant(evaluatedAt, clock.getZone());
        BrowseReleasesResult.Window window = window(query.view(), evaluatedOn);
        long offset = Math.multiplyExact((long) query.pageNumber() - 1, query.pageSize());

        Result result =
                readPort.findPublishedReleases(
                                new ReleaseBrowseReadPort.Criteria(
                                        query.view(),
                                        new ReleaseBrowseReadPort.Window(
                                                window.from(), window.to()),
                                        query.platformId(),
                                        query.regionId(),
                                        new ReleaseBrowseReadPort.Pagination(
                                                query.pageNumber(), query.pageSize(), offset),
                                        browsePolicy.includesUnknownUpcomingDates()))
                        .orElseThrow(CatalogueNotReadyException::new);

        validateTaxonomy(query, result);
        long totalPages =
                result.totalItems() / query.pageSize()
                        + (result.totalItems() % query.pageSize() == 0 ? 0 : 1);

        return new BrowseReleasesResult(
                result.publicationVersion(),
                query.view(),
                evaluatedOn,
                window,
                new BrowseReleasesResult.ActiveFilters(query.platformId(), query.regionId()),
                availableFilters(result),
                result.items().stream().map(item -> toItem(item, evaluatedAt)).toList(),
                new BrowseReleasesResult.PageMetadata(
                        query.pageNumber(), query.pageSize(), result.totalItems(), totalPages));
    }

    private BrowseReleasesResult.Window window(View view, LocalDate evaluatedOn) {
        return switch (view) {
            case RECENT ->
                    new BrowseReleasesResult.Window(
                            evaluatedOn.minusMonths(browsePolicy.recentWindowMonths()),
                            evaluatedOn);
            case UPCOMING ->
                    new BrowseReleasesResult.Window(
                            evaluatedOn,
                            evaluatedOn.plusMonths(browsePolicy.upcomingWindowMonths()));
        };
    }

    private static void validateTaxonomy(Query query, Result result) {
        if (query.platformId() != null
                && result.platforms().stream()
                        .noneMatch(platform -> platform.id().equals(query.platformId()))) {
            throw new ReleaseQueryValidationException(
                    ReleaseQueryValidationException.Code.PLATFORM_NOT_SUPPORTED);
        }
        if (query.regionId() != null
                && result.regions().stream()
                        .noneMatch(region -> region.id().equals(query.regionId()))) {
            throw new ReleaseQueryValidationException(
                    ReleaseQueryValidationException.Code.REGION_NOT_SUPPORTED);
        }
    }

    private static BrowseReleasesResult.AvailableFilters availableFilters(Result result) {
        Comparator<ReleaseBrowseReadPort.Taxonomy> order =
                Comparator.comparing(
                                (ReleaseBrowseReadPort.Taxonomy value) ->
                                        value.name().toLowerCase(Locale.ROOT))
                        .thenComparing(ReleaseBrowseReadPort.Taxonomy::id);
        List<BrowseReleasesResult.Taxonomy> platforms =
                result.platforms().stream()
                        .sorted(order)
                        .map(value -> new BrowseReleasesResult.Taxonomy(value.id(), value.name()))
                        .toList();
        List<BrowseReleasesResult.Taxonomy> regions =
                result.regions().stream()
                        .sorted(order)
                        .map(value -> new BrowseReleasesResult.Taxonomy(value.id(), value.name()))
                        .toList();
        return new BrowseReleasesResult.AvailableFilters(platforms, regions);
    }

    private BrowseReleasesResult.Item toItem(Item item, Instant evaluatedAt) {
        BrowseReleasesResult.Taxonomy platform =
                new BrowseReleasesResult.Taxonomy(item.platform().id(), item.platform().name());
        BrowseReleasesResult.Taxonomy region =
                new BrowseReleasesResult.Taxonomy(item.region().id(), item.region().name());
        BrowseReleasesResult.Release release =
                new BrowseReleasesResult.Release(
                        item.releaseId(),
                        item.gameId(),
                        platform,
                        region,
                        toReleaseDate(item.releaseDate()),
                        toStatus(item.status()),
                        new BrowseReleasesResult.Provenance(
                                toSource(item.sourceKind()),
                                item.sourceName(),
                                item.sourceEntityType()),
                        item.providerUpdatedAt(),
                        item.lastSyncedAt(),
                        item.lastVerifiedAt(),
                        toVerification(item.verificationLevel()),
                        toReview(item.reviewStatus()),
                        toFreshness(freshnessPolicy.status(item.lastSyncedAt(), evaluatedAt)));
        return new BrowseReleasesResult.Item(
                item.gameId(), item.slug(), item.canonicalTitle(), cover(item), release);
    }

    private static BrowseReleasesResult.Cover cover(Item item) {
        return switch (item.cover()) {
            case ProviderCoverReference provider ->
                    new BrowseReleasesResult.ProviderCoverReference(
                            provider.provider(),
                            provider.reference(),
                            provider.alternativeText(),
                            provider.sourceUrl());
            case ProductCoverReference product ->
                    new BrowseReleasesResult.FallbackCover(
                            product.assetPath(), product.alternativeText());
            case UnavailableCoverReference unavailable ->
                    new BrowseReleasesResult.FallbackCover(
                            FALLBACK_COVER_PATH, FALLBACK_ALTERNATIVE_TEXT);
        };
    }

    private static BrowseReleasesResult.DateValue toReleaseDate(ReleaseDate source) {
        return switch (source) {
            case ReleaseDate.Day day ->
                    new BrowseReleasesResult.DateValue(
                            BrowseReleasesResult.DatePrecision.DAY, day.value());
            case ReleaseDate.Month month ->
                    new BrowseReleasesResult.DateValue(
                            BrowseReleasesResult.DatePrecision.MONTH, month.value());
            case ReleaseDate.Quarter quarter ->
                    new BrowseReleasesResult.DateValue(
                            BrowseReleasesResult.DatePrecision.QUARTER, quarter.value());
            case ReleaseDate.YearOnly year ->
                    new BrowseReleasesResult.DateValue(
                            BrowseReleasesResult.DatePrecision.YEAR, year.value());
            case ReleaseDate.Unknown unknown ->
                    new BrowseReleasesResult.DateValue(
                            BrowseReleasesResult.DatePrecision.UNKNOWN, null);
        };
    }

    private static BrowseReleasesResult.Status toStatus(ReleaseStatus source) {
        return switch (source) {
            case ANNOUNCED -> BrowseReleasesResult.Status.ANNOUNCED;
            case SCHEDULED -> BrowseReleasesResult.Status.SCHEDULED;
            case RELEASED -> BrowseReleasesResult.Status.RELEASED;
            case DELAYED -> BrowseReleasesResult.Status.DELAYED;
            case CANCELLED -> BrowseReleasesResult.Status.CANCELLED;
            case UNKNOWN -> BrowseReleasesResult.Status.UNKNOWN;
        };
    }

    private static BrowseReleasesResult.Source toSource(SourceKind source) {
        return switch (source) {
            case EXTERNAL_PROVIDER -> BrowseReleasesResult.Source.EXTERNAL_PROVIDER;
            case PRODUCT_CURATED -> BrowseReleasesResult.Source.PRODUCT_CURATED;
            case OFFICIAL_SOURCE -> BrowseReleasesResult.Source.OFFICIAL_SOURCE;
        };
    }

    private static BrowseReleasesResult.Verification toVerification(VerificationLevel source) {
        return switch (source) {
            case PROVIDER_ONLY -> BrowseReleasesResult.Verification.PROVIDER_ONLY;
            case VERIFIED -> BrowseReleasesResult.Verification.VERIFIED;
        };
    }

    private static BrowseReleasesResult.Review toReview(ReviewStatus source) {
        return switch (source) {
            case NOT_REQUIRED -> BrowseReleasesResult.Review.NOT_REQUIRED;
            case REQUIRED -> BrowseReleasesResult.Review.REQUIRED;
        };
    }

    private static BrowseReleasesResult.Freshness toFreshness(FreshnessStatus source) {
        return switch (source) {
            case FRESH -> BrowseReleasesResult.Freshness.FRESH;
            case STALE -> BrowseReleasesResult.Freshness.STALE;
        };
    }
}
