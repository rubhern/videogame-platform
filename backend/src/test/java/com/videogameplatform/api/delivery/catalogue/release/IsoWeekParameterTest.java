package com.videogameplatform.api.delivery.catalogue.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class IsoWeekParameterTest {

    @Test
    void namesWeeksByTheirWeekBasedYearAcrossCalendarYears() {
        // 2026 has 53 weeks: its last one ends in January 2027, its first starts in December 2025.
        assertThat(IsoWeekParameter.monday("2026-W53")).contains(LocalDate.parse("2026-12-28"));
        assertThat(IsoWeekParameter.monday("2027-W01")).contains(LocalDate.parse("2027-01-04"));
        assertThat(IsoWeekParameter.monday("2026-W01")).contains(LocalDate.parse("2025-12-29"));
        assertThat(IsoWeekParameter.monday("2026-W42")).contains(LocalDate.parse("2026-10-12"));
        assertThat(IsoWeekParameter.monday("2020-W53")).contains(LocalDate.parse("2020-12-28"));
        assertThat(IsoWeekParameter.monday("0001-W01")).contains(LocalDate.parse("0001-01-01"));

        assertThat(IsoWeekParameter.format(LocalDate.parse("2026-12-28"))).isEqualTo("2026-W53");
        assertThat(IsoWeekParameter.format(LocalDate.parse("2027-01-03"))).isEqualTo("2026-W53");
        assertThat(IsoWeekParameter.format(LocalDate.parse("2025-12-29"))).isEqualTo("2026-W01");
        assertThat(IsoWeekParameter.format(LocalDate.parse("2027-01-04"))).isEqualTo("2027-W01");
    }

    @Test
    void aYearWithFiftyTwoWeeksHasNoWeekFiftyThree() {
        assertThat(IsoWeekParameter.monday("2025-W53")).isEmpty();
        assertThat(IsoWeekParameter.monday("2027-W53")).isEmpty();
        assertThat(IsoWeekParameter.monday("2026-W00")).isEmpty();
        assertThat(IsoWeekParameter.monday("2025-W52")).contains(LocalDate.parse("2025-12-22"));
    }
}
