package com.videogameplatform.tools.igdb.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.videogameplatform.tools.igdb.model.ActualCase;
import com.videogameplatform.tools.igdb.model.ActualRelease;
import com.videogameplatform.tools.igdb.model.ExecutionStats;
import com.videogameplatform.tools.igdb.sample.ExpectedCase;

class AcceptanceEvaluatorTest {

    @Test
    void calculatesARepeatablePassFromCaseChecks() {
        ExpectedCase expected = new ExpectedCase(
                "CASE-01", "recent_release", "Example Game", "Example Game",
                "main_game", "", "PC", "Worldwide", "2024-06-21", "day",
                "released", "", "blocking", URI.create("https://example.test/evidence"));
        ActualCase actual = new ActualCase(
                "CASE-01", true, 42L, "Example Game", "main_game", "", List.of(),
                List.of(new ActualRelease(
                        "PC (Microsoft Windows)", "Worldwide", "2024-06-21",
                        "day", "released", "Full Release")),
                true, true, true, Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-07-24T00:00:00Z"), "IGDB", 1, 1, "");
        CaseValidation validation = new PocValidator().validate(expected, actual);
        ExecutionStats stats = new ExecutionStats();
        stats.complete();

        PocReport report = new AcceptanceEvaluator().evaluate(
                "sample.csv",
                List.of(validation),
                new AcceptanceContext("validate", 0, 1, 1, 0, stats));

        assertThat(report.decision()).isEqualTo(Decision.PASS);
        assertThat(report.metrics())
                .filteredOn(metric -> "release_accuracy".equals(metric.key()))
                .singleElement()
                .extracting(MetricResult::status)
                .isEqualTo(MetricStatus.PASS);
    }

    @Test
    void calculatesProviderMetadataOnlyForFoundRecords() {
        ExpectedCase foundExpected = expected("CASE-01", "Example Game", "day");
        ActualCase foundActual = actual(
                "CASE-01", true, 42L, "Example Game",
                List.of(new ActualRelease(
                        "PC (Microsoft Windows)", "Worldwide", "2024-06-21",
                        "day", "released", "Full Release")));
        ExpectedCase missingExpected = expected("CASE-02", "Missing Game", "day");
        ActualCase missingActual = ActualCase.notFound(
                "CASE-02", 0, Instant.parse("2026-07-24T00:00:00Z"));
        List<CaseValidation> validations = List.of(
                new PocValidator().validate(foundExpected, foundActual),
                new PocValidator().validate(missingExpected, missingActual));
        ExecutionStats stats = new ExecutionStats();
        stats.complete();

        PocReport report = new AcceptanceEvaluator().evaluate(
                "sample.csv",
                validations,
                new AcceptanceContext("validate", 0, 2, 2, 0, stats));

        assertThat(report.metrics())
                .filteredOn(metric -> "provider_metadata".equals(metric.key()))
                .singleElement()
                .satisfies(metric -> {
                    assertThat(metric.actual()).contains("1/1");
                    assertThat(metric.status()).isEqualTo(MetricStatus.PASS);
                });
    }

    @Test
    void includesUnknownDatePrecisionInReleaseAccuracy() {
        ExpectedCase expected = expected("DATE-01", "Announced Game", "unknown");
        ActualCase actual = actual(
                "DATE-01", true, 42L, "Announced Game",
                List.of(new ActualRelease(
                        "PC (Microsoft Windows)", "Worldwide", "",
                        "unknown", "announced", "")));
        CaseValidation validation = new PocValidator().validate(expected, actual);
        ExecutionStats stats = new ExecutionStats();
        stats.complete();

        PocReport report = new AcceptanceEvaluator().evaluate(
                "sample.csv",
                List.of(validation),
                new AcceptanceContext("validate", 0, 1, 1, 0, stats));

        assertThat(report.metrics())
                .filteredOn(metric -> "release_accuracy".equals(metric.key()))
                .singleElement()
                .satisfies(metric -> {
                    assertThat(metric.actual()).contains("1/1");
                    assertThat(metric.status()).isEqualTo(MetricStatus.PASS);
                });
    }

    private ExpectedCase expected(String caseId, String title, String precision) {
        String date = "unknown".equals(precision) ? "" : "2024-06-21";
        String status = "unknown".equals(precision) ? "announced" : "released";
        return new ExpectedCase(
                caseId, "recent_release", title, title, "main_game", "",
                "PC", "Worldwide", date, precision, status, "", "blocking",
                URI.create("https://example.test/evidence"));
    }

    private ActualCase actual(
            String caseId,
            boolean found,
            Long providerId,
            String title,
            List<ActualRelease> releases) {
        return new ActualCase(
                caseId, found, providerId, title, "main_game", "", List.of(),
                releases, true, true, true, Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-07-24T00:00:00Z"), "IGDB", 1, 1, "");
    }
}
