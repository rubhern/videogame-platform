package com.videogameplatform.identity.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ContentSecurityPolicyTest {

    @Test
    void withoutAnalyticsTheDocumentAdmitsOnlyItsOwnScriptsAndConnections() {
        assertThat(IdentitySecurityConfiguration.contentSecurityPolicy(false))
                .isEqualTo(
                        "default-src 'self'; base-uri 'self'; object-src 'none'; "
                                + "frame-ancestors 'none'; form-action 'self'; "
                                + "script-src 'self'; style-src 'self'; "
                                + "img-src 'self' data: https://images.igdb.com; "
                                + "connect-src 'self'")
                .doesNotContain("google");
    }

    @Test
    void aConfiguredMeasurementIdAdmitsOnlyGoogleTagAndCollectionEndpoints() {
        String policy = IdentitySecurityConfiguration.contentSecurityPolicy(true);

        assertThat(policy)
                .contains("script-src 'self' https://*.googletagmanager.com;")
                .contains(
                        "img-src 'self' data: https://images.igdb.com"
                                + " https://*.google-analytics.com")
                .endsWith(
                        "connect-src 'self' https://*.google-analytics.com"
                                + " https://*.analytics.google.com https://*.googletagmanager.com")
                .contains("style-src 'self';")
                .contains("frame-ancestors 'none'")
                .doesNotContain("unsafe-inline")
                .doesNotContain("unsafe-eval");
    }
}
