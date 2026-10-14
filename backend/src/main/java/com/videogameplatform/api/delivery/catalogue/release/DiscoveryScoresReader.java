package com.videogameplatform.api.delivery.catalogue.release;

import com.videogameplatform.api.generated.model.Attribution;
import com.videogameplatform.api.generated.model.AvailableRatingSummary;
import com.videogameplatform.api.generated.model.DiscoveryScores;
import com.videogameplatform.api.generated.model.ExternalPlayerScore;
import com.videogameplatform.api.generated.model.RatingSummary;
import com.videogameplatform.api.generated.model.UnavailableRatingStatistics;
import com.videogameplatform.catalogue.application.releases.GetExternalPlayerScoresUseCase;
import com.videogameplatform.ratings.application.GetRatingSummariesUseCase;
import java.math.RoundingMode;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Delivery composes the two module contracts after selection; neither module reads the other. */
@Component
public final class DiscoveryScoresReader {
    private final GetExternalPlayerScoresUseCase external;
    private final GetRatingSummariesUseCase community;

    public DiscoveryScoresReader(
            GetExternalPlayerScoresUseCase external, GetRatingSummariesUseCase community) {
        this.external = external;
        this.community = community;
    }

    Map<String, DiscoveryScores> read(Set<String> gameIds) {
        if (gameIds.isEmpty()) return Map.of();
        var externalScores = external.get(gameIds);
        var summaries = community.get(gameIds);
        var result = new HashMap<String, DiscoveryScores>();
        for (String id : gameIds) {
            var summary = summaries.getOrDefault(id, Optional.empty());
            var score =
                    new DiscoveryScores(
                            summary.<RatingSummary>map(
                                            s ->
                                                    new AvailableRatingSummary(
                                                            "available", s.mean(), s.count()))
                                    .orElseGet(
                                            () ->
                                                    new UnavailableRatingStatistics(
                                                            "unavailable",
                                                            "RATING_STATISTICS_READ_FAILED")));
            var other = externalScores.get(id);
            if (other != null) {
                var wire =
                        new ExternalPlayerScore(
                                ExternalPlayerScore.SourceEnum.IGDB,
                                ExternalPlayerScore.KindEnum.PLAYER,
                                other.value().movePointLeft(1).setScale(1, RoundingMode.HALF_UP),
                                10,
                                other.count(),
                                new Attribution("Jugadores IGDB", other.attribution()),
                                other.observedAt().atOffset(ZoneOffset.UTC));
                other.providerRecordUpdatedAt()
                        .map(i -> i.atOffset(ZoneOffset.UTC))
                        .ifPresent(wire::setProviderRecordUpdatedAt);
                score.setExternal(wire);
            }
            result.put(id, score);
        }
        return Map.copyOf(result);
    }
}
