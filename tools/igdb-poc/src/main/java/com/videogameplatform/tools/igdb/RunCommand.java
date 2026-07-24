package com.videogameplatform.tools.igdb;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videogameplatform.tools.igdb.model.ActualCase;
import com.videogameplatform.tools.igdb.model.ExecutionStats;
import com.videogameplatform.tools.igdb.normalization.IgdbNormalizer;
import com.videogameplatform.tools.igdb.normalization.IgdbNormalizer.ProviderSelection;
import com.videogameplatform.tools.igdb.report.ActualCaseCsvRepository;
import com.videogameplatform.tools.igdb.report.EvidenceStore;
import com.videogameplatform.tools.igdb.sample.CsvSampleReader;
import com.videogameplatform.tools.igdb.sample.ExpectedCase;
import com.videogameplatform.tools.igdb.support.QueryLoader;
import com.videogameplatform.tools.igdb.support.SecretRedactor;
import com.videogameplatform.tools.igdb.support.TextNormalizer;
import com.videogameplatform.tools.igdb.validation.AcceptanceContext;
import com.videogameplatform.tools.igdb.validation.PocReport;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "run",
        description = "Capture IGDB evidence, normalize it and validate the approved sample.")
public final class RunCommand implements Callable<Integer> {

    @Option(
            names = "--sample",
            defaultValue = "docs/research/igdb-poc-sample.csv",
            description = "Approved control sample CSV.")
    private Path sample;

    @Option(
            names = "--output",
            defaultValue = ".poc/igdb",
            description = "Generated evidence directory.")
    private Path output;

    @Option(
            names = "--requests-per-second",
            defaultValue = "3",
            description = "Maximum IGDB requests per second (must be <= 3).")
    private double requestsPerSecond;

    @Override
    public Integer call() throws Exception {
        String clientId = System.getenv("IGDB_CLIENT_ID");
        String clientSecret = System.getenv("IGDB_CLIENT_SECRET");
        ObjectMapper objectMapper = PocRuntime.objectMapper();
        List<ExpectedCase> expectedCases = new CsvSampleReader().read(sample);
        ExecutionStats stats = new ExecutionStats();
        PocRuntime.Clients clients = PocRuntime.clients(objectMapper, stats, requestsPerSecond);
        EvidenceStore evidence = new EvidenceStore(
                output, objectMapper, List.of(safe(clientId), safe(clientSecret)));
        evidence.prepare();

        String accessToken = clients.tokenClient().getAppAccessToken(clientId, clientSecret);
        QueryLoader queries = new QueryLoader();
        IgdbNormalizer normalizer = new IgdbNormalizer(objectMapper);
        List<ActualCase> actualCases = new ArrayList<>();

        for (ExpectedCase expected : expectedCases) {
            ActualCase actual;
            try {
                String searchResponse = clients.igdbClient().queryGames(
                        clientId, accessToken, queries.gameSearch(expected.searchQuery()));
                evidence.writeRaw(expected.caseId(), "search", searchResponse);
                ProviderSelection selection = normalizer.selectProvider(expected, searchResponse);
                if (selection.providerId() == null
                        && !TextNormalizer.titleMatches(
                                expected.searchQuery(), expected.expectedTitle())) {
                    String canonicalSearchResponse = clients.igdbClient().queryGames(
                            clientId, accessToken, queries.gameSearch(expected.expectedTitle()));
                    evidence.writeRaw(
                            expected.caseId(), "search-canonical", canonicalSearchResponse);
                    selection = normalizer.selectProvider(expected, canonicalSearchResponse);
                }
                if (selection.providerId() == null) {
                    actual = normalizer.normalize(expected, selection, "[]", "[]");
                } else {
                    String detailsResponse = clients.igdbClient().queryGames(
                            clientId, accessToken, queries.gameDetails(selection.providerId()));
                    String releasesResponse = clients.igdbClient().queryReleaseDates(
                            clientId, accessToken, queries.releaseDates(selection.providerId()));
                    evidence.writeRaw(expected.caseId(), "details", detailsResponse);
                    evidence.writeRaw(expected.caseId(), "releases", releasesResponse);
                    actual = normalizer.normalize(
                            expected, selection, detailsResponse, releasesResponse);
                }
            } catch (RuntimeException exception) {
                String safeMessage = SecretRedactor.redact(
                        exception.getMessage(),
                        List.of(safe(clientId), safe(clientSecret), safe(accessToken)));
                actual = technicalFailure(expected.caseId(), safeMessage);
            }
            evidence.writeNormalized(actual);
            actualCases.add(actual);
        }

        stats.complete();
        Path actualResults = output.resolve("actual-results.csv");
        new ActualCaseCsvRepository(objectMapper).write(actualResults, actualCases);
        AcceptanceContext context = new AcceptanceContext(
                "run",
                requestsPerSecond,
                expectedCases.size(),
                actualCases.size(),
                0,
                stats);
        PocReport report = new ValidationWorkflow(objectMapper).validate(
                sample, output, expectedCases, actualCases, context);

        System.out.println("Decision: " + report.decision());
        System.out.println("Actual results: " + actualResults);
        System.out.println("Report: " + output.resolve("report.md"));
        if (evidence.redactionCount() > 0) {
            System.out.println("Sensitive values redacted from generated evidence: "
                    + evidence.redactionCount());
        }
        return ValidationWorkflow.exitCode(report.decision());
    }

    private ActualCase technicalFailure(String caseId, String error) {
        return new ActualCase(
                caseId, false, null, "", "", "", List.of(), List.of(), false, false,
                false, null, Instant.now(), "IGDB", 0, 0, "technical_error: " + error);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
