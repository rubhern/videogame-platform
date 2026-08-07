package com.videogameplatform.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.VideoGamePlatformApplication;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTest {

    private final ApplicationModules modules =
            ApplicationModules.of(VideoGamePlatformApplication.class);

    @Test
    void verifiesModuleDependencies() {
        modules.verify();
    }

    @Test
    void exposesOnlyTheApprovedInitialModules() {
        Set<String> moduleNames =
                modules.stream()
                        .map(module -> module.getIdentifier().toString())
                        .collect(Collectors.toUnmodifiableSet());

        assertThat(moduleNames)
                .containsExactlyInAnyOrder("catalogue", "ratings", "identity", "api", "platform");
    }
}
