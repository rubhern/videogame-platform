package com.videogameplatform.api.delivery.frontend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.videogameplatform.catalogue.application.details.ListIndexableGamesUseCase;
import com.videogameplatform.identity.adapter.session.RatingReturnContextStore;
import com.videogameplatform.identity.configuration.AuthenticationProblemEntryPoint;
import com.videogameplatform.identity.configuration.CsrfProblemAccessDeniedHandler;
import com.videogameplatform.identity.configuration.IdentitySecurityConfiguration;
import com.videogameplatform.identity.configuration.RatingIntentAuthenticationFailureHandler;
import com.videogameplatform.identity.configuration.RatingResumeAuthenticationSuccessHandler;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** Anonymous crawlers reach robots and sitemaps through the real filter chain, uncookied. */
@WebMvcTest(
        value = SearchIndexingController.class,
        properties = "api.search-indexing.public-origin=https://gameometro.com")
@Import({
    IdentitySecurityConfiguration.class,
    AuthenticationProblemEntryPoint.class,
    CsrfProblemAccessDeniedHandler.class,
    RatingResumeAuthenticationSuccessHandler.class,
    RatingIntentAuthenticationFailureHandler.class,
    SearchIndexingSecurityIntegrationTest.Infrastructure.class
})
class SearchIndexingSecurityIntegrationTest {

    @Autowired MockMvc mockMvc;

    @Test
    void anonymousCrawlersReadRobotsAndSitemapsWithTheirOwnCachePolicy() throws Exception {
        for (var path : List.of("/robots.txt", "/sitemap.xml", "/sitemaps/pages.xml")) {
            mockMvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "max-age=3600, public"))
                    .andExpect(header().doesNotExist("Set-Cookie"));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Infrastructure {

        @Bean
        ListIndexableGamesUseCase indexableGames() {
            var games = Mockito.mock(ListIndexableGamesUseCase.class);
            Mockito.when(games.partitionStarts(Mockito.anyInt())).thenReturn(List.of());
            return games;
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        RatingReturnContextStore ratingReturnContextStore(HttpServletRequest request, Clock clock) {
            return new RatingReturnContextStore(request, clock, Duration.ofMinutes(10));
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
