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
import com.videogameplatform.api.generated.model.ReleaseItem;
import com.videogameplatform.api.generated.model.ReleaseWindow;
import com.videogameplatform.api.generated.model.UnknownReleaseDate;
import com.videogameplatform.api.generated.model.YearReleaseDate;
import com.videogameplatform.catalogue.application.ReleasePage;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** Maps provider-independent UC-001 results to the generated HTTP contract. */
@Component
final class ReleaseApiMapper {

    private static final String IGDB_COVER_BASE =
            "https://images.igdb.com/igdb/image/upload/t_cover_big/";

    com.videogameplatform.api.generated.model.ReleasePage toResponse(ReleasePage result) {
        List<Platform> platforms =
                result.availableFilters().platforms().stream()
                        .map(value -> new Platform(value.id(), value.name()))
                        .toList();
        List<Region> regions =
                result.availableFilters().regions().stream()
                        .map(value -> new Region(value.id(), value.name()))
                        .toList();
        List<ReleaseItem> items = result.items().stream().map(this::toItem).toList();
        return new com.videogameplatform.api.generated.model.ReleasePage(
                com.videogameplatform.api.generated.model.ReleasePage.ViewEnum.fromValue(
                        result.view().name().toLowerCase(Locale.ROOT)),
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

    private ReleaseItem toItem(ReleasePage.Item item) {
        ReleasePage.Release source = item.release();
        com.videogameplatform.api.generated.model.Release release =
                new com.videogameplatform.api.generated.model.Release(
                        source.releaseId(),
                        source.gameId(),
                        new Platform(source.platform().id(), source.platform().name()),
                        new Region(source.region().id(), source.region().name()),
                        toReleaseDate(source.releaseDate()),
                        com.videogameplatform.api.generated.model.Release.StatusEnum.fromValue(
                                source.status().value()),
                        new Provenance(
                                Provenance.SourceKindEnum.fromValue(
                                        source.provenance().sourceKind().value()),
                                source.provenance().sourceName(),
                                source.provenance().sourceEntityType()),
                        toOffsetDateTime(source.lastSyncedAt()),
                        com.videogameplatform.api.generated.model.Release.VerificationLevelEnum
                                .fromValue(source.verificationLevel().value()),
                        com.videogameplatform.api.generated.model.Release.ReviewStatusEnum
                                .fromValue(source.reviewStatus().value()),
                        com.videogameplatform.api.generated.model.Release.FreshnessStatusEnum
                                .fromValue(source.freshnessStatus().value()));
        release.setProviderUpdatedAt(toOffsetDateTime(source.providerUpdatedAt()));
        release.setLastVerifiedAt(toOffsetDateTime(source.lastVerifiedAt()));
        return new ReleaseItem(
                item.gameId(),
                item.slug(),
                item.canonicalTitle(),
                toCover(item.primaryCover()),
                release);
    }

    private static Cover toCover(ReleasePage.Cover source) {
        if (source instanceof ReleasePage.ProviderCoverReference provider) {
            if (!"IGDB".equalsIgnoreCase(provider.provider())) {
                throw new IllegalStateException("Unsupported published cover provider");
            }
            return new ProviderCover(
                    "provider",
                    URI.create(IGDB_COVER_BASE + provider.reference() + ".webp"),
                    provider.alternativeText(),
                    new Attribution("IGDB", URI.create(provider.sourceUrl())));
        }
        ReleasePage.FallbackCover fallback = (ReleasePage.FallbackCover) source;
        return new FallbackCover(
                "fallback", fallback.assetPath(), fallback.alternativeText(), null);
    }

    private static com.videogameplatform.api.generated.model.ReleaseDate toReleaseDate(
            ReleasePage.DateValue source) {
        return switch (source.precision()) {
            case DAY -> new DayReleaseDate("day", LocalDate.parse(source.value()));
            case MONTH -> new MonthReleaseDate("month", source.value());
            case QUARTER -> new QuarterReleaseDate("quarter", source.value());
            case YEAR -> new YearReleaseDate("year", source.value());
            case UNKNOWN -> new UnknownReleaseDate("unknown", null);
        };
    }

    private static OffsetDateTime toOffsetDateTime(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
