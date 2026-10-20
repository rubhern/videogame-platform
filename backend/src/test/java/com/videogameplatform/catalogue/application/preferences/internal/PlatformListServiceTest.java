package com.videogameplatform.catalogue.application.preferences.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videogameplatform.catalogue.application.CatalogueDataInvalidException;
import com.videogameplatform.catalogue.application.CatalogueNotReadyException;
import com.videogameplatform.catalogue.application.preferences.ListPlatformsUseCase;
import com.videogameplatform.catalogue.application.preferences.ListPlatformsUseCase.PlatformList;
import com.videogameplatform.catalogue.application.preferences.PlatformOption;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PlatformListServiceTest {

    @Test
    void asksForOneMoreThanTheBoundAndReturnsACompleteList() {
        AtomicInteger requested = new AtomicInteger();
        PlatformList list =
                new PlatformList("v1", List.of(new PlatformOption("ps5", "PlayStation 5")));

        var service =
                new PlatformListService(
                        limit -> {
                            requested.set(limit);
                            return Optional.of(list);
                        });

        assertThat(service.list()).isEqualTo(list);
        assertThat(requested).hasValue(ListPlatformsUseCase.MAX_PLATFORMS + 1);
    }

    @Test
    void failsRatherThanTruncatingABoundBreakingTaxonomy() {
        var platforms =
                IntStream.rangeClosed(0, ListPlatformsUseCase.MAX_PLATFORMS)
                        .mapToObj(index -> new PlatformOption("p" + index, "Platform " + index))
                        .toList();
        var service =
                new PlatformListService(limit -> Optional.of(new PlatformList("v1", platforms)));

        assertThatThrownBy(service::list).isInstanceOf(CatalogueDataInvalidException.class);
    }

    @Test
    void anUnpublishedCatalogueIsNotReady() {
        assertThatThrownBy(new PlatformListService(limit -> Optional.empty())::list)
                .isInstanceOf(CatalogueNotReadyException.class);
    }
}
