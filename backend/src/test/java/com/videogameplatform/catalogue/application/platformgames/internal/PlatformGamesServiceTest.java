package com.videogameplatform.catalogue.application.platformgames.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videogameplatform.catalogue.application.CatalogueNotReadyException;
import com.videogameplatform.catalogue.application.CatalogueReleaseDate;
import com.videogameplatform.catalogue.application.CatalogueReleaseStatus;
import com.videogameplatform.catalogue.application.cover.internal.CatalogueCoverPolicy;
import com.videogameplatform.catalogue.application.cover.port.CatalogueCoverReference;
import com.videogameplatform.catalogue.application.platformgames.BrowsePlatformGamesUseCase.Query;
import com.videogameplatform.catalogue.application.platformgames.BrowsePlatformGamesUseCase.Sort;
import com.videogameplatform.catalogue.application.platformgames.PlatformGames;
import com.videogameplatform.catalogue.application.platformgames.PlatformGamesQueryException;
import com.videogameplatform.catalogue.application.platformgames.port.PlatformGamesReadPort;
import com.videogameplatform.catalogue.application.search.SearchQueryInvalidException;
import com.videogameplatform.catalogue.domain.ReleaseDate;
import com.videogameplatform.catalogue.domain.ReleaseStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PlatformGamesServiceTest {

    // 23:30 UTC is already the next day in Madrid.
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-12T23:30:00Z"), ZoneId.of("Europe/Madrid"));
    private static final PlatformGames.Taxonomy SWITCH =
            new PlatformGames.Taxonomy("platform-switch", "Switch Two");

    @Test
    void asksTheStoreForTheMadridDateTheNormalizedSearchAndThePagesOffset() {
        var port = new RecordingPort(List.of(SWITCH), List.of(), 23);
        var result =
                service(port)
                        .browse(query(List.of("platform-switch"), "  Pokémon  Leyendas ", 3, 10));

        var asked = port.asked.getFirst();
        assertThat(asked.evaluatedOn()).isEqualTo(LocalDate.of(2026, 8, 13));
        assertThat(asked.search().normalizedQuery()).isEqualTo("pokemon leyendas");
        assertThat(asked.search().tokens()).containsExactly("pokemon", "leyendas");
        assertThat(asked.pagination()).isEqualTo(new PlatformGamesReadPort.Pagination(3, 10, 20));
        assertThat(result.page()).isEqualTo(new PlatformGames.PageMetadata(3, 10, 23, 3));
        assertThat(result.platforms()).containsExactly(SWITCH);
    }

    @Test
    void browsesWithoutASearchWhenNoTextIsGiven() {
        var port = new RecordingPort(List.of(SWITCH), List.of(), 0);
        var result = service(port).browse(query(List.of("platform-switch"), null, 1, 24));

        assertThat(port.asked.getFirst().search()).isNull();
        assertThat(result.items()).isEmpty();
        assertThat(result.page().totalPages()).isZero();
    }

    @Test
    void presentsTheEffectiveStatusOfTheDateNotTheStoredEvidence() {
        var port =
                new RecordingPort(
                        List.of(SWITCH),
                        List.of(
                                item(
                                        "today",
                                        new ReleaseDate.Day(LocalDate.of(2026, 8, 13)),
                                        ReleaseStatus.ANNOUNCED),
                                item(
                                        "tomorrow",
                                        new ReleaseDate.Day(LocalDate.of(2026, 8, 14)),
                                        ReleaseStatus.ANNOUNCED),
                                item("undated", new ReleaseDate.Unknown(), ReleaseStatus.ANNOUNCED),
                                item(
                                        "delayed",
                                        new ReleaseDate.Day(LocalDate.of(2026, 1, 1)),
                                        ReleaseStatus.DELAYED)),
                        4);

        var items = service(port).browse(query(List.of("platform-switch"), null, 1, 24)).items();

        assertThat(items)
                .extracting(item -> item.platformRelease().status())
                .containsExactly(
                        CatalogueReleaseStatus.RELEASED,
                        CatalogueReleaseStatus.SCHEDULED,
                        CatalogueReleaseStatus.ANNOUNCED,
                        CatalogueReleaseStatus.DELAYED);
        assertThat(items.get(2).platformRelease().releaseDate())
                .isEqualTo(new CatalogueReleaseDate(CatalogueReleaseDate.Precision.UNKNOWN, null));
    }

    @Test
    void rejectsAMissingOrUnknownPlatformAndAnUnknownGenre() {
        var unknownPlatform = service(new RecordingPort(List.of(SWITCH), List.of(), 0));
        var twoPlatforms = query(List.of("platform-switch", "platform-invented"), null, 1, 24);
        assertThatThrownBy(() -> unknownPlatform.browse(twoPlatforms))
                .isInstanceOfSatisfying(
                        PlatformGamesQueryException.class,
                        exception ->
                                assertThat(exception.code())
                                        .isEqualTo(
                                                PlatformGamesQueryException.Code
                                                        .PLATFORM_NOT_SUPPORTED));
        var none = query(List.of(), null, 1, 24);
        assertThatThrownBy(() -> unknownPlatform.browse(none))
                .isInstanceOf(PlatformGamesQueryException.class);

        var genres =
                new Query(
                        List.of("platform-switch"),
                        null,
                        null,
                        null,
                        null,
                        List.of("adventure", "invented"),
                        Sort.RELEASE_DATE_DESC,
                        1,
                        24);
        assertThatThrownBy(() -> unknownPlatform.browse(genres))
                .isInstanceOfSatisfying(
                        PlatformGamesQueryException.class,
                        exception ->
                                assertThat(exception.code())
                                        .isEqualTo(
                                                PlatformGamesQueryException.Code
                                                        .GENRE_NOT_SUPPORTED));
    }

    @Test
    void rejectsAReversedYearRangeAndASearchWithoutAToken() {
        var port = new RecordingPort(List.of(SWITCH), List.of(), 0);
        var reversed =
                new Query(
                        List.of("platform-switch"),
                        null,
                        null,
                        2027,
                        2026,
                        List.of(),
                        Sort.RELEASE_DATE_DESC,
                        1,
                        24);
        assertThatThrownBy(() -> service(port).browse(reversed))
                .isInstanceOfSatisfying(
                        PlatformGamesQueryException.class,
                        exception ->
                                assertThat(exception.code())
                                        .isEqualTo(
                                                PlatformGamesQueryException.Code
                                                        .YEAR_RANGE_INVALID));
        var punctuation = query(List.of("platform-switch"), " ¡¿!? ", 1, 24);
        assertThatThrownBy(() -> service(port).browse(punctuation))
                .isInstanceOf(SearchQueryInvalidException.class);
        var tooLong = query(List.of("platform-switch"), "a".repeat(101), 1, 24);
        assertThatThrownBy(() -> service(port).browse(tooLong))
                .isInstanceOf(SearchQueryInvalidException.class);
        assertThat(port.asked).isEmpty();
    }

    @Test
    void reportsACatalogueThatWasNeverPublished() {
        var service = service(criteria -> Optional.empty());
        var query = query(List.of("platform-switch"), null, 1, 24);
        assertThatThrownBy(() -> service.browse(query))
                .isInstanceOf(CatalogueNotReadyException.class);
    }

    @Test
    void rejectsAnUnboundedQuery() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> query(List.of("platform-switch"), null, 0, 24));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> query(List.of("platform-switch"), null, 1, 101));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new Query(
                                        List.of("platform-switch"),
                                        null,
                                        null,
                                        null,
                                        null,
                                        List.of(),
                                        null,
                                        1,
                                        24));
    }

    private static Query query(List<String> platforms, String text, int page, int size) {
        return new Query(
                platforms, text, null, null, null, List.of(), Sort.RELEASE_DATE_DESC, page, size);
    }

    private static PlatformGamesReadPort.Item item(
            String id, ReleaseDate date, ReleaseStatus status) {
        return new PlatformGamesReadPort.Item(
                id,
                id,
                id,
                null,
                new CatalogueCoverReference.Product("/assets/covers/fallback.svg", "Portada"),
                List.of(),
                SWITCH,
                new PlatformGames.Taxonomy("region-world", "Mundial"),
                date,
                status);
    }

    private static PlatformGamesService service(PlatformGamesReadPort port) {
        return new PlatformGamesService(
                port,
                new CatalogueCoverPolicy(
                        (provider, reference, sourceUrl) -> {
                            throw new AssertionError("No provider resolution for fallback");
                        }),
                CLOCK);
    }

    private static final class RecordingPort implements PlatformGamesReadPort {
        private final List<PlatformGames.Taxonomy> platforms;
        private final List<Item> items;
        private final long total;
        private final List<Criteria> asked = new ArrayList<>();

        private RecordingPort(
                List<PlatformGames.Taxonomy> platforms, List<Item> items, long total) {
            this.platforms = platforms;
            this.items = items;
            this.total = total;
        }

        @Override
        public Optional<Result> findPlatformGames(Criteria criteria) {
            asked.add(criteria);
            // The store knows only genres it holds; "invented" is never one.
            List<String> known =
                    criteria.genreIds().stream().filter(id -> !id.equals("invented")).toList();
            return Optional.of(
                    new Result("v1", platforms, known, List.of(), null, null, items, total));
        }
    }
}
