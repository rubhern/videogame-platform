package com.videogameplatform.api.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;

class ApiAdmissionConfigurationTest {
    private static final String PATH = "/api/v1/admission-probe";
    private final Probe probe = new Probe();
    private final WebApplicationContextRunner runner =
            new WebApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withUserConfiguration(ApiAdmissionConfiguration.class, MvcConfiguration.class)
                    .withBean(ObjectMapper.class, ObjectMapper::new)
                    .withBean(Probe.class, () -> probe);

    @Test
    void executableDefaultAdmitsMoreThanEightRequestsAndTwoWrites() {
        runner.run(
                context -> {
                    assertThat(context).doesNotHaveBean(ApiAdmissionInterceptor.class);
                    assertAllRequestsEnter(MockMvcBuilders.webAppContextSetup(context).build());
                });
    }

    @Test
    void explicitEnvironmentDisableDoesNotInstallAdmissionEvenWithSmallBudgets() {
        runner.withPropertyValues(
                        "APPLICATION_API_ADMISSION_ENABLED=false",
                        "APPLICATION_API_MAX_REQUESTS=2",
                        "APPLICATION_API_MAX_WRITES=1")
                .run(
                        context -> {
                            assertThat(context).doesNotHaveBean(ApiAdmissionInterceptor.class);
                            assertAllRequestsEnter(
                                    MockMvcBuilders.webAppContextSetup(context).build());
                        });
    }

    @Test
    void environmentOptInUsesExperimentalDefaultsAndRecoversAfterSaturation() {
        assertEnabledBudget(8, 2, new String[] {"APPLICATION_API_ADMISSION_ENABLED=true"});
    }

    @Test
    void environmentOverridesApplyWithoutChangingTheCompiledConfiguration() {
        assertEnabledBudget(
                3,
                1,
                new String[] {
                    "APPLICATION_API_ADMISSION_ENABLED=true",
                    "APPLICATION_API_MAX_REQUESTS=3",
                    "APPLICATION_API_MAX_WRITES=1"
                });
    }

    private void assertEnabledBudget(int total, int maxWrites, String[] properties) {
        runner.withPropertyValues(properties)
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(ApiAdmissionInterceptor.class);
                            var admission = context.getBean(ApiAdmissionInterceptor.class);
                            var mvc = MockMvcBuilders.webAppContextSetup(context).build();
                            var held = new ArrayList<MockHttpServletRequest>();
                            try {
                                for (int index = 0; index < total; index++) {
                                    var request = new MockHttpServletRequest("GET", PATH);
                                    assertThat(
                                                    admission.preHandle(
                                                            request,
                                                            new MockHttpServletResponse(),
                                                            this))
                                            .isTrue();
                                    held.add(request);
                                }
                                mvc.perform(get(PATH))
                                        .andExpect(status().isServiceUnavailable())
                                        .andExpect(jsonPath("$.code").value("SERVICE_OVERLOADED"))
                                        .andExpect(header().string("Retry-After", "1"))
                                        .andExpect(header().string("Cache-Control", "no-store"));
                                assertThat(probe.entered.getCount()).isEqualTo(13);
                            } finally {
                                for (var request : held)
                                    admission.afterCompletion(
                                            request, new MockHttpServletResponse(), this, null);
                            }
                            probe.release.countDown();
                            mvc.perform(get(PATH)).andExpect(status().isNoContent());
                            var heldWrites = new ArrayList<MockHttpServletRequest>();
                            try {
                                for (int index = 0; index < maxWrites; index++) {
                                    var request = new MockHttpServletRequest("PUT", PATH);
                                    assertThat(
                                                    admission.preHandle(
                                                            request,
                                                            new MockHttpServletResponse(),
                                                            this))
                                            .isTrue();
                                    heldWrites.add(request);
                                }
                                mvc.perform(put(PATH))
                                        .andExpect(status().isServiceUnavailable())
                                        .andExpect(jsonPath("$.code").value("SERVICE_OVERLOADED"))
                                        .andExpect(header().string("Retry-After", "1"));
                                mvc.perform(get(PATH)).andExpect(status().isNoContent());
                            } finally {
                                for (var request : heldWrites)
                                    admission.afterCompletion(
                                            request, new MockHttpServletResponse(), this, null);
                            }
                            mvc.perform(put(PATH)).andExpect(status().isNoContent());
                        });
    }

    private void assertAllRequestsEnter(MockMvc mvc) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var requests = new ArrayList<Future<Integer>>();
            for (int index = 0; index < 13; index++) {
                final boolean write = index < 4;
                requests.add(
                        executor.submit(
                                () ->
                                        mvc.perform(write ? put(PATH) : get(PATH))
                                                .andReturn()
                                                .getResponse()
                                                .getStatus()));
            }
            try {
                assertThat(probe.entered.await(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                probe.release.countDown();
            }
            for (var request : requests)
                assertThat(request.get(5, TimeUnit.SECONDS)).isEqualTo(204);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    static class MvcConfiguration {}

    @RestController
    static class Probe {
        final CountDownLatch entered = new CountDownLatch(13);
        final CountDownLatch release = new CountDownLatch(1);

        @RequestMapping(PATH)
        ResponseEntity<Void> request() throws InterruptedException {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS))
                throw new IllegalStateException("Test did not release work");
            return ResponseEntity.noContent().build();
        }
    }
}
