package com.videogameplatform.api.delivery;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.ObjectMapper;

/** Bounds synchronous API work after Spring Security and before MVC body conversion. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "api.admission.enabled", havingValue = "true")
public class ApiAdmissionConfiguration {
    @Bean
    ApiAdmissionInterceptor apiAdmissionInterceptor(
            @Value("${api.admission.max-requests:8}") int maxRequests,
            @Value("${api.admission.max-writes:2}") int maxWrites,
            ObjectMapper mapper) {
        return new ApiAdmissionInterceptor(maxRequests, maxWrites, mapper);
    }

    @Bean
    WebMvcConfigurer apiAdmissionWebMvc(ApiAdmissionInterceptor admission) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(admission).addPathPatterns("/api/v1/**");
            }
        };
    }
}
