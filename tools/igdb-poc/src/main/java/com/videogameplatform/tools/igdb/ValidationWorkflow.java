package com.videogameplatform.tools.igdb;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videogameplatform.tools.igdb.model.ActualCase;
import com.videogameplatform.tools.igdb.report.ReportWriter;
import com.videogameplatform.tools.igdb.sample.ExpectedCase;
import com.videogameplatform.tools.igdb.validation.AcceptanceContext;
import com.videogameplatform.tools.igdb.validation.AcceptanceEvaluator;
import com.videogameplatform.tools.igdb.validation.CaseValidation;
import com.videogameplatform.tools.igdb.validation.Decision;
import com.videogameplatform.tools.igdb.validation.PocReport;
import com.videogameplatform.tools.igdb.validation.PocValidator;

public final class ValidationWorkflow {

    private final PocValidator validator = new PocValidator();
    private final AcceptanceEvaluator evaluator = new AcceptanceEvaluator();
    private final ReportWriter reportWriter;

    public ValidationWorkflow(ObjectMapper objectMapper) {
        reportWriter = new ReportWriter(objectMapper);
    }

    public PocReport validate(
            Path samplePath,
            Path output,
            List<ExpectedCase> expectedCases,
            List<ActualCase> actualCases,
            AcceptanceContext context) throws IOException {
        Map<String, ActualCase> actualById = new HashMap<>();
        for (ActualCase actual : actualCases) {
            if (actualById.put(actual.caseId(), actual) != null) {
                throw new IllegalArgumentException("Duplicate actual case: " + actual.caseId());
            }
        }

        List<CaseValidation> validations = new ArrayList<>();
        for (ExpectedCase expected : expectedCases) {
            ActualCase actual = actualById.getOrDefault(
                    expected.caseId(),
                    ActualCase.notFound(expected.caseId(), 0, Instant.now()));
            validations.add(validator.validate(expected, actual));
        }
        PocReport report = evaluator.evaluate(samplePath.toString(), validations, context);
        reportWriter.write(output, report);
        return report;
    }

    public static int exitCode(Decision decision) {
        return switch (decision) {
            case PASS -> 0;
            case CONDITIONAL_PASS -> 2;
            case FAIL -> 3;
        };
    }
}
