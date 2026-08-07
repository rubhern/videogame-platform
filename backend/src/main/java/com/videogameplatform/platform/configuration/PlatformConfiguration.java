package com.videogameplatform.platform.configuration;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Technical configuration shared by the executable application boundary. */
@Configuration(proxyBeanMethods = false)
class PlatformConfiguration {

    private static final ZoneId PRODUCT_ZONE = ZoneId.of("Europe/Madrid");

    @Bean
    Clock applicationClock() {
        return Clock.system(PRODUCT_ZONE);
    }
}
