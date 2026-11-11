package com.videogameplatform.api.delivery.analytics;

import com.videogameplatform.api.generated.WebAnalyticsApi;
import com.videogameplatform.api.generated.model.GoogleAnalyticsConfiguration;
import com.videogameplatform.api.generated.model.WebAnalyticsConfiguration;
import java.time.Duration;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publishes the deployment's consent-gated product analytics configuration (#507, UC-022).
 *
 * <p>The value is public deployment configuration, identical for every visitor; a blank value
 * turns analytics off. A malformed value fails startup instead of reaching browsers.
 */
@RestController
@RequestMapping("/api/v1")
public class WebAnalyticsController implements WebAnalyticsApi {

    static final Pattern MEASUREMENT_ID = Pattern.compile("G-[A-Z0-9]{4,20}");
    private static final CacheControl CACHE_CONTROL =
            CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic();

    private final WebAnalyticsConfiguration configuration;

    public WebAnalyticsController(
            @Value("${api.web-analytics.google-analytics-measurement-id:}") String measurementId) {
        this.configuration = configuration(measurementId.strip());
    }

    @Override
    public ResponseEntity<WebAnalyticsConfiguration> getWebAnalyticsConfiguration() {
        return ResponseEntity.ok().cacheControl(CACHE_CONTROL).body(configuration);
    }

    private static WebAnalyticsConfiguration configuration(String measurementId) {
        if (measurementId.isEmpty()) {
            return new WebAnalyticsConfiguration();
        }
        if (!MEASUREMENT_ID.matcher(measurementId).matches()) {
            throw new IllegalStateException(
                    "api.web-analytics.google-analytics-measurement-id must be a GA4"
                            + " measurement ID such as G-ABC123XYZ9 or blank");
        }
        return new WebAnalyticsConfiguration()
                .googleAnalytics(new GoogleAnalyticsConfiguration(measurementId));
    }
}
