package com.videogameplatform.catalogue.application.search.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videogameplatform.catalogue.application.CatalogueFreshness;
import com.videogameplatform.catalogue.application.CatalogueNotReadyException;
import com.videogameplatform.catalogue.application.CatalogueReleaseDate;
import com.videogameplatform.catalogue.application.CatalogueReleaseStatus;
import com.videogameplatform.catalogue.application.cover.CatalogueCover;
import com.videogameplatform.catalogue.application.cover.internal.CatalogueCoverPolicy;
import com.videogameplatform.catalogue.application.cover.port.CatalogueCoverReference;
import com.videogameplatform.catalogue.application.cover.port.ProviderCoverReferenceResolver;
import com.videogameplatform.catalogue.application.internal.CatalogueFreshnessPolicy;
import com.videogameplatform.catalogue.application.search.SearchCatalogueResult;
import com.videogameplatform.catalogue.application.search.SearchCatalogueUseCase;
import com.videogameplatform.catalogue.application.search.SearchQueryInvalidException;
import com.videogameplatform.catalogue.application.search.port.GameSearchReadPort;
import com.videogameplatform.catalogue.domain.ReleaseDate;
import com.videogameplatform.catalogue.domain.ReleaseStatus;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CatalogueSearchServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-13T10:00:00Z");
    private static final GameSearchReadPort.Taxonomy PLATFORM =
            new GameSearchReadPort.Taxonomy("platform-1", "PlayStation 5");
    private static final GameSearchReadPort.Taxonomy REGION =
            new GameSearchReadPort.Taxonomy("region-1", "Europe");

    @Test
    void normalizesTheVisitorQueryOnceBeforeItReachesTheStore() {
        AtomicReference<GameSearchReadPort.Criteria> captured = new AtomicReference<>();

        service(
                        criteria -> {
                            captured.set(criteria);
                            return Optional.of(new GameSearchReadPort.Result("v1", List.of(), 0));
                        })
                .search(new SearchCatalogueUseCase.Query("  RESIDENT   Evil 4!  ", 1, 20));

        assertThat(captured.get().normalizedQuery()).isEqualTo("resident evil 4");
        assertThat(captured.get().tokens()).containsExactly("resident", "evil", "4");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "!!!", "-.-"})
    void rejectsAQueryThatCannotAddressTheCatalogue(String text) {
        AtomicReference<Boolean> read = new AtomicReference<>(false);

        assertThatThrownBy(
                        () ->
                                service(
                                                criteria -> {
                                                    read.set(true);
                                                    return Optional.of(
                                                            new GameSearchReadPort.Result(
                                                                    "v1", List.of(), 0));
                                                })
                                        .search(new SearchCatalogueUseCase.Query(text, 1, 20)))
                .isInstanceOf(SearchQueryInvalidException.class);
        assertThat(read.get()).as("no invalid work reaches the store").isFalse();
    }

    @Test
    void rejectsAQueryLongerThanTheContractBound() {
        assertThatThrownBy(
                        () ->
                                service(criteria -> Optional.empty())
                                        .search(
                                                new SearchCatalogueUseCase.Query(
                                                        "a".repeat(101), 1, 20)))
                .isInstanceOf(SearchQueryInvalidException.class);
    }

    @Test
    void acceptsSupplementaryLettersWithinTheCodePointBound() {
        // A supplementary code point uses two Java chars; the contract bounds code points.
        String query = "𐐀".repeat(100);
        SearchCatalogueResult result =
                service(criteria -> Optional.of(new GameSearchReadPort.Result("v1", List.of(), 0)))
                        .search(new SearchCatalogueUseCase.Query(query, 1, 20));
        assertThat(result.items()).isEmpty();
    }

    @Test
    void boundsTheReleaseContextRequestedFromTheStore() {
        AtomicReference<GameSearchReadPort.Criteria> captured = new AtomicReference<>();

        service(
                        criteria -> {
                            captured.set(criteria);
                            return Optional.of(new GameSearchReadPort.Result("v1", List.of(), 0));
                        })
                .search(new SearchCatalogueUseCase.Query("pragmata", 1, 20));

        assertThat(captured.get().releaseContextLimit()).isEqualTo(3);
    }

    @Test
    void passesAPageOffsetInsteadOfAskingForEveryMatch() {
        AtomicReference<GameSearchReadPort.Criteria> captured = new AtomicReference<>();

        service(
                        criteria -> {
                            captured.set(criteria);
                            return Optional.of(new GameSearchReadPort.Result("v1", List.of(), 0));
                        })
                .search(new SearchCatalogueUseCase.Query("pragmata", 4, 25));

        assertThat(captured.get().pagination())
                .isEqualTo(new GameSearchReadPort.Pagination(4, 25, 75));
    }

    @Test
    void reportsZeroResultsAsAValidEmptyPageRatherThanAFailure() {
        SearchCatalogueResult result =
                service(criteria -> Optional.of(new GameSearchReadPort.Result("v1", List.of(), 0)))
                        .search(new SearchCatalogueUseCase.Query("a title nobody curated", 1, 20));

        assertThat(result.items()).isEmpty();
        assertThat(result.page()).isEqualTo(new SearchCatalogueResult.PageMetadata(1, 20, 0, 0));
    }

    @Test
    void reportsTheCatalogueAsNotReadyWhenNothingIsPublished() {
        assertThatThrownBy(
                        () ->
                                service(criteria -> Optional.empty())
                                        .search(
                                                new SearchCatalogueUseCase.Query(
                                                        "pragmata", 1, 20)))
                .isInstanceOf(CatalogueNotReadyException.class);
    }

    @Test
    void roundsTheLastPartialPageUp() {
        SearchCatalogueResult result =
                service(criteria -> Optional.of(new GameSearchReadPort.Result("v1", List.of(), 41)))
                        .search(new SearchCatalogueUseCase.Query("evil", 1, 20));

        assertThat(result.page().totalPages()).isEqualTo(3);
    }

    @Test
    void exposesMatchContextCoverAndBoundedReleaseContextForEachResult() {
        GameSearchReadPort.Item item =
                new GameSearchReadPort.Item(
                        "game-1",
                        "the-witcher-iv",
                        "The Witcher IV",
                        "The Witcher 4",
                        new CatalogueCoverReference.Provider(
                                "IGDB", "cover1", "Carátula", "https://www.igdb.com/games/w4"),
                        List.of(
                                new GameSearchReadPort.ReleaseContext(
                                        PLATFORM,
                                        REGION,
                                        new ReleaseDate.Day(LocalDate.parse("2026-03-18")),
                                        ReleaseStatus.RELEASED,
                                        NOW.minus(Duration.ofDays(30)))));

        SearchCatalogueResult.Item result =
                service(
                                criteria ->
                                        Optional.of(
                                                new GameSearchReadPort.Result(
                                                        "v1", List.of(item), 1)))
                        .search(new SearchCatalogueUseCase.Query("the witcher 4", 1, 20))
                        .items()
                        .getFirst();

        assertThat(result.gameId()).isEqualTo("game-1");
        assertThat(result.canonicalTitle()).isEqualTo("The Witcher IV");
        assertThat(result.matchedAlias()).isEqualTo("The Witcher 4");
        assertThat(result.primaryCover())
                .isEqualTo(
                        new CatalogueCover.Provider(
                                URI.create("https://images.example.test/covers/cover1.webp"),
                                "Carátula",
                                new CatalogueCover.Attribution(
                                        "Test provider",
                                        URI.create("https://www.igdb.com/games/w4"))));
        assertThat(result.releaseContext())
                .containsExactly(
                        new SearchCatalogueResult.ReleaseContext(
                                new SearchCatalogueResult.Taxonomy("platform-1", "PlayStation 5"),
                                new SearchCatalogueResult.Taxonomy("region-1", "Europe"),
                                new CatalogueReleaseDate(
                                        CatalogueReleaseDate.Precision.DAY, "2026-03-18"),
                                CatalogueReleaseStatus.RELEASED,
                                CatalogueFreshness.STALE));
    }

    @Test
    void omitsMatchContextWhenOnlyTheCanonicalTitleMatched() {
        GameSearchReadPort.Item item =
                new GameSearchReadPort.Item(
                        "game-2",
                        "pragmata",
                        "Pragmata",
                        null,
                        new CatalogueCoverReference.Product(
                                "/assets/covers/fallback.svg", "Sin portada"),
                        List.of());

        SearchCatalogueResult.Item result =
                service(
                                criteria ->
                                        Optional.of(
                                                new GameSearchReadPort.Result(
                                                        "v1", List.of(item), 1)))
                        .search(new SearchCatalogueUseCase.Query("pragmata", 1, 20))
                        .items()
                        .getFirst();

        assertThat(result.matchedAlias()).isNull();
        assertThat(result.releaseContext()).isEmpty();
    }

    @Test
    void keepsSeveralMatchingGamesAsSeparateResults() {
        List<GameSearchReadPort.Item> items =
                List.of(
                        game("game-1", "Death Stranding 2: On the Beach"),
                        game("game-2", "Subnautica 2"));

        SearchCatalogueResult result =
                service(criteria -> Optional.of(new GameSearchReadPort.Result("v1", items, 2)))
                        .search(new SearchCatalogueUseCase.Query("2", 1, 20));

        assertThat(result.items())
                .extracting(SearchCatalogueResult.Item::gameId)
                .containsExactly("game-1", "game-2");
    }

    @Test
    void rejectsPaginationOutsideTheContractBoundsBeforeReadingAnything() {
        assertThatThrownBy(() -> new SearchCatalogueUseCase.Query("evil", 0, 20))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SearchCatalogueUseCase.Query("evil", 1, 101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static GameSearchReadPort.Item game(String gameId, String canonicalTitle) {
        return new GameSearchReadPort.Item(
                gameId,
                gameId,
                canonicalTitle,
                null,
                new CatalogueCoverReference.Unavailable(),
                List.of());
    }

    private static CatalogueSearchService service(GameSearchReadPort port) {
        return new CatalogueSearchService(
                port,
                new CatalogueCoverPolicy(
                        (provider, reference, sourceUrl) ->
                                new ProviderCoverReferenceResolver.ResolvedProviderCover(
                                        URI.create(
                                                "https://images.example.test/covers/"
                                                        + reference
                                                        + ".webp"),
                                        "Test provider",
                                        URI.create(sourceUrl))),
                Clock.fixed(NOW, ZoneId.of("Europe/Madrid")),
                new CatalogueSearchPolicy(3),
                new CatalogueFreshnessPolicy(Duration.ofDays(7)));
    }
}
