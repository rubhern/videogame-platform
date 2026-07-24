package com.videogameplatform.tools.igdb;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videogameplatform.tools.igdb.model.ActualCase;
import com.videogameplatform.tools.igdb.model.ExecutionStats;
import com.videogameplatform.tools.igdb.report.ActualCaseCsvRepository;
import com.videogameplatform.tools.igdb.sample.CsvSampleReader;
import com.videogameplatform.tools.igdb.sample.ExpectedCase;
import com.videogameplatform.tools.igdb.validation.AcceptanceContext;
import com.videogameplatform.tools.igdb.validation.PocReport;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "validate",
        description = "Re-run deterministic validation without calling IGDB.")
public final class ValidateCommand implements Callable<Integer> {

    @Option(
            names = "--sample",
            defaultValue = "docs/research/igdb-poc-sample.csv",
            description = "Approved control sample CSV.")
    private Path sample;

    @Option(
            names = "--actual",
            defaultValue = ".poc/igdb/actual-results.csv",
            description = "Canonical actual-results CSV created by run.")
    private Path actual;

    @Option(
            names = "--output",
            defaultValue = ".poc/igdb",
            description = "Directory where reports are regenerated.")
    private Path output;

    @Override
    public Integer call() throws Exception {
        ObjectMapper objectMapper = PocRuntime.objectMapper();
        List<ExpectedCase> expectedCases = new CsvSampleReader().read(sample);
        List<ActualCase> actualCases = new ActualCaseCsvRepository(objectMapper).read(actual);
        ExecutionStats stats = new ExecutionStats();
        stats.complete();
        AcceptanceContext context = new AcceptanceContext(
                "validate",
                0,
                expectedCases.size(),
                actualCases.size(),
                0,
                stats);
        PocReport report = new ValidationWorkflow(objectMapper).validate(
                sample, output, expectedCases, actualCases, context);

        System.out.println("Decision: " + report.decision());
        System.out.println("Validation used no network calls.");
        System.out.println("Report: " + output.resolve("report.md"));
        return ValidationWorkflow.exitCode(report.decision());
    }
}
