package com.videogameplatform.tools.igdb.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.videogameplatform.tools.igdb.model.ActualCase;
import com.videogameplatform.tools.igdb.model.ActualRelease;
import com.videogameplatform.tools.igdb.sample.ExpectedCase;

class PocValidatorTest {

    private final PocValidator validator = new PocValidator();

    @Test
    void doesNotCombinePlatformRegionAndDateFromDifferentReleases() {
        ExpectedCase expected = expected("recent_release", "2026-08-27", "released");
        ActualCase actual = actual(List.of(
                new ActualRelease(
                        "PlayStation 5", "Worldwide", "2026-09-15", "day",
                        "released", "Full Release"),
                new ActualRelease(
                        "Xbox Series X|S", "Worldwide", "2026-08-27", "day",
                        "released", "Full Release")));

        CaseValidation result = validator.validate(expected, actual);

        assertThat(check(result, "platform").outcome()).isEqualTo(CheckOutcome.PASS);
        assertThat(check(result, "region").outcome()).isEqualTo(CheckOutcome.PASS);
        assertThat(check(result, "release_date").outcome()).isEqualTo(CheckOutcome.FAIL);
        assertThat(check(result, "status").outcome()).isEqualTo(CheckOutcome.PASS);
        assertThat(result.outcome()).isEqualTo(CaseOutcome.FAIL);
    }

    @Test
    void sendsChangedFutureDatesToManualReview() {
        ExpectedCase expected = expected("upcoming_release", "2027-08-27", "announced");
        ActualCase actual = actual(List.of(new ActualRelease(
                "PlayStation 5", "Worldwide", "2027-09-15", "day",
                "announced", "")));

        CaseValidation result = validator.validate(expected, actual);

        assertThat(check(result, "release_date").outcome()).isEqualTo(CheckOutcome.REVIEW);
        assertThat(result.outcome()).isEqualTo(CaseOutcome.REVIEW);
    }

    @Test
    void sendsExplicitProviderTaxonomyDifferencesToReview() {
        ExpectedCase expected = new ExpectedCase(
                "DATE-05", "delayed_imprecise", "Example Trilogy",
                "Example Trilogy", "remaster", "", "Xbox Series X|S",
                "Worldwide", "2027", "year", "announced", "", "blocking",
                URI.create("https://example.test/evidence"));
        ActualCase actual = new ActualCase(
                "DATE-05", true, 42L, "Example Trilogy", "bundle", "", List.of(),
                List.of(new ActualRelease(
                        "Xbox Series X|S", "Worldwide", "2027", "year",
                        "announced", "Full Release")),
                true, true, true, Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-07-24T00:00:00Z"), "IGDB", 1, 1, "");

        CaseValidation result = validator.validate(expected, actual);

        assertThat(check(result, "type").outcome()).isEqualTo(CheckOutcome.REVIEW);
        assertThat(result.outcome()).isEqualTo(CaseOutcome.REVIEW);
    }

    @Test
    void keepsSilentMergesAsBlockingFailures() {
        ExpectedCase expected = new ExpectedCase(
                "DLC-01", "dlc_expansion", "Example Expansion",
                "Example Expansion", "expansion", "Example Game", "PC",
                "Worldwide", "2024-01-01", "day", "released", "", "blocking",
                URI.create("https://example.test/evidence"));
        ActualCase actual = new ActualCase(
                "DLC-01", true, 42L, "Example Expansion", "main_game",
                "Example Game", List.of(),
                List.of(new ActualRelease(
                        "PC (Microsoft Windows)", "Worldwide", "2024-01-01",
                        "day", "released", "Full Release")),
                true, true, true, Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-07-24T00:00:00Z"), "IGDB", 1, 1, "");

        CaseValidation result = validator.validate(expected, actual);

        assertThat(check(result, "type").outcome()).isEqualTo(CheckOutcome.FAIL);
        assertThat(result.outcome()).isEqualTo(CaseOutcome.FAIL);
    }

    @Test
    void treatsResolvedExactTitleDuplicatesAsObservational() {
        ExpectedCase expected = expected("recent_release", "2026-08-27", "released");
        ActualCase actual = new ActualCase(
                "CASE-01", true, 42L, "Example Game", "main_game", "", List.of(),
                List.of(new ActualRelease(
                        "PlayStation 5", "Worldwide", "2026-08-27", "day",
                        "released", "Full Release")),
                true, true, true, Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-07-24T00:00:00Z"), "IGDB", 4, 3, "");

        CaseValidation result = validator.validate(expected, actual);

        assertThat(check(result, "duplicates").outcome()).isEqualTo(CheckOutcome.PASS);
        assertThat(check(result, "duplicates").actual()).contains("3");
    }

    private ExpectedCase expected(String category, String date, String status) {
        return new ExpectedCase(
                "CASE-01", category, "Example Game", "Example Game", "main_game", "",
                "PlayStation 5", "Worldwide", date, "day", status, "", "blocking",
                URI.create("https://example.test/evidence"));
    }

    private ActualCase actual(List<ActualRelease> releases) {
        return new ActualCase(
                "CASE-01", true, 42L, "Example Game", "main_game", "", List.of(),
                releases, true, true, true, Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-07-24T00:00:00Z"), "IGDB", 1, 1, "");
    }

    private FieldCheck check(CaseValidation validation, String field) {
        return validation.checks().stream()
                .filter(check -> field.equals(check.field()))
                .findFirst()
                .orElseThrow();
    }
}
