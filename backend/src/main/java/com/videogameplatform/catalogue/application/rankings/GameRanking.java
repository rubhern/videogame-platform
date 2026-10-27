package com.videogameplatform.catalogue.application.rankings;

import com.videogameplatform.catalogue.application.cover.CatalogueCover;
import com.videogameplatform.catalogue.application.details.GameDetailsResult;
import com.videogameplatform.catalogue.application.releases.ExternalPlayerScore;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * One page of one source's ranking of the current local publication. Every identifier is a
 * product identifier; positions continue across pages, so the first page's first three items are
 * the podium of the same sequence.
 */
public record GameRanking(
        String publicationVersion,
        RankGamesUseCase.Source source,
        List<Item> items,
        PageMetadata page) {

    public GameRanking {
        Objects.requireNonNull(source, "source");
        items = List.copyOf(items);
    }

    /** One ranked game and the reading of the ranked source that placed it. */
    public record Item(
            long position,
            String gameId,
            String slug,
            String canonicalTitle,
            CatalogueCover primaryCover,
            List<GameDetailsResult.Term> genres,
            ReleaseSummary releaseSummary,
            Score score) {
        public Item {
            if (position < 1) {
                throw new IllegalArgumentException("A ranking position is one-based");
            }
            genres = List.copyOf(genres);
            Objects.requireNonNull(score, "score");
        }
    }

    /** The ranked source's reading of a game; never another source's, never a blend. */
    public sealed interface Score {

        /** The IGDB players' current score, its count and attribution. */
        record IgdbPlayers(ExternalPlayerScore score) implements Score {
            public IgdbPlayers {
                Objects.requireNonNull(score, "score");
            }
        }

        /** The Gameómetro community mean at the one decimal shown and its positive count. */
        record Community(BigDecimal mean, int ratingCount) implements Score {
            public Community {
                if (mean == null
                        || mean.scale() > 1
                        || mean.compareTo(BigDecimal.ONE) < 0
                        || mean.compareTo(BigDecimal.TEN) > 0
                        || ratingCount < 1) {
                    throw new IllegalArgumentException("Invalid community ranking score");
                }
            }
        }
    }

    /**
     * Compact aggregate over every stored release of the game: a bounded head of its ordered
     * distinct platforms, their exact count and the earliest and latest known years, both null
     * when no date is known.
     */
    public record ReleaseSummary(
            List<Taxonomy> platforms,
            int totalPlatforms,
            Integer earliestKnownYear,
            Integer latestKnownYear) {
        public ReleaseSummary {
            platforms = List.copyOf(platforms);
            if (platforms.size() > totalPlatforms) {
                throw new IllegalArgumentException(
                        "Summary platforms cannot exceed the distinct platform count");
            }
            if ((earliestKnownYear == null) != (latestKnownYear == null)) {
                throw new IllegalArgumentException("Known years must be both present or absent");
            }
        }
    }

    public record Taxonomy(String id, String name) {}

    public record PageMetadata(int number, int size, long totalItems, long totalPages) {}
}
