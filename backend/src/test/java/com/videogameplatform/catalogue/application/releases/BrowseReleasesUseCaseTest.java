package com.videogameplatform.catalogue.application.releases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BrowseReleasesUseCaseTest {

    @Test
    void rejectsMissingView() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> query(null, 1, 20))
                .withMessage("Release view is required");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonOneBasedPageNumbers(int pageNumber) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> query(BrowseReleasesUseCase.View.RECENT, pageNumber, 20))
                .withMessage("Page number must be one-based");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositivePageSizes(int pageSize) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> query(BrowseReleasesUseCase.View.RECENT, 1, pageSize))
                .withMessage("Page size must be between 1 and 100");
    }

    @ParameterizedTest
    @ValueSource(ints = {101, 500})
    void rejectsOversizedPages(int pageSize) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> query(BrowseReleasesUseCase.View.RECENT, 1, pageSize))
                .withMessage("Page size must be between 1 and 100");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 100})
    void acceptsPageSizeBounds(int pageSize) {
        BrowseReleasesUseCase.Query query = query(BrowseReleasesUseCase.View.UPCOMING, 1, pageSize);

        assertThat(query.view()).isEqualTo(BrowseReleasesUseCase.View.UPCOMING);
        assertThat(query.pageNumber()).isEqualTo(1);
        assertThat(query.pageSize()).isEqualTo(pageSize);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 3, 5, 52})
    void rejectsUnsupportedWeekHorizons(int weeks) {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new BrowseReleasesUseCase.Query(
                                        BrowseReleasesUseCase.View.RECENT,
                                        weeks,
                                        null,
                                        null,
                                        1,
                                        20))
                .withMessage("Release window must be 1, 2, or 4 weeks");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 4})
    void acceptsFixedWeekHorizons(int weeks) {
        assertThat(
                        new BrowseReleasesUseCase.Query(
                                        BrowseReleasesUseCase.View.UPCOMING,
                                        weeks,
                                        null,
                                        null,
                                        1,
                                        20)
                                .weeks())
                .isEqualTo(weeks);
    }

    private static BrowseReleasesUseCase.Query query(
            BrowseReleasesUseCase.View view, int pageNumber, int pageSize) {
        return new BrowseReleasesUseCase.Query(view, 1, null, null, pageNumber, pageSize);
    }
}
