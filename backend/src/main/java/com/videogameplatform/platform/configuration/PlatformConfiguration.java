package com.videogameplatform.platform.configuration;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Technical configuration shared by the executable application boundary. */
@Configuration(proxyBeanMethods = false)
class PlatformConfiguration {

    private static final ZoneId PRODUCT_ZONE = ZoneId.of("Europe/Madrid");

    @Bean
    Clock applicationClock(@Value("${platform.clock.fixed-instant:}") String fixedInstant) {
        return fixedInstant.isBlank()
                ? Clock.system(PRODUCT_ZONE)
                : Clock.fixed(Instant.parse(fixedInstant), PRODUCT_ZONE);
    }
}
