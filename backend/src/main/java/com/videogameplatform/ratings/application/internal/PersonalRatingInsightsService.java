package com.videogameplatform.ratings.application.internal;

import com.videogameplatform.ratings.application.GetPersonalRatingInsightsUseCase;
import com.videogameplatform.ratings.application.PersonalRatingInsights;
import com.videogameplatform.ratings.application.port.PersonalRatingInsightsReadPort;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

public final class PersonalRatingInsightsService implements GetPersonalRatingInsightsUseCase {
    private static final ZoneId PRODUCT_ZONE = ZoneId.of("Europe/Madrid");
    private final PersonalRatingInsightsReadPort read;
    private final Clock clock;

    public PersonalRatingInsightsService(PersonalRatingInsightsReadPort read, Clock clock) {
        this.read = read;
        this.clock = clock;
    }

    @Override
    public PersonalRatingInsights get(String userId) {
        return read.read(userId, LocalDate.now(clock.withZone(PRODUCT_ZONE)));
    }
}
