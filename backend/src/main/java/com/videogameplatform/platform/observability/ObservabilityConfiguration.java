package com.videogameplatform.platform.observability;

import io.micrometer.common.KeyValues;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.client.observation.ClientRequestObservationConvention;
import org.springframework.http.client.observation.DefaultClientRequestObservationConvention;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.http.server.observation.ServerRequestObservationConvention;

/**
 * Configures bounded, privacy-safe HTTP observations. Their high-cardinality values become span
 * attributes in sampled traces (ADR-0046), and the full URL carries query strings such as search
 * terms, so only the bounded route template, method, status and outcome remain.
 */
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

    @Bean
    ClientRequestObservationConvention safeClientRequestObservationConvention() {
        return new DefaultClientRequestObservationConvention() {
            @Override
            public KeyValues getHighCardinalityKeyValues(ClientRequestObservationContext context) {
                return KeyValues.empty();
            }
        };
    }
}
