package com.videogameplatform.tools.igdb.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videogameplatform.tools.igdb.validation.CaseValidation;
import com.videogameplatform.tools.igdb.validation.FieldCheck;
import com.videogameplatform.tools.igdb.validation.MetricResult;
import com.videogameplatform.tools.igdb.validation.PocReport;

public final class ReportWriter {

    private final ObjectMapper objectMapper;

    public ReportWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(Path output, PocReport report) throws IOException {
        Files.createDirectories(output);
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(output.resolve("report.json").toFile(), report);
        Files.writeString(
                output.resolve("report.md"),
                markdown(report),
                StandardCharsets.UTF_8);
    }

    private String markdown(PocReport report) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# IGDB PoC report\n\n");
        markdown.append("- **Decision:** `").append(report.decision()).append("`\n");
        markdown.append("- **Execution mode:** `").append(report.executionMode()).append("`\n");
        markdown.append("- **Sample:** `").append(report.sample()).append("`\n");
        markdown.append("- **Generated at:** ").append(report.generatedAt()).append("\n");
        markdown.append("- **Blocking failures:** ").append(report.blockingFailures()).append("\n");
        markdown.append("- **Non-blocking failures:** ").append(report.nonBlockingFailures()).append("\n");
        markdown.append("- **Manual reviews:** ").append(report.manualReviews()).append("\n\n");

        markdown.append("## Acceptance metrics\n\n");
        markdown.append("| Metric | Threshold | Actual | Blocking | Result |\n");
        markdown.append("|---|---:|---:|---|---|\n");
        for (MetricResult metric : report.metrics()) {
            markdown.append("| ").append(escape(metric.description()))
                    .append(" | ").append(escape(metric.threshold()))
                    .append(" | ").append(escape(metric.actual()))
                    .append(" | ").append(metric.blocking() ? "Yes" : "No")
                    .append(" | `").append(metric.status()).append("` |\n");
        }

        markdown.append("\n## Case results\n\n");
        markdown.append("| Case | Selected title | Result | Failed or review checks |\n");
        markdown.append("|---|---|---|---|\n");
        for (CaseValidation validation : report.cases()) {
            String checks = validation.checks().stream()
                    .filter(check -> check.outcome()
                            != com.videogameplatform.tools.igdb.validation.CheckOutcome.PASS)
                    .filter(check -> check.outcome()
                            != com.videogameplatform.tools.igdb.validation.CheckOutcome.NOT_APPLICABLE)
                    .map(this::summary)
                    .reduce((left, right) -> left + "; " + right)
                    .orElse("");
            markdown.append("| `").append(validation.caseId())
                    .append("` | ").append(escape(validation.selectedTitle()))
                    .append(" | `").append(validation.outcome())
                    .append("` | ").append(escape(checks)).append(" |\n");
        }

        markdown.append("\n## Recommendation\n\n");
        markdown.append(report.recommendation()).append("\n\n");
        markdown.append("> Review this generated report before copying its conclusions to ")
                .append("`docs/research/igdb-poc-results.md`.\n");
        return markdown.toString();
    }

    private String summary(FieldCheck check) {
        return check.field() + "=" + check.outcome();
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("|", "\\|").replace("\n", " ");
    }
}
