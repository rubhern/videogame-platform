package com.videogameplatform.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.common.KeyValue;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** ADR-0046: HTTP observations never carry the full URL, which holds query strings. */
class ObservabilityConfigurationTest {
    private static final String TERM = "privacymarker";
    private final ObservabilityConfiguration configuration = new ObservabilityConfiguration();

    @Test
    void serverObservationsKeepNoFullUrl() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/games");
        request.setQueryString("q=" + TERM);
        ServerRequestObservationContext context =
                new ServerRequestObservationContext(request, new MockHttpServletResponse());

        var convention = configuration.safeServerRequestObservationConvention();

        assertThat(convention.getHighCardinalityKeyValues(context)).isEmpty();
        assertThat(convention.getLowCardinalityKeyValues(context))
                .map(KeyValue::getValue)
                .noneMatch(value -> value.contains(TERM));
    }

    @Test
    void clientObservationsKeepNoFullUrl() {
        MockClientHttpRequest request =
                new MockClientHttpRequest(
                        HttpMethod.GET, URI.create("https://provider.test/search?q=" + TERM));
        ClientRequestObservationContext context = new ClientRequestObservationContext(request);
        context.setCarrier(request);

        var convention = configuration.safeClientRequestObservationConvention();

        assertThat(convention.getHighCardinalityKeyValues(context)).isEmpty();
        assertThat(convention.getLowCardinalityKeyValues(context))
                .map(KeyValue::getValue)
                .noneMatch(value -> value.contains(TERM));
    }
}
