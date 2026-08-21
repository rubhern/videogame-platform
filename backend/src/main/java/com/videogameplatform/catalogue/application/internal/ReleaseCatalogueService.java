package com.videogameplatform.catalogue.application.internal;

import com.videogameplatform.catalogue.application.BrowseReleasesUseCase;
import com.videogameplatform.catalogue.application.CatalogueNotReadyException;
import com.videogameplatform.catalogue.application.ReleasePage;
import com.videogameplatform.catalogue.application.ReleaseQueryValidationException;
import com.videogameplatform.catalogue.application.port.ReleaseBrowseReadPort;
import com.videogameplatform.catalogue.application.port.ReleaseBrowseReadPort.Item;
import com.videogameplatform.catalogue.application.port.ReleaseBrowseReadPort.ProviderCoverReference;
import com.videogameplatform.catalogue.application.port.ReleaseBrowseReadPort.Result;
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
    public ReleasePage browse(Query query) {
        Instant evaluatedAt = clock.instant();
        LocalDate evaluatedOn = LocalDate.ofInstant(evaluatedAt, clock.getZone());
        ReleasePage.Window window = window(query.view(), evaluatedOn);
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

        return new ReleasePage(
                result.publicationVersion(),
                query.view(),
                evaluatedOn,
                window,
                new ReleasePage.ActiveFilters(query.platformId(), query.regionId()),
                availableFilters(result),
                result.items().stream().map(item -> toItem(item, evaluatedAt)).toList(),
                new ReleasePage.PageMetadata(
                        query.pageNumber(), query.pageSize(), result.totalItems(), totalPages));
    }

    private ReleasePage.Window window(View view, LocalDate evaluatedOn) {
        return view == View.RECENT
                ? new ReleasePage.Window(
                        evaluatedOn.minusMonths(browsePolicy.recentWindowMonths()), evaluatedOn)
                : new ReleasePage.Window(
                        evaluatedOn, evaluatedOn.plusMonths(browsePolicy.upcomingWindowMonths()));
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

    private static ReleasePage.AvailableFilters availableFilters(Result result) {
        Comparator<ReleaseBrowseReadPort.Taxonomy> order =
                Comparator.comparing(
                                (ReleaseBrowseReadPort.Taxonomy value) ->
                                        value.name().toLowerCase(Locale.ROOT))
                        .thenComparing(ReleaseBrowseReadPort.Taxonomy::id);
        List<ReleasePage.Taxonomy> platforms =
                result.platforms().stream()
                        .sorted(order)
                        .map(value -> new ReleasePage.Taxonomy(value.id(), value.name()))
                        .toList();
        List<ReleasePage.Taxonomy> regions =
                result.regions().stream()
                        .sorted(order)
                        .map(value -> new ReleasePage.Taxonomy(value.id(), value.name()))
                        .toList();
        return new ReleasePage.AvailableFilters(platforms, regions);
    }

    private ReleasePage.Item toItem(Item item, Instant evaluatedAt) {
        ReleasePage.Taxonomy platform =
                new ReleasePage.Taxonomy(item.platform().id(), item.platform().name());
        ReleasePage.Taxonomy region =
                new ReleasePage.Taxonomy(item.region().id(), item.region().name());
        ReleasePage.Release release =
                new ReleasePage.Release(
                        item.releaseId(),
                        item.gameId(),
                        platform,
                        region,
                        new ReleasePage.DateValue(
                                ReleasePage.DatePrecision.valueOf(
                                        item.releaseDate().precision().name()),
                                item.releaseDate().value()),
                        ReleasePage.Status.valueOf(item.status().name()),
                        new ReleasePage.Provenance(
                                ReleasePage.Source.valueOf(item.sourceKind().name()),
                                item.sourceName(),
                                item.sourceEntityType()),
                        item.providerUpdatedAt(),
                        item.lastSyncedAt(),
                        item.lastVerifiedAt(),
                        ReleasePage.Verification.valueOf(item.verificationLevel().name()),
                        ReleasePage.Review.valueOf(item.reviewStatus().name()),
                        freshnessPolicy.status(item.lastSyncedAt(), evaluatedAt));
        return new ReleasePage.Item(
                item.gameId(), item.slug(), item.canonicalTitle(), cover(item), release);
    }

    private static ReleasePage.Cover cover(Item item) {
        if (item.cover() instanceof ProviderCoverReference provider) {
            return new ReleasePage.ProviderCoverReference(
                    provider.provider(),
                    provider.reference(),
                    provider.alternativeText(),
                    provider.sourceUrl());
        }
        if (item.cover() instanceof ReleaseBrowseReadPort.ProductCoverReference product) {
            return new ReleasePage.FallbackCover(product.assetPath(), product.alternativeText());
        }
        return new ReleasePage.FallbackCover(FALLBACK_COVER_PATH, FALLBACK_ALTERNATIVE_TEXT);
    }
}
