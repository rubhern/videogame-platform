package com.videogameplatform.catalogue.application.preferences.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videogameplatform.catalogue.application.preferences.PlatformOption;
import com.videogameplatform.catalogue.application.preferences.PlatformPreferenceInvalidException;
import com.videogameplatform.catalogue.application.preferences.PlatformPreferences;
import com.videogameplatform.catalogue.application.preferences.port.PlatformPreferenceStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PlatformPreferencesServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");
    private static final Map<String, String> TAXONOMY =
            Map.of("ps5", "PlayStation 5", "switch-2", "Nintendo Switch 2");

    private final FakeStore store = new FakeStore();
    private final PlatformPreferencesService service =
            new PlatformPreferencesService(store, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void readsOnlyTheCallersOwnChoice() {
        service.replace("alice", List.of("ps5"));

        assertThat(service.get("alice").platforms())
                .containsExactly(new PlatformOption("ps5", "PlayStation 5"));
        assertThat(service.get("bob")).isEqualTo(PlatformPreferences.notConfigured());
    }

    @Test
    void trimsAndDeduplicatesBeforeReplacingAtTheTrustedTime() {
        service.replace("alice", List.of(" ps5", "switch-2", "ps5 "));

        assertThat(store.lastSet).containsExactlyInAnyOrder("ps5", "switch-2");
        assertThat(store.lastSavedAt).isEqualTo(NOW);
    }

    @Test
    void anEmptyChoiceIsAConfiguredSkip() {
        assertThat(service.replace("alice", List.of()))
                .isEqualTo(new PlatformPreferences(true, List.of()));
    }

    @Test
    void rejectsUnknownBlankOrTooManyPlatformsWithoutChangingTheChoice() {
        service.replace("alice", List.of("ps5"));
        String[] tooMany =
                IntStream.range(0, 51).mapToObj(index -> "p" + index).toArray(String[]::new);

        for (List<String> invalid :
                List.of(List.of("ps5", "unknown"), List.of(" "), Arrays.asList(tooMany))) {
            assertThatThrownBy(() -> service.replace("alice", invalid))
                    .isInstanceOf(PlatformPreferenceInvalidException.class);
        }
        assertThat(service.get("alice").platforms())
                .extracting(PlatformOption::platformId)
                .containsExactly("ps5");
    }

    @Test
    void requiresTheServerDerivedUser() {
        assertThatThrownBy(() -> service.get(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.replace(null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** An in-memory store with the port's all-or-nothing replacement semantics. */
    private static final class FakeStore implements PlatformPreferenceStore {
        private final Map<String, PlatformPreferences> choices = new HashMap<>();
        private Set<String> lastSet;
        private Instant lastSavedAt;

        @Override
        public PlatformPreferences find(String userId) {
            return choices.getOrDefault(userId, PlatformPreferences.notConfigured());
        }

        @Override
        public Optional<PlatformPreferences> replace(
                String userId, Set<String> platformIds, Instant savedAt) {
            lastSet = platformIds;
            lastSavedAt = savedAt;
            if (!TAXONOMY.keySet().containsAll(platformIds)) {
                return Optional.empty();
            }
            PlatformPreferences stored =
                    new PlatformPreferences(
                            true,
                            platformIds.stream()
                                    .map(id -> new PlatformOption(id, TAXONOMY.get(id)))
                                    .sorted(java.util.Comparator.comparing(PlatformOption::name))
                                    .toList());
            choices.put(userId, stored);
            return Optional.of(stored);
        }
    }
}
