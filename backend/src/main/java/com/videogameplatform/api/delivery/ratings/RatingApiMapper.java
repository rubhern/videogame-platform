package com.videogameplatform.api.delivery.ratings;

import com.videogameplatform.api.generated.model.AvailableRatingStatistics;
import com.videogameplatform.api.generated.model.PersonalRating;
import com.videogameplatform.api.generated.model.RatingDistribution;
import com.videogameplatform.ratings.application.RatingStatistics;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;

/** Maps provider-independent rating results to the generated HTTP transport. */
@Component
final class RatingApiMapper {
    PersonalRating toResponse(com.videogameplatform.ratings.application.PersonalRating rating) {
        return new PersonalRating(
                rating.gameId(),
                rating.value(),
                rating.createdAt().atOffset(ZoneOffset.UTC),
                rating.updatedAt().atOffset(ZoneOffset.UTC),
                entityTag(rating.versionToken()));
    }

    AvailableRatingStatistics toResponse(RatingStatistics.Available statistics) {
        var buckets = statistics.distribution();
        return new AvailableRatingStatistics(
                "available",
                statistics.mean(),
                statistics.count(),
                new RatingDistribution(
                        buckets.get(0),
                        buckets.get(1),
                        buckets.get(2),
                        buckets.get(3),
                        buckets.get(4),
                        buckets.get(5),
                        buckets.get(6),
                        buckets.get(7),
                        buckets.get(8),
                        buckets.get(9)));
    }

    static String entityTag(String versionToken) {
        return '"' + versionToken + '"';
    }
}
