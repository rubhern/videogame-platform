package com.videogameplatform.catalogue.application.details.internal;

import com.videogameplatform.catalogue.application.cover.internal.CatalogueCoverPolicy;
import com.videogameplatform.catalogue.application.details.GameDetailsResult;
import com.videogameplatform.catalogue.application.details.GameNotFoundException;
import com.videogameplatform.catalogue.application.details.GetGameDetailsUseCase;
import com.videogameplatform.catalogue.application.details.port.GameDetailsReadPort;
import com.videogameplatform.catalogue.application.internal.CatalogueFreshnessPolicy;
import com.videogameplatform.catalogue.application.internal.CatalogueReleaseMapping;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/** Framework-independent UC-003 catalogue read with one application evaluation instant. */
public final class GameDetailsService implements GetGameDetailsUseCase {
    private final GameDetailsReadPort readPort;
    private final CatalogueCoverPolicy covers;
    private final CatalogueFreshnessPolicy freshness;
    private final Clock clock;

    public GameDetailsService(
            GameDetailsReadPort readPort,
            CatalogueCoverPolicy covers,
            CatalogueFreshnessPolicy freshness,
            Clock clock) {
        this.readPort = readPort;
        this.covers = covers;
        this.freshness = freshness;
        this.clock = clock;
    }

    @Override
    public GameDetailsResult get(String gameId) {
        var now = clock.instant();
        var game = readPort.find(gameId).orElseThrow(GameNotFoundException::new);
        return new GameDetailsResult(
                game.gameId(),
                game.slug(),
                game.canonicalTitle(),
                game.aliases(),
                game.summary(),
                covers.resolve(game.cover()),
                game.releases().stream()
                        .map(r -> CatalogueReleaseMapping.map(r, now, freshness))
                        .toList(),
                LocalDate.ofInstant(now, ZoneId.of("Europe/Madrid")));
    }
}
