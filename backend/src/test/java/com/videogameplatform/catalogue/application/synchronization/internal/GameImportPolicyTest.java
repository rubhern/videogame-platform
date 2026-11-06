package com.videogameplatform.catalogue.application.synchronization.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderWork;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderWorkType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The line between a work the product models and one it defers (CAT-002, CAT-006). */
class GameImportPolicyTest {

    @ParameterizedTest
    @EnumSource(
            value = ProviderWorkType.class,
            names = {
                "MAIN_GAME",
                "REMAKE",
                "REMASTER",
                "STANDALONE_EXPANSION",
                "PORT",
                "EXPANDED_GAME"
            })
    void importsWorksTheProductTreatsAsIndependent(ProviderWorkType type) {
        assertThat(GameImportPolicy.evaluate(work(type, "Fixture Work")))
                .isEqualTo(GameImportPolicy.Decision.ACCEPTED);
    }

    /** These depend on a parent work, are packaging or are not first-party works (#449). */
    @ParameterizedTest
    @EnumSource(
            value = ProviderWorkType.class,
            names = {
                "ADD_ON",
                "EXPANSION",
                "EPISODE",
                "SEASON",
                "BUNDLE",
                "PACK",
                "UPDATE",
                "MOD",
                "FORK",
                "UNKNOWN"
            })
    void defersEverythingElseWithoutFailing(ProviderWorkType type) {
        assertThat(GameImportPolicy.evaluate(work(type, "Fixture Work")))
                .isEqualTo(GameImportPolicy.Decision.DEFERRED_UNSUPPORTED_TYPE);
    }

    @Test
    void defersAWorkWhoseTitleCannotBecomeCatalogueContent() {
        assertThat(GameImportPolicy.evaluate(work(ProviderWorkType.MAIN_GAME, "   ")))
                .isEqualTo(GameImportPolicy.Decision.DEFERRED_UNUSABLE_TITLE);
        assertThat(GameImportPolicy.evaluate(work(ProviderWorkType.MAIN_GAME, null)))
                .isEqualTo(GameImportPolicy.Decision.DEFERRED_UNUSABLE_TITLE);
    }

    @Test
    void keepsTheImportableSetSmallAndExplicit() {
        assertThat(GameImportPolicy.importableTypes())
                .containsExactlyInAnyOrder(
                        ProviderWorkType.MAIN_GAME,
                        ProviderWorkType.REMAKE,
                        ProviderWorkType.REMASTER,
                        ProviderWorkType.STANDALONE_EXPANSION,
                        ProviderWorkType.PORT,
                        ProviderWorkType.EXPANDED_GAME);
    }

    private static ProviderWork work(ProviderWorkType type, String title) {
        return new ProviderWork(
                "1",
                title,
                type,
                Instant.EPOCH,
                Optional.empty(),
                List.of(),
                Optional.empty(),
                List.of(),
                List.of());
    }
}
