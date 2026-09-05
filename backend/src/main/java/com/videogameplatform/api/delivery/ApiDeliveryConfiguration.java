package com.videogameplatform.api.delivery;

import com.videogameplatform.api.delivery.catalogue.release.ReleaseHttpProperties;
import com.videogameplatform.api.delivery.catalogue.search.GameSearchHttpProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Shared HTTP delivery conventions. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ReleaseHttpProperties.class, GameSearchHttpProperties.class})
class ApiDeliveryConfiguration implements WebMvcConfigurer {

    private final StrictQueryParameterInterceptor strictQueryParameters;

    ApiDeliveryConfiguration(StrictQueryParameterInterceptor strictQueryParameters) {
        this.strictQueryParameters = strictQueryParameters;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(strictQueryParameters).addPathPatterns("/api/v1/**");
    }
}
