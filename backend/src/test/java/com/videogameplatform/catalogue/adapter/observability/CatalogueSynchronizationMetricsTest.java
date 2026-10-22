package com.videogameplatform.catalogue.adapter.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationReport;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationReport.Counters;
import com.videogameplatform.catalogue.application.synchronization.SynchronizationOutcome;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderMappingFailure;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The player-score run records and the Grafana queries that read them must name the same series:
 * a renamed kind would silently empty a panel rather than fail.
 */
class CatalogueSynchronizationMetricsTest {

    private static final Path DASHBOARD =
            Path.of("../deploy/private-dev/grafana/dashboards/vgp-synchronization.json");

    @Test
    void recordsEachPlayerScoreCounterOfARunUnderItsOwnBoundedKind() {
        var registry = new SimpleMeterRegistry();

        new CatalogueSynchronizationMetrics(registry).recordRun(report());

        assertThat(playerScoreRecords(registry))
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of(
                                "player_score_observed_games", 31.0,
                                "player_score_absent_games", 32.0,
                                "player_score_unavailable_games", 33.0,
                                "player_score_created_games", 34.0,
                                "player_score_updated_games", 35.0,
                                "player_score_removed_games", 36.0,
                                "player_score_unchanged_games", 37.0));
    }

    @Test
    void everyAssessmentIsPreregisteredAndEachFinishedRunCountsUnderItsOwn() {
        var registry = new SimpleMeterRegistry();
        var metrics = new CatalogueSynchronizationMetrics(registry);

        // A completed interval whose only failure was a known data-quality failure.
        metrics.recordRun(
                report(
                        SynchronizationOutcome.PARTIAL,
                        "SYNCHRONIZATION_COMPLETED_WITH_FAILURES",
                        new Counters(
                                0, 5, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                                0, 0, 0, 0, 0, 1)));
        metrics.recordRun(report(SynchronizationOutcome.FAILED, "SYNCHRONIZATION_FAILED", none()));
        metrics.recordRun(
                report(SynchronizationOutcome.PARTIAL, "SYNCHRONIZATION_STOPPED", none()));

        assertThat(
                        registry
                                .find(CatalogueSynchronizationMetrics.ASSESSMENT)
                                .counters()
                                .stream()
                                .collect(
                                        Collectors.toMap(
                                                counter -> counter.getId().getTag("assessment"),
                                                counter -> counter.count())))
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of(
                                "clean", 0.0,
                                "data_quality", 1.0,
                                "stopped", 1.0,
                                "skipped", 0.0,
                                "attention", 1.0));
        assertThat(
                        registry.find("catalogue.synchronization.run.records")
                                .tag("kind", "data_quality_failed_games")
                                .summary()
                                .totalAmount())
                .isEqualTo(1.0);
    }

    @Test
    void thePlayerScorePanelsSelectExactlyTheRecordedSeries() throws Exception {
        var registry = new SimpleMeterRegistry();
        new CatalogueSynchronizationMetrics(registry).recordRun(report());
        var recordedKinds =
                registry.find("catalogue.synchronization.run.records").summaries().stream()
                        .map(summary -> summary.getId().getTag("kind"))
                        .toList();
        List<String> expressions = new ArrayList<>();
        for (JsonNode panel :
                JsonMapper.builder().build().readTree(Files.readString(DASHBOARD)).get("panels")) {
            for (JsonNode target : panel.path("targets")) {
                String expression = target.path("expr").asString("");
                if (expression.contains("player_score")) {
                    expressions.add(expression);
                }
            }
        }

        assertThat(expressions).isNotEmpty();
        for (String expression : expressions) {
            Matcher kinds = Pattern.compile("kind=~\"([^\"]+)\"").matcher(expression);
            if (kinds.find()) {
                Pattern selected = Pattern.compile(kinds.group(1));
                assertThat(recordedKinds.stream().filter(kind -> selected.matcher(kind).matches()))
                        .as(expression)
                        .containsExactlyInAnyOrderElementsOf(playerScoreRecords(registry).keySet());
            } else {
                assertThat(expression)
                        .contains(
                                "reason=\""
                                        + ProviderMappingFailure.PLAYER_SCORE_INVALID
                                                .name()
                                                .toLowerCase(Locale.ROOT)
                                        + "\"");
            }
        }
    }

    private static Map<String, Double> playerScoreRecords(SimpleMeterRegistry registry) {
        return registry.find("catalogue.synchronization.run.records").summaries().stream()
                .filter(summary -> summary.getId().getTag("kind").startsWith("player_score_"))
                .collect(
                        Collectors.toMap(
                                summary -> summary.getId().getTag("kind"),
                                DistributionSummary::totalAmount));
    }

    private static Counters none() {
        return new Counters(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0);
    }

    private static CatalogueSynchronizationReport report(
            SynchronizationOutcome outcome, String code, Counters counters) {
        return new CatalogueSynchronizationReport(
                UUID.fromString("10000000-0000-4000-8000-000000000001"),
                LocalDate.parse("2026-10-10"),
                LocalDate.parse("2026-10-13"),
                Instant.parse("2026-10-14T10:00:00Z"),
                Instant.parse("2026-10-14T10:01:00Z"),
                outcome,
                code,
                counters);
    }

    private static CatalogueSynchronizationReport report() {
        return new CatalogueSynchronizationReport(
                UUID.fromString("10000000-0000-4000-8000-000000000001"),
                LocalDate.parse("2026-10-10"),
                LocalDate.parse("2026-10-13"),
                Instant.parse("2026-10-14T10:00:00Z"),
                Instant.parse("2026-10-14T10:01:00Z"),
                SynchronizationOutcome.SUCCEEDED,
                "SYNCHRONIZATION_COMPLETED",
                new Counters(
                        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 31,
                        32, 33, 34, 35, 36, 37, 38, 0));
    }
}
