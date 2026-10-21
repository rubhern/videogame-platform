package com.videogameplatform.api.delivery.catalogue.release;

import com.videogameplatform.api.delivery.catalogue.CatalogueCoverMapper;
import com.videogameplatform.api.generated.model.ActiveFilters;
import com.videogameplatform.api.generated.model.AvailableFilters;
import com.videogameplatform.api.generated.model.DayReleaseDate;
import com.videogameplatform.api.generated.model.MonthReleaseDate;
import com.videogameplatform.api.generated.model.PageMetadata;
import com.videogameplatform.api.generated.model.Platform;
import com.videogameplatform.api.generated.model.Provenance;
import com.videogameplatform.api.generated.model.QuarterReleaseDate;
import com.videogameplatform.api.generated.model.Region;
import com.videogameplatform.api.generated.model.Release;
import com.videogameplatform.api.generated.model.ReleaseItem;
import com.videogameplatform.api.generated.model.ReleasePage;
import com.videogameplatform.api.generated.model.ReleaseWindow;
import com.videogameplatform.api.generated.model.UnknownReleaseDate;
import com.videogameplatform.api.generated.model.YearReleaseDate;
import com.videogameplatform.catalogue.application.CatalogueReleaseDate;
import com.videogameplatform.catalogue.application.CatalogueReleaseStatus;
import com.videogameplatform.catalogue.application.releases.BrowseReleasesResult;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Component;

/** Maps provider-independent UC-001 results to the generated HTTP contract. */
@Component
public final class ReleaseApiMapper {

    private final CatalogueCoverMapper coverMapper;

    ReleaseApiMapper(CatalogueCoverMapper coverMapper) {
        this.coverMapper = coverMapper;
    }

    ReleasePage toResponse(BrowseReleasesResult result) {
        List<ReleaseItem> items = result.items().stream().map(this::toItem).toList();
        return new ReleasePage(
                result.evaluatedOn(),
                new ReleaseWindow(result.window().from(), result.window().to()),
                toActiveFilters(result.activeFilters()),
                toAvailableFilters(result.availableFilters()),
                items,
                new PageMetadata(
                        result.page().number(),
                        result.page().size(),
                        result.page().totalItems(),
                        result.page().totalPages()));
    }

    /** The applied filters; shared by the release explorer and the release calendar. */
    static ActiveFilters toActiveFilters(BrowseReleasesResult.ActiveFilters source) {
        return new ActiveFilters(
                new java.util.LinkedHashSet<>(source.platformIds()),
                new java.util.LinkedHashSet<>(source.regionIds()));
    }

    /** The contextual facets; shared by the release explorer and the release calendar. */
    static AvailableFilters toAvailableFilters(BrowseReleasesResult.AvailableFilters source) {
        List<Platform> platforms =
                source.platforms().stream()
                        .map(value -> new Platform(value.id(), value.name()))
                        .toList();
        List<Region> regions =
                source.regions().stream()
                        .map(value -> new Region(value.id(), value.name()))
                        .toList();
        return new AvailableFilters(platforms, regions);
    }

    /** One game with its presented releases; shared by discovery and the featured selection. */
    ReleaseItem toItem(BrowseReleasesResult.Item item) {
        List<Release> releases = item.releases().stream().map(this::toRelease).toList();
        ReleaseItem result =
                new ReleaseItem(
                        item.gameId(),
                        item.slug(),
                        item.canonicalTitle(),
                        coverMapper.toResponse(item.primaryCover()),
                        releases);
        result.setGenres(
                item.genres().stream()
                        .map(
                                term ->
                                        new com.videogameplatform.api.generated.model.Genre(
                                                term.code(), term.name()))
                        .collect(
                                java.util.stream.Collectors.toCollection(
                                        java.util.LinkedHashSet::new)));
        return result;
    }

    public Release toRelease(BrowseReleasesResult.Release source) {
        Release release =
                new Release(
                        source.releaseId(),
                        source.gameId(),
                        new Platform(source.platform().id(), source.platform().name()),
                        new Region(source.region().id(), source.region().name()),
                        toReleaseDate(source.releaseDate()),
                        toStatus(source.status()),
                        com.videogameplatform.api.generated.model.ReleaseStage.valueOf(
                                source.stage().name()),
                        new Provenance(
                                toSourceKind(source.provenance().sourceKind()),
                                source.provenance().sourceName(),
                                source.provenance().sourceEntityType()),
                        toVerificationLevel(source.verificationLevel()),
                        toReviewStatus(source.reviewStatus()));
        release.setProviderUpdatedAt(toOffsetDateTime(source.providerUpdatedAt()));
        release.setLastVerifiedAt(toOffsetDateTime(source.lastVerifiedAt()));
        return release;
    }

    private static com.videogameplatform.api.generated.model.ReleaseDate toReleaseDate(
            CatalogueReleaseDate source) {
        return switch (source.precision()) {
            case DAY -> new DayReleaseDate("day", LocalDate.parse(source.value()));
            case MONTH -> new MonthReleaseDate("month", source.value());
            case QUARTER -> new QuarterReleaseDate("quarter", source.value());
            case YEAR -> new YearReleaseDate("year", source.value());
            case UNKNOWN -> new UnknownReleaseDate("unknown", null);
        };
    }

    private static Release.StatusEnum toStatus(CatalogueReleaseStatus source) {
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

    private static OffsetDateTime toOffsetDateTime(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
