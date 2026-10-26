package com.videogameplatform.catalogue.application.rankings.port;

import com.videogameplatform.catalogue.application.cover.port.CatalogueCoverReference;
import com.videogameplatform.catalogue.application.details.GameDetailsResult;
import com.videogameplatform.catalogue.application.rankings.GameRanking;
import com.videogameplatform.catalogue.application.rankings.RankGamesUseCase.Source;
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
     */
    record Criteria(
            Source source, LocalDate evaluatedOn, Pagination pagination, int summaryPlatformLimit) {
        public Criteria {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(evaluatedOn, "evaluatedOn");
            Objects.requireNonNull(pagination, "pagination");
            if (summaryPlatformLimit < 1) {
                throw new IllegalArgumentException("Summary platforms must be bounded");
            }
        }
    }

    record Pagination(int pageNumber, int pageSize, long offset) {}

    /** The page's games in ranking order and the number of ranked games. */
    record Result(String publicationVersion, List<Item> items, long totalItems) {
        public Result {
            items = List.copyOf(items);
        }
    }

    record Item(
            String gameId,
            String slug,
            String canonicalTitle,
            CatalogueCoverReference cover,
            List<GameDetailsResult.Term> genres,
            GameRanking.ReleaseSummary releaseSummary,
            GameRanking.Score score) {}
}
