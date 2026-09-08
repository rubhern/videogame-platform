package com.videogameplatform.catalogue.application.details.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videogameplatform.catalogue.application.CatalogueFreshness;
import com.videogameplatform.catalogue.application.cover.internal.CatalogueCoverPolicy;
import com.videogameplatform.catalogue.application.cover.port.CatalogueCoverReference;
import com.videogameplatform.catalogue.application.details.GameDetailsResult;
import com.videogameplatform.catalogue.application.details.GameNotFoundException;
import com.videogameplatform.catalogue.application.details.port.GameDetailsReadPort;
import com.videogameplatform.catalogue.application.internal.CatalogueFreshnessPolicy;
import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort;
import com.videogameplatform.catalogue.domain.ReleaseDate;
import com.videogameplatform.catalogue.domain.ReleaseStatus;
import com.videogameplatform.catalogue.domain.ReviewStatus;
import com.videogameplatform.catalogue.domain.SourceKind;
import com.videogameplatform.catalogue.domain.VerificationLevel;
import com.videogameplatform.ratings.application.RatingStatistics;
import com.videogameplatform.ratings.application.internal.RatingContextService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GameDetailsServiceTest {
    @ParameterizedTest
    @CsvSource({
        "2026-08-12T21:59:59Z,2026-08-12,false",
        "2026-08-12T22:00:00Z,2026-08-13,true",
        "2026-12-31T23:00:00Z,2027-01-01,true"
    })
    void usesMadridEvenWhenInjectedClockHasAnotherZoneAndStaleEvidenceStillProvesRelease(
            String instant, String date, boolean eligible) {
        var game = service(id -> Optional.of(game()), instant).get("game");
        assertThat(game.evaluatedOn()).isEqualTo(date);
        assertThat(game.releases().getFirst().freshnessStatus())
                .isEqualTo(CatalogueFreshness.STALE);
        var context =
                new RatingContextService(
                                id ->
                                        new RatingStatistics.Available(
                                                null, 0, Collections.nCopies(10, 0)))
                        .get(game);
        assertThat(context.eligible()).isEqualTo(eligible);
        assertThat(context.evaluatedOn()).isEqualTo(date);
        assertThat(game.summary().text()).isEqualTo("Editorial summary");
    }

    @Test
    void keepsMissingDistinctAndDoesNotAskAProvider() {
        var details = service(id -> Optional.empty(), "2026-08-13T10:00:00Z");
        assertThatThrownBy(() -> details.get("unknown")).isInstanceOf(GameNotFoundException.class);
    }

    private static GameDetailsService service(GameDetailsReadPort port, String instant) {
        return new GameDetailsService(
                port,
                new CatalogueCoverPolicy(
                        (provider, reference, sourceUrl) -> {
                            throw new AssertionError("No provider resolution for fallback");
                        }),
                new CatalogueFreshnessPolicy(Duration.ofDays(1)),
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private static GameDetailsReadPort.Game game() {
        var cover = new CatalogueCoverReference.Product("/assets/covers/fallback.svg", "Fallback");
        var tuple =
                new ReleaseBrowseReadPort.Item(
                        "release",
                        "game",
                        "game",
                        "Game",
                        cover,
                        new ReleaseBrowseReadPort.Taxonomy("pc", "PC"),
                        new ReleaseBrowseReadPort.Taxonomy("eu", "Europe"),
                        new ReleaseDate.Day(LocalDate.of(2026, 8, 13)),
                        ReleaseStatus.RELEASED,
                        SourceKind.PRODUCT_CURATED,
                        "Product",
                        "release",
                        null,
                        Instant.parse("2026-01-01T00:00:00Z"),
                        null,
                        VerificationLevel.PROVIDER_ONLY,
                        ReviewStatus.NOT_REQUIRED);
        return new GameDetailsReadPort.Game(
                "game",
                "game",
                "Game",
                List.of("Alias"),
                new GameDetailsResult.Summary("editorial", "Editorial summary", "en", null),
                cover,
                List.of(tuple));
    }
}
