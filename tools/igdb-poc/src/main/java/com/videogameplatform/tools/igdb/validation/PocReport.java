package com.videogameplatform.tools.igdb.validation;

import java.time.Instant;
import java.util.List;

public record PocReport(
        String sample,
        String executionMode,
        Instant generatedAt,
        Decision decision,
        List<MetricResult> metrics,
        List<CaseValidation> cases,
        int blockingFailures,
        int nonBlockingFailures,
        int manualReviews,
        String recommendation) {
}
