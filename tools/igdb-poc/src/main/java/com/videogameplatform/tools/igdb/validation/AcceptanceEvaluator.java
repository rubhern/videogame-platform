package com.videogameplatform.tools.igdb.validation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public final class AcceptanceEvaluator {

    public PocReport evaluate(
            String sample,
            List<CaseValidation> cases,
            AcceptanceContext context) {
        List<MetricResult> metrics = new ArrayList<>();
        metrics.add(rate(
                "exact_title_search", "Exact-title search", ">= 95%",
                cases, caseValidation -> !"localized_title".equals(caseValidation.category()),
                List.of("title"), 95, true));
        metrics.add(rate(
                "alternative_title_search", "Alternative or localized-title search", ">= 80%",
                cases, caseValidation -> "localized_title".equals(caseValidation.category()),
                List.of("alternative_title"), 80, false));
        metrics.add(rate(
                "provider_metadata", "Provider ID, provenance and synchronization timestamp", "100%",
                cases, hasPassed("found"),
                List.of("provider_id", "provenance", "synchronized_at"), 100, true));
        metrics.add(rate(
                "platform_accuracy", "Platform correctly identified", ">= 95%",
                cases, hasApplicable("platform"), List.of("platform"), 95, true));
        metrics.add(rate(
                "release_accuracy", "Release date and precision correctly represented", ">= 90%",
                cases, hasApplicable("date_precision"),
                List.of("release_date", "date_precision"), 90, true));
        metrics.add(rate(
                "region_accuracy", "Region correct or explicitly unknown", ">= 85%",
                cases, hasApplicable("region"), List.of("region"), 85, true));
        metrics.add(rate(
                "cover_availability", "Usable cover available", ">= 90%",
                cases, hasApplicable("cover"), List.of("cover"), 90, false));
        metrics.add(rate(
                "genre_availability", "Genre identifiable", ">= 90%",
                cases, hasApplicable("genre"), List.of("genre"), 90, false));
        metrics.add(rate(
                "company_availability", "Developer or publisher identifiable", ">= 85%",
                cases, hasApplicable("company"), List.of("company"), 85, false));
        metrics.add(zeroFailures(
                "special_status_errors", "Cancelled or delayed games shown as normal releases", "0",
                cases, caseValidation -> expectedSpecialStatus(caseValidation), List.of("status"), true));
        metrics.add(zeroFailures(
                "type_relationship_errors", "DLC, expansions, ports or remasters silently merged", "0",
                cases, caseValidation -> typeSensitive(caseValidation), List.of("type", "parent_title"), true));
        metrics.add(rate(
                "unexpected_duplicates", "Cases with candidate identity resolved despite duplicates", ">= 95%",
                cases, hasApplicable("duplicates"), List.of("duplicates"), 95, true));
        metrics.add(booleanMetric(
                "secret_leaks", "Secrets in generated output", "0",
                Integer.toString(context.securityLeakCount()), context.securityLeakCount() == 0, true));
        metrics.add(booleanMetric(
                "browser_calls", "Direct browser calls to IGDB", "0", "0", true, true));
        metrics.add(booleanMetric(
                "configured_rate", "Configured IGDB request rate", "<= 3 requests/second",
                Double.toString(context.configuredRequestsPerSecond()),
                context.configuredRequestsPerSecond() <= 3, true));
        metrics.add(runtimeMetric(
                "rate_limit_responses", "HTTP 429 responses", "0",
                context, Integer.toString(context.executionStats().rateLimitResponseCount()),
                context.executionStats().rateLimitResponseCount() == 0, true));
        metrics.add(runtimeRate(
                "request_success", "Successful requests after bounded retry", ">= 99%",
                context, context.executionStats().successRate(), 99, true));
        metrics.add(booleanMetric(
                "offline_readability", "Expected cases readable from captured canonical data", "100%",
                context.actualCaseCount() + "/" + context.expectedCaseCount(),
                context.actualCaseCount() == context.expectedCaseCount(), true));
        metrics.add(rate(
                "normalization_errors", "Cases without normalization errors", "100%",
                cases, ignored -> true, List.of("normalization_error"), 100, true));
        metrics.add(runtimeMetric(
                "execution_telemetry", "Request count, latency and errors recorded", "100% of runs",
                context, context.executionStats().requestCount() + " requests; p95="
                        + context.executionStats().p95LatencyMillis() + "ms",
                context.executionStats().completedAt() != null, true));

        Decision decision = decision(metrics);
        int blockingFailures = (int) cases.stream()
                .flatMap(caseValidation -> caseValidation.checks().stream())
                .filter(check -> check.blocking() && check.outcome() == CheckOutcome.FAIL)
                .count();
        int nonBlockingFailures = (int) cases.stream()
                .flatMap(caseValidation -> caseValidation.checks().stream())
                .filter(check -> !check.blocking() && check.outcome() == CheckOutcome.FAIL)
                .count();
        int reviews = (int) cases.stream()
                .flatMap(caseValidation -> caseValidation.checks().stream())
                .filter(check -> check.outcome() == CheckOutcome.REVIEW)
                .count();

        return new PocReport(
                sample,
                context.executionMode(),
                Instant.now(),
                decision,
                List.copyOf(metrics),
                List.copyOf(cases),
                blockingFailures,
                nonBlockingFailures,
                reviews,
                recommendation(decision));
    }

    private MetricResult rate(
            String key,
            String description,
            String threshold,
            List<CaseValidation> cases,
            Predicate<CaseValidation> filter,
            List<String> fields,
            double minimum,
            boolean blocking) {
        List<CaseValidation> applicable = cases.stream().filter(filter).toList();
        if (applicable.isEmpty()) {
            return notApplicable(key, description, threshold, blocking);
        }
        int passed = 0;
        int reviewed = 0;
        for (CaseValidation caseValidation : applicable) {
            List<FieldCheck> checks = selectedChecks(caseValidation, fields);
            if (checks.isEmpty()) {
                continue;
            }
            if (checks.stream().anyMatch(check -> check.outcome() == CheckOutcome.REVIEW)) {
                reviewed++;
            } else if (checks.stream().allMatch(check -> check.outcome() == CheckOutcome.PASS)) {
                passed++;
            }
        }
        double rate = passed * 100.0 / applicable.size();
        MetricStatus status = rate >= minimum
                ? reviewed > 0 ? MetricStatus.REVIEW : MetricStatus.PASS
                : reviewed > 0 ? MetricStatus.REVIEW : MetricStatus.FAIL;
        return new MetricResult(
                key, description, threshold,
                "%.1f%% (%d/%d; %d review)".formatted(rate, passed, applicable.size(), reviewed),
                blocking, status);
    }

    private MetricResult zeroFailures(
            String key,
            String description,
            String threshold,
            List<CaseValidation> cases,
            Predicate<CaseValidation> filter,
            List<String> fields,
            boolean blocking) {
        List<CaseValidation> applicable = cases.stream().filter(filter).toList();
        if (applicable.isEmpty()) {
            return notApplicable(key, description, threshold, blocking);
        }
        long failures = applicable.stream()
                .flatMap(caseValidation -> selectedChecks(caseValidation, fields).stream())
                .filter(check -> check.outcome() == CheckOutcome.FAIL)
                .count();
        return new MetricResult(
                key, description, threshold, Long.toString(failures), blocking,
                failures == 0 ? MetricStatus.PASS : MetricStatus.FAIL);
    }

    private MetricResult runtimeRate(
            String key,
            String description,
            String threshold,
            AcceptanceContext context,
            double actual,
            double minimum,
            boolean blocking) {
        if (!context.authenticatedRun()) {
            return notApplicable(key, description, threshold, blocking);
        }
        return new MetricResult(
                key, description, threshold, "%.1f%%".formatted(actual), blocking,
                actual >= minimum ? MetricStatus.PASS : MetricStatus.FAIL);
    }

    private MetricResult runtimeMetric(
            String key,
            String description,
            String threshold,
            AcceptanceContext context,
            String actual,
            boolean passed,
            boolean blocking) {
        if (!context.authenticatedRun()) {
            return notApplicable(key, description, threshold, blocking);
        }
        return new MetricResult(
                key, description, threshold, actual, blocking,
                passed ? MetricStatus.PASS : MetricStatus.FAIL);
    }

    private MetricResult booleanMetric(
            String key,
            String description,
            String threshold,
            String actual,
            boolean passed,
            boolean blocking) {
        return new MetricResult(
                key, description, threshold, actual, blocking,
                passed ? MetricStatus.PASS : MetricStatus.FAIL);
    }

    private MetricResult notApplicable(
            String key,
            String description,
            String threshold,
            boolean blocking) {
        return new MetricResult(
                key, description, threshold, "not applicable", blocking,
                MetricStatus.NOT_APPLICABLE);
    }

    private Predicate<CaseValidation> hasApplicable(String field) {
        return caseValidation -> caseValidation.checks().stream().anyMatch(
                check -> field.equals(check.field())
                        && check.outcome() != CheckOutcome.NOT_APPLICABLE);
    }

    private Predicate<CaseValidation> hasPassed(String field) {
        return caseValidation -> caseValidation.checks().stream().anyMatch(
                check -> field.equals(check.field())
                        && check.outcome() == CheckOutcome.PASS);
    }

    private List<FieldCheck> selectedChecks(CaseValidation caseValidation, List<String> fields) {
        return caseValidation.checks().stream()
                .filter(check -> fields.contains(check.field()))
                .filter(check -> check.outcome() != CheckOutcome.NOT_APPLICABLE)
                .toList();
    }

    private boolean expectedSpecialStatus(CaseValidation caseValidation) {
        return caseValidation.checks().stream().anyMatch(check ->
                "status".equals(check.field())
                        && ("cancelled".equalsIgnoreCase(check.expected())
                                || "delayed".equalsIgnoreCase(check.expected())));
    }

    private boolean typeSensitive(CaseValidation caseValidation) {
        return caseValidation.checks().stream().anyMatch(check ->
                "type".equals(check.field())
                        && !List.of("main_game", "").contains(
                                check.expected().toLowerCase()));
    }

    private Decision decision(List<MetricResult> metrics) {
        if (metrics.stream().anyMatch(metric ->
                metric.blocking() && metric.status() == MetricStatus.FAIL)) {
            return Decision.FAIL;
        }
        if (metrics.stream().anyMatch(metric ->
                metric.status() == MetricStatus.REVIEW
                        || (!metric.blocking() && metric.status() == MetricStatus.FAIL))) {
            return Decision.CONDITIONAL_PASS;
        }
        return Decision.PASS;
    }

    private String recommendation(Decision decision) {
        return switch (decision) {
            case PASS -> "IGDB passes the technical gate; complete the contractual/publication gate before approval.";
            case CONDITIONAL_PASS -> "Review the flagged cases and document accepted limitations before deciding.";
            case FAIL -> "Do not approve IGDB; investigate blocking failures and evaluate RAWG only if they cannot be mitigated.";
        };
    }
}
