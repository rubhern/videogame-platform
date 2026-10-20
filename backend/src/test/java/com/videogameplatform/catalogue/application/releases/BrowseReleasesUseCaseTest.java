package com.videogameplatform.catalogue.application.releases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BrowseReleasesUseCaseTest {

    @Test
    void rejectsMissingSort() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new BrowseReleasesUseCase.Query(
                                        null, null, null, false, null, null, 1, 20))
                .withMessage("Release sort is required");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonOneBasedPageNumbers(int pageNumber) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> query(pageNumber, 20))
                .withMessage("Page number must be one-based");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositivePageSizes(int pageSize) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> query(1, pageSize))
                .withMessage("Page size must be between 1 and 100");
    }

    @ParameterizedTest
    @ValueSource(ints = {101, 500})
    void rejectsOversizedPages(int pageSize) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> query(1, pageSize))
                .withMessage("Page size must be between 1 and 100");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 100})
    void acceptsPageSizeBounds(int pageSize) {
        BrowseReleasesUseCase.Query query = query(1, pageSize);

        assertThat(query.pageNumber()).isEqualTo(1);
        assertThat(query.pageSize()).isEqualTo(pageSize);
    }

    @Test
    void carriesAnOptionalRangeAndTheApproximateDateChoiceForTheUseCaseToValidate() {
        BrowseReleasesUseCase.Query query =
                new BrowseReleasesUseCase.Query(
                        LocalDate.of(2026, 8, 1),
                        LocalDate.of(2026, 8, 31),
                        BrowseReleasesUseCase.Sort.HYPE,
                        true,
                        null,
                        null,
                        1,
                        20);

        assertThat(query.from()).isEqualTo("2026-08-01");
        assertThat(query.to()).isEqualTo("2026-08-31");
        assertThat(query.sort()).isEqualTo(BrowseReleasesUseCase.Sort.HYPE);
        assertThat(query.includeApproximateDates()).isTrue();
        assertThat(query.platformIds()).isEmpty();
        assertThat(query(1, 20).from()).isNull();
    }

    private static BrowseReleasesUseCase.Query query(int pageNumber, int pageSize) {
        return new BrowseReleasesUseCase.Query(
                null,
                null,
                BrowseReleasesUseCase.Sort.RELEASE_DATE,
                false,
                null,
                null,
                pageNumber,
                pageSize);
    }
}
