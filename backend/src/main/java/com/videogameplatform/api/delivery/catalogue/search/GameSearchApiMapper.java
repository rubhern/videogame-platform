package com.videogameplatform.api.delivery.catalogue.search;

import com.videogameplatform.api.delivery.catalogue.CatalogueCoverMapper;
import com.videogameplatform.api.generated.model.DayReleaseDate;
import com.videogameplatform.api.generated.model.GameSearchPage;
import com.videogameplatform.api.generated.model.GameSummary;
import com.videogameplatform.api.generated.model.MonthReleaseDate;
import com.videogameplatform.api.generated.model.PageMetadata;
import com.videogameplatform.api.generated.model.Platform;
import com.videogameplatform.api.generated.model.QuarterReleaseDate;
import com.videogameplatform.api.generated.model.Region;
import com.videogameplatform.api.generated.model.ReleaseSummary;
import com.videogameplatform.api.generated.model.UnknownReleaseDate;
import com.videogameplatform.api.generated.model.YearReleaseDate;
import com.videogameplatform.catalogue.application.CatalogueFreshness;
import com.videogameplatform.catalogue.application.CatalogueReleaseDate;
import com.videogameplatform.catalogue.application.CatalogueReleaseStatus;
import com.videogameplatform.catalogue.application.search.SearchCatalogueResult;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

/** Maps provider-independent UC-002 results to the generated HTTP contract. */
@Component
final class GameSearchApiMapper {

    private final CatalogueCoverMapper coverMapper;

    GameSearchApiMapper(CatalogueCoverMapper coverMapper) {
        this.coverMapper = coverMapper;
    }

    GameSearchPage toResponse(SearchCatalogueResult result) {
        List<GameSummary> items = result.items().stream().map(this::toItem).toList();
        return new GameSearchPage(
                items,
                new PageMetadata(
                        result.page().number(),
                        result.page().size(),
                        result.page().totalItems(),
                        result.page().totalPages()));
    }

    private GameSummary toItem(SearchCatalogueResult.Item item) {
        GameSummary summary =
                new GameSummary(
                        item.gameId(),
                        item.slug(),
                        item.canonicalTitle(),
                        coverMapper.toResponse(item.primaryCover()),
                        item.releaseContext().stream()
                                .map(GameSearchApiMapper::toContext)
                                .toList());
        summary.setMatchedAlias(item.matchedAlias());
        return summary;
    }

    private static ReleaseSummary toContext(SearchCatalogueResult.ReleaseContext context) {
        return new ReleaseSummary(
                new Platform(context.platform().id(), context.platform().name()),
                new Region(context.region().id(), context.region().name()),
                toReleaseDate(context.releaseDate()),
                toStatus(context.status()),
                toFreshnessStatus(context.freshnessStatus()));
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

    private static ReleaseSummary.StatusEnum toStatus(CatalogueReleaseStatus source) {
        return switch (source) {
            case ANNOUNCED -> ReleaseSummary.StatusEnum.ANNOUNCED;
            case SCHEDULED -> ReleaseSummary.StatusEnum.SCHEDULED;
            case RELEASED -> ReleaseSummary.StatusEnum.RELEASED;
            case DELAYED -> ReleaseSummary.StatusEnum.DELAYED;
            case CANCELLED -> ReleaseSummary.StatusEnum.CANCELLED;
            case UNKNOWN -> ReleaseSummary.StatusEnum.UNKNOWN;
        };
    }

    private static ReleaseSummary.FreshnessStatusEnum toFreshnessStatus(CatalogueFreshness source) {
        return switch (source) {
            case FRESH -> ReleaseSummary.FreshnessStatusEnum.FRESH;
            case STALE -> ReleaseSummary.FreshnessStatusEnum.STALE;
        };
    }
}
