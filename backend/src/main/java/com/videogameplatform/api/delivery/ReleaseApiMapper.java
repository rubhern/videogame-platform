package com.videogameplatform.api.delivery;

import com.videogameplatform.api.generated.model.ActiveFilters;
import com.videogameplatform.api.generated.model.Attribution;
import com.videogameplatform.api.generated.model.AvailableFilters;
import com.videogameplatform.api.generated.model.Cover;
import com.videogameplatform.api.generated.model.DayReleaseDate;
import com.videogameplatform.api.generated.model.FallbackCover;
import com.videogameplatform.api.generated.model.MonthReleaseDate;
import com.videogameplatform.api.generated.model.PageMetadata;
import com.videogameplatform.api.generated.model.Platform;
import com.videogameplatform.api.generated.model.Provenance;
import com.videogameplatform.api.generated.model.ProviderCover;
import com.videogameplatform.api.generated.model.QuarterReleaseDate;
import com.videogameplatform.api.generated.model.Region;
import com.videogameplatform.api.generated.model.Release;
import com.videogameplatform.api.generated.model.ReleaseItem;
import com.videogameplatform.api.generated.model.ReleasePage;
import com.videogameplatform.api.generated.model.ReleaseWindow;
import com.videogameplatform.api.generated.model.UnknownReleaseDate;
import com.videogameplatform.api.generated.model.YearReleaseDate;
import com.videogameplatform.catalogue.application.BrowseReleasesResult;
import com.videogameplatform.catalogue.application.BrowseReleasesUseCase;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Component;

/** Maps provider-independent UC-001 results to the generated HTTP contract. */
@Component
final class ReleaseApiMapper {

    private static final String IGDB_COVER_BASE =
            "https://images.igdb.com/igdb/image/upload/t_cover_big/";

    ReleasePage toResponse(BrowseReleasesResult result) {
        List<Platform> platforms =
                result.availableFilters().platforms().stream()
                        .map(value -> new Platform(value.id(), value.name()))
                        .toList();
        List<Region> regions =
                result.availableFilters().regions().stream()
                        .map(value -> new Region(value.id(), value.name()))
                        .toList();
        List<ReleaseItem> items = result.items().stream().map(this::toItem).toList();
        return new ReleasePage(
                toView(result.view()),
                result.evaluatedOn(),
                new ReleaseWindow(result.window().from(), result.window().to()),
                new ActiveFilters(
                        result.activeFilters().platformId(), result.activeFilters().regionId()),
                new AvailableFilters(platforms, regions),
                items,
                new PageMetadata(
                        result.page().number(),
                        result.page().size(),
                        result.page().totalItems(),
                        result.page().totalPages()));
    }

    private ReleaseItem toItem(BrowseReleasesResult.Item item) {
        BrowseReleasesResult.Release source = item.release();
        Release release =
                new Release(
                        source.releaseId(),
                        source.gameId(),
                        new Platform(source.platform().id(), source.platform().name()),
                        new Region(source.region().id(), source.region().name()),
                        toReleaseDate(source.releaseDate()),
                        toStatus(source.status()),
                        new Provenance(
                                toSourceKind(source.provenance().sourceKind()),
                                source.provenance().sourceName(),
                                source.provenance().sourceEntityType()),
                        toOffsetDateTime(source.lastSyncedAt()),
                        toVerificationLevel(source.verificationLevel()),
                        toReviewStatus(source.reviewStatus()),
                        toFreshnessStatus(source.freshnessStatus()));
        release.setProviderUpdatedAt(toOffsetDateTime(source.providerUpdatedAt()));
        release.setLastVerifiedAt(toOffsetDateTime(source.lastVerifiedAt()));
        return new ReleaseItem(
                item.gameId(),
                item.slug(),
                item.canonicalTitle(),
                toCover(item.primaryCover()),
                release);
    }

    private static Cover toCover(BrowseReleasesResult.Cover source) {
        return switch (source) {
            case BrowseReleasesResult.ProviderCoverReference provider -> {
                if (!"IGDB".equalsIgnoreCase(provider.provider())) {
                    throw new IllegalStateException("Unsupported published cover provider");
                }
                yield new ProviderCover(
                        "provider",
                        URI.create(IGDB_COVER_BASE + provider.reference() + ".webp"),
                        provider.alternativeText(),
                        new Attribution("IGDB", URI.create(provider.sourceUrl())));
            }
            case BrowseReleasesResult.FallbackCover fallback ->
                    new FallbackCover(
                            "fallback", fallback.assetPath(), fallback.alternativeText(), null);
        };
    }

    private static com.videogameplatform.api.generated.model.ReleaseDate toReleaseDate(
            BrowseReleasesResult.DateValue source) {
        return switch (source.precision()) {
            case DAY -> new DayReleaseDate("day", LocalDate.parse(source.value()));
            case MONTH -> new MonthReleaseDate("month", source.value());
            case QUARTER -> new QuarterReleaseDate("quarter", source.value());
            case YEAR -> new YearReleaseDate("year", source.value());
            case UNKNOWN -> new UnknownReleaseDate("unknown", null);
        };
    }

    private static ReleasePage.ViewEnum toView(BrowseReleasesUseCase.View source) {
        return switch (source) {
            case RECENT -> ReleasePage.ViewEnum.RECENT;
            case UPCOMING -> ReleasePage.ViewEnum.UPCOMING;
        };
    }

    private static Release.StatusEnum toStatus(BrowseReleasesResult.Status source) {
        return switch (source) {
            case ANNOUNCED -> Release.StatusEnum.ANNOUNCED;
            case SCHEDULED -> Release.StatusEnum.SCHEDULED;
            case RELEASED -> Release.StatusEnum.RELEASED;
            case DELAYED -> Release.StatusEnum.DELAYED;
            case CANCELLED -> Release.StatusEnum.CANCELLED;
            case UNKNOWN -> Release.StatusEnum.UNKNOWN;
        };
    }

    private static Provenance.SourceKindEnum toSourceKind(BrowseReleasesResult.Source source) {
        return switch (source) {
            case EXTERNAL_PROVIDER -> Provenance.SourceKindEnum.EXTERNAL_PROVIDER;
            case PRODUCT_CURATED -> Provenance.SourceKindEnum.PRODUCT_CURATED;
            case OFFICIAL_SOURCE -> Provenance.SourceKindEnum.OFFICIAL_SOURCE;
        };
    }

    private static Release.VerificationLevelEnum toVerificationLevel(
            BrowseReleasesResult.Verification source) {
        return switch (source) {
            case PROVIDER_ONLY -> Release.VerificationLevelEnum.PROVIDER_ONLY;
            case VERIFIED -> Release.VerificationLevelEnum.VERIFIED;
        };
    }

    private static Release.ReviewStatusEnum toReviewStatus(BrowseReleasesResult.Review source) {
        return switch (source) {
            case NOT_REQUIRED -> Release.ReviewStatusEnum.NOT_REQUIRED;
            case REQUIRED -> Release.ReviewStatusEnum.REQUIRED;
        };
    }

    private static Release.FreshnessStatusEnum toFreshnessStatus(
            BrowseReleasesResult.Freshness source) {
        return switch (source) {
            case FRESH -> Release.FreshnessStatusEnum.FRESH;
            case STALE -> Release.FreshnessStatusEnum.STALE;
        };
    }

    private static OffsetDateTime toOffsetDateTime(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
