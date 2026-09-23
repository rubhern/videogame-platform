package com.videogameplatform.platform.observability;

import io.micrometer.common.KeyValues;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.http.server.observation.ServerRequestObservationConvention;

/** Configures bounded, privacy-safe HTTP observations. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReadinessHealthProperties.class)
class ObservabilityConfiguration {

    @Bean
    ServerRequestObservationConvention safeServerRequestObservationConvention() {
        return new DefaultServerRequestObservationConvention() {
            @Override
            public KeyValues getHighCardinalityKeyValues(ServerRequestObservationContext context) {
                return KeyValues.empty();
            }
        };
    }
}
