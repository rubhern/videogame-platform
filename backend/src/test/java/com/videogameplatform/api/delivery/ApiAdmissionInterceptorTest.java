package com.videogameplatform.api.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class ApiAdmissionInterceptorTest {

    private final ApiAdmissionInterceptor filter =
            new ApiAdmissionInterceptor(2, 1, new JsonMapper());

    @Test
    void twoWritesAlsoOccupyTwoOfTheEightSharedSlots() throws Exception {
        var admission = new ApiAdmissionInterceptor(8, 2, new JsonMapper());
        var response = new MockHttpServletResponse();
        var writes = new java.util.ArrayList<MockHttpServletRequest>();
        var reads = new java.util.ArrayList<MockHttpServletRequest>();
        for (int index = 0; index < 2; index++) {
            var write = new MockHttpServletRequest("PUT", "/api/v1/me/ratings/game");
            assertThat(admission.preHandle(write, response, this)).isTrue();
            writes.add(write);
        }
        for (int index = 0; index < 6; index++) {
            var read = new MockHttpServletRequest("GET", "/api/v1/games");
            assertThat(admission.preHandle(read, response, this)).isTrue();
            reads.add(read);
        }
        assertThat(
                        admission.preHandle(
                                new MockHttpServletRequest("GET", "/api/v1/session"),
                                new MockHttpServletResponse(),
                                this))
                .isFalse();

        admission.afterCompletion(reads.getFirst(), response, this, null);
        var thirdWrite = new MockHttpServletRequest("PUT", "/api/v1/me/ratings/other-game");
        var rejected = new MockHttpServletResponse();
        assertThat(admission.preHandle(thirdWrite, rejected, this)).isFalse();
        assertThat(rejected.getStatus()).isEqualTo(503);

        // Total capacity alone is insufficient until one of the two writes also completes.
        admission.afterCompletion(writes.getFirst(), response, this, null);
        assertThat(admission.preHandle(thirdWrite, new MockHttpServletResponse(), this)).isTrue();
        admission.afterCompletion(thirdWrite, response, this, null);
        for (var read : reads) admission.afterCompletion(read, response, this, null);
        for (var write : writes) admission.afterCompletion(write, response, this, null);
    }

    @Test
    void rejectsExcessWritesWithoutQueuingAndKeepsReadCapacityThenRecovers() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var running = executor.submit(() -> request("PUT", blocking(entered, release)));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var denied =
                        request(
                                "PUT",
                                (req, res) -> {
                                    throw new AssertionError("write ran");
                                });
                assertThat(denied.getStatus()).isEqualTo(503);
                OpenApiResponseContract.load("/games").assertJsonResponse(denied, 503, "Problem");
                assertThat(denied.getHeader("Retry-After")).isEqualTo("1");
                assertThat(denied.getHeader("Cache-Control")).isEqualTo("no-store");
                assertThat(denied.getContentType()).startsWith("application/problem+json");
                var problem = new JsonMapper().readTree(denied.getContentAsString());
                assertThat(problem.get("code").asText()).isEqualTo("SERVICE_OVERLOADED");
                assertThat(problem.get("correlationId").asText())
                        .isEqualTo(denied.getHeader("X-Correlation-ID"));
                assertThat(request("GET", (req, res) -> {}).getStatus()).isEqualTo(200);
            } finally {
                release.countDown();
            }
            assertThat(running.get(5, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
        }
        assertThat(request("PUT", (req, res) -> {}).getStatus()).isEqualTo(200);
    }

    @Test
    void totalSaturationIgnoresForgedIpHeadersAndReleasesUnusedWritePermit() throws Exception {
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> request("GET", blocking(entered, release)));
            var second = executor.submit(() -> request("GET", blocking(entered, release)));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                for (String method : new String[] {"GET", "PUT"}) {
                    assertThat(
                                    request(
                                                    method,
                                                    (req, res) -> {
                                                        throw new AssertionError("work ran");
                                                    })
                                            .getStatus())
                            .isEqualTo(503);
                }
            } finally {
                release.countDown();
            }
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        }
        assertThat(request("PUT", (req, res) -> {}).getStatus()).isEqualTo(200);
    }

    @Test
    void handlerFailureDoesNotLeakEitherPermit() throws Exception {
        assertThatThrownBy(
                        () ->
                                request(
                                        "PUT",
                                        (req, res) -> {
                                            throw new ServletException("synthetic");
                                        }))
                .isInstanceOf(ServletException.class);
        assertThat(request("PUT", (req, res) -> {}).getStatus()).isEqualTo(200);
    }

    @Test
    void refusesInvalidConfiguration() {
        assertThatThrownBy(() -> new ApiAdmissionInterceptor(0, 1, new JsonMapper()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApiAdmissionInterceptor(2, 2, new JsonMapper()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private MockHttpServletResponse request(String method, FilterChain chain) throws Exception {
        var request = new MockHttpServletRequest(method, "/api/v1/games");
        request.addHeader("X-Forwarded-For", "192.0.2.44");
        request.addHeader("Forwarded", "for=192.0.2.45");
        var response = new MockHttpServletResponse();
        if (filter.preHandle(request, response, this)) {
            try {
                chain.doFilter(request, response);
            } finally {
                filter.afterCompletion(request, response, this, null);
            }
        }
        return response;
    }

    private static FilterChain blocking(CountDownLatch entered, CountDownLatch release) {
        return (req, res) -> {
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new ServletException("test did not release work");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new ServletException(exception);
            }
        };
    }
}
