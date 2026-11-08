package com.videogameplatform.catalogue.application.rankings.port;

import com.videogameplatform.catalogue.application.cover.port.CatalogueCoverReference;
import com.videogameplatform.catalogue.application.details.GameDetailsResult;
import com.videogameplatform.catalogue.application.rankings.GameRanking;
import com.videogameplatform.catalogue.application.rankings.RankGamesUseCase.Source;
import com.videogameplatform.catalogue.domain.ReleaseDate;
import com.videogameplatform.catalogue.domain.ReleaseStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Outbound query port for one bounded page of one source's ranking. Selection, eligibility,
 * ordering, counting and paging all happen in the store, in one snapshot of the current
 * publication; only the page's games cross it.
 */
public interface GameRankingReadPort {

    /** Empty when the catalogue has never been published. */
    Optional<Result> findRankedGames(Criteria criteria);

    /**
     * @param evaluatedOn the trusted {@code Europe/Madrid} date that decides whether a release has
     *     effectively occurred (REL-011, REL-012)
     * @param platformIds a non-empty scope admits only games with an eligible release on one of
     *     those platforms, before anything is ordered, counted or paged (RNK-005); empty is global
     */
    record Criteria(
            Source source,
            List<String> platformIds,
            LocalDate evaluatedOn,
            Pagination pagination,
            int summaryPlatformLimit) {
        public Criteria {
            Objects.requireNonNull(source, "source");
            platformIds = platformIds == null ? List.of() : List.copyOf(platformIds);
            Objects.requireNonNull(evaluatedOn, "evaluatedOn");
            Objects.requireNonNull(pagination, "pagination");
            if (summaryPlatformLimit < 1) {
                throw new IllegalArgumentException("Summary platforms must be bounded");
            }
        }
    }

    record Pagination(int pageNumber, int pageSize, long offset) {}

    /**
     * The page's games in ranking order and the number of ranked games. {@code platformsSupported}
     * is false when a scope platform is not part of the taxonomy, in which case nothing was ranked.
     */
    record Result(
            String publicationVersion,
            boolean platformsSupported,
            List<Item> items,
            long totalItems) {
        public Result {
            items = List.copyOf(items);
        }

        public Result(String publicationVersion, List<Item> items, long totalItems) {
            this(publicationVersion, true, items, totalItems);
        }
    }

    /** {@code platformRelease} is present exactly when the criteria name a platform scope. */
    record Item(
            String gameId,
            String slug,
            String canonicalTitle,
            CatalogueCoverReference cover,
            List<GameDetailsResult.Term> genres,
            GameRanking.ReleaseSummary releaseSummary,
            Optional<PlatformRelease> platformRelease,
            GameRanking.Score score) {
        public Item {
            Objects.requireNonNull(platformRelease, "platformRelease");
        }
    }

    /** The presented scope release with its persisted status evidence. */
    record PlatformRelease(
            GameRanking.Taxonomy platform,
            GameRanking.Taxonomy region,
            ReleaseDate releaseDate,
            ReleaseStatus status) {}
}
