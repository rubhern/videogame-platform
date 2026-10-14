package com.videogameplatform.api.delivery.catalogue;

import com.videogameplatform.api.generated.model.Attribution;
import com.videogameplatform.catalogue.application.releases.ExternalPlayerScore;
import java.math.RoundingMode;
import java.time.ZoneOffset;

/** One HTTP mapping for the external player score on discovery and game details. */
public final class ExternalPlayerScoreMapper {
    private ExternalPlayerScoreMapper() {}

    /** The persisted 0–100 mean on the declared /10 display scale, rounded half up. */
    public static com.videogameplatform.api.generated.model.ExternalPlayerScore toResponse(
            ExternalPlayerScore score) {
        var wire =
                new com.videogameplatform.api.generated.model.ExternalPlayerScore(
                        com.videogameplatform.api.generated.model.ExternalPlayerScore.SourceEnum
                                .IGDB,
                        com.videogameplatform.api.generated.model.ExternalPlayerScore.KindEnum
                                .PLAYER,
                        score.value().movePointLeft(1).setScale(1, RoundingMode.HALF_UP),
                        10,
                        score.count(),
                        new Attribution("Jugadores IGDB", score.attribution()),
                        score.observedAt().atOffset(ZoneOffset.UTC));
        score.providerRecordUpdatedAt()
                .map(i -> i.atOffset(ZoneOffset.UTC))
                .ifPresent(wire::setProviderRecordUpdatedAt);
        return wire;
    }
}
