package com.videogameplatform.platform.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PlatformConfigurationTest {

    private static final ZoneId PRODUCT_ZONE = ZoneId.of("Europe/Madrid");
    private static final Instant BROWSER_TEST_INSTANT = Instant.parse("2026-08-13T10:00:00Z");

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(PlatformConfiguration.class);

    @Test
    void usesTheRealProductClockByDefault() {
        contextRunner.run(
                context -> {
                    Clock clock = context.getBean(Clock.class);
                    Instant before = Instant.now();
                    Instant current = clock.instant();
                    Instant after = Instant.now();

                    assertThat(context).hasSingleBean(Clock.class);
                    assertThat(clock.getZone()).isEqualTo(PRODUCT_ZONE);
                    assertThat(current).isBetween(before, after);
                });
    }

    @Test
    void usesTheConfiguredFixedInstantWhenProvided() {
        contextRunner
                .withPropertyValues("platform.clock.fixed-instant=" + BROWSER_TEST_INSTANT)
                .run(
                        context -> {
                            Clock clock = context.getBean(Clock.class);

                            assertThat(context).hasSingleBean(Clock.class);
                            assertThat(clock.getZone()).isEqualTo(PRODUCT_ZONE);
                            assertThat(clock.instant()).isEqualTo(BROWSER_TEST_INSTANT);
                        });
    }
}
