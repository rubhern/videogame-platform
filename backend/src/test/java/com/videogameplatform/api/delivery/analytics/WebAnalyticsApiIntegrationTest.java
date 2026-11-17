package com.videogameplatform.api.delivery.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.videogameplatform.api.delivery.OpenApiResponseContract;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

class WebAnalyticsApiIntegrationTest {

    private static final String PATH = "/api/v1/web-analytics";

    @Nested
    @WebMvcTest(
            value = WebAnalyticsController.class,
            properties = "api.web-analytics.google-analytics-measurement-id=G-ABC123XYZ9")
    @Import({
        IdentitySecurityConfiguration.class,
        AuthenticationProblemEntryPoint.class,
        CsrfProblemAccessDeniedHandler.class,
        RatingResumeAuthenticationSuccessHandler.class,
        RatingIntentAuthenticationFailureHandler.class,
        WebAnalyticsApiIntegrationTest.Infrastructure.class
    })
    class Configured {

        @Autowired MockMvc mockMvc;

        @Test
        void publishesTheMeasurementIdToEveryVisitorAndAdmitsGoogleInThePolicy() throws Exception {
            var result =
                    mockMvc.perform(get(PATH).accept(MediaType.APPLICATION_JSON))
                            .andExpect(status().isOk())
                            .andExpect(header().string("Cache-Control", "max-age=300, public"))
                            .andExpect(header().doesNotExist("Set-Cookie"))
                            .andExpect(
                                    content()
                                            .json(
                                                    "{\"googleAnalytics\":{\"measurementId\":"
                                                            + "\"G-ABC123XYZ9\"}}",
                                                    JsonCompareMode.STRICT))
                            .andReturn();

            OpenApiResponseContract.load("/web-analytics")
                    .assertJsonResponse(result.getResponse(), 200, "WebAnalyticsConfiguration");
            assertThat(result.getResponse().getHeader("Content-Security-Policy"))
                    .contains("script-src 'self' https://*.googletagmanager.com;");
        }
    }

    @Nested
    @WebMvcTest(value = WebAnalyticsController.class)
    @Import({
        IdentitySecurityConfiguration.class,
        AuthenticationProblemEntryPoint.class,
        CsrfProblemAccessDeniedHandler.class,
        RatingResumeAuthenticationSuccessHandler.class,
        RatingIntentAuthenticationFailureHandler.class,
        WebAnalyticsApiIntegrationTest.Infrastructure.class
    })
    class Unconfigured {

        @Autowired MockMvc mockMvc;

        @Test
        void reportsNoAnalyticsAndKeepsTheDocumentPolicyClosed() throws Exception {
            var result =
                    mockMvc.perform(get(PATH).accept(MediaType.APPLICATION_JSON))
                            .andExpect(status().isOk())
                            .andExpect(content().json("{}", JsonCompareMode.STRICT))
                            .andReturn();

            OpenApiResponseContract.load("/web-analytics")
                    .assertJsonResponse(result.getResponse(), 200, "WebAnalyticsConfiguration");
            assertThat(result.getResponse().getHeader("Content-Security-Policy"))
                    .doesNotContain("google");
        }
    }

    @Test
    void refusesToStartWithAMalformedMeasurementId() {
        assertThatThrownBy(() -> new WebAnalyticsController("UA-12345-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GA4 measurement ID");
        assertThatThrownBy(() -> new WebAnalyticsController("G-abc<script>"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void treatsABlankMeasurementIdAsDisabled() {
        assertThat(new WebAnalyticsController("  ").getWebAnalyticsConfiguration().getBody())
                .satisfies(body -> assertThat(body.getGoogleAnalytics()).isNull());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Infrastructure {

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
