package com.videogameplatform.platform.configuration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Security boundary for the operator-facing management endpoints.
 *
 * <p>Actuator runs on its own port, which the platform design binds to loopback locally and to the
 * private container network otherwise. It carries no cookie-authenticated session, so the product
 * BFF's CSRF token would be ceremony an operator could not satisfy with a plain HTTP client. What
 * must not happen is a browser being tricked into invoking an operator command against a local
 * port, so a cross-site browser request is rejected outright instead.
 */
@Configuration(proxyBeanMethods = false)
class ManagementEndpointSecurityConfiguration {

    @Bean
    @Order(0)
    SecurityFilterChain managementEndpointSecurity(HttpSecurity http) {
        http.securityMatcher(EndpointRequest.toAnyEndpoint())
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .requestCache(cache -> cache.disable())
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.ignoringRequestMatchers(EndpointRequest.toAnyEndpoint()))
                .addFilterBefore(
                        new CrossSiteManagementRequestFilter(), SecurityContextHolderFilter.class)
                .headers(headers -> headers.frameOptions(frame -> frame.deny()));
        return http.build();
    }

    /** Rejects any state-changing management request a browser initiated from another site. */
    private static final class CrossSiteManagementRequestFilter extends OncePerRequestFilter {

        private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
        private static final Set<String> SAFE_FETCH_SITES = Set.of("same-origin", "none");

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return SAFE_METHODS.contains(request.getMethod().toUpperCase(Locale.ROOT));
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
                throws ServletException, IOException {
            String fetchSite = request.getHeader("Sec-Fetch-Site");
            boolean browserInitiated =
                    request.getHeader(HttpHeaders.ORIGIN) != null
                            || (fetchSite != null
                                    && !SAFE_FETCH_SITES.contains(
                                            fetchSite.toLowerCase(Locale.ROOT)));
            if (browserInitiated) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setHeader("Cache-Control", "no-store");
                return;
            }
            filterChain.doFilter(request, response);
        }
    }
}
