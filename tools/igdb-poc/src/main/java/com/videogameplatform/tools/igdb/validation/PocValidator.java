package com.videogameplatform.tools.igdb.validation;

import java.util.ArrayList;
import java.util.List;

import com.videogameplatform.tools.igdb.model.ActualCase;
import com.videogameplatform.tools.igdb.model.ActualRelease;
import com.videogameplatform.tools.igdb.sample.ExpectedCase;
import com.videogameplatform.tools.igdb.support.TextNormalizer;

public final class PocValidator {

    public CaseValidation validate(ExpectedCase expected, ActualCase actual) {
        List<FieldCheck> checks = new ArrayList<>();
        boolean blocking = expected.isBlocking();

        checks.add(check("found", "true", Boolean.toString(actual.found()),
                actual.found(), blocking, "The expected game must be selected from the search results"));
        checks.add(check("provider_id", "present", value(actual.providerId()),
                actual.providerId() != null, true, "Provider identity is mandatory"));
        checks.add(check("provenance", "IGDB", actual.provenance(),
                "IGDB".equals(actual.provenance()), true, "Provenance is mandatory"));
        checks.add(check("synchronized_at", "present", value(actual.synchronizedAt()),
                actual.synchronizedAt() != null, true, "Synchronization timestamp is mandatory"));
        checks.add(check("normalization_error", "none", actual.normalizationError(),
                actual.normalizationError().isBlank(), true, "Normalization errors must be explicit"));

        if (actual.found()) {
            checks.add(check("title", expected.expectedTitle(), actual.title(),
                    titleMatches(expected.expectedTitle(), actual.title()), blocking,
                    "Selected candidate identity must be strict"));
            checks.add(typeCheck(expected.expectedType(), actual.type(), blocking));
            checks.add(optionalTitleCheck(
                    "parent_title", expected.expectedParentTitle(), actual.parentTitle(), blocking));
            checks.add(alternativeTitleCheck(expected, actual));
            checks.add(check("cover", "present", Boolean.toString(actual.coverAvailable()),
                    actual.coverAvailable(), false, "Cover availability is non-blocking"));
            checks.add(check("genre", "present", Boolean.toString(actual.genreAvailable()),
                    actual.genreAvailable(), false, "Genre availability is non-blocking"));
            checks.add(check("company", "present", Boolean.toString(actual.companyAvailable()),
                    actual.companyAvailable(), false, "Developer or publisher availability is non-blocking"));
            checks.add(check("duplicates", "identity resolved",
                    actual.exactTitleMatchCount() + " exact-title candidate(s)",
                    true, true,
                    "The selector accepts only one highest-scoring candidate; raw duplicates are observational"));
            addReleaseChecks(expected, actual, checks, blocking);
        } else {
            addNotApplicable(checks, "title", "type", "parent_title", "alternative_title",
                    "cover", "genre", "company", "duplicates", "platform", "region",
                    "release_date", "date_precision", "status");
        }

        return new CaseValidation(
                expected.caseId(),
                expected.category(),
                expected.criticality(),
                actual.providerId(),
                actual.title(),
                List.copyOf(checks),
                outcome(checks));
    }

    private void addReleaseChecks(
            ExpectedCase expected,
            ActualCase actual,
            List<FieldCheck> checks,
            boolean blocking) {
        if (expected.expectedPlatform().isBlank()) {
            addNotApplicable(checks, "platform", "region", "release_date", "date_precision", "status");
            return;
        }

        List<ActualRelease> platformMatches = actual.releases().stream()
                .filter(release -> TextNormalizer.platformMatches(
                        expected.expectedPlatform(), release.platform()))
                .toList();
        checks.add(check("platform", expected.expectedPlatform(), platforms(actual.releases()),
                !platformMatches.isEmpty(), blocking,
                "Platform must exist on the selected game"));

        List<ActualRelease> tupleCandidates = platformMatches.stream()
                .filter(release -> expected.expectedRegion().isBlank()
                        || TextNormalizer.regionMatches(expected.expectedRegion(), release.region()))
                .toList();
        checks.add(check("region", expected.expectedRegion(), regions(platformMatches),
                expected.expectedRegion().isBlank() || !tupleCandidates.isEmpty(), blocking,
                "Region must belong to the same platform release"));

        ActualRelease matchingDate = tupleCandidates.stream()
                .filter(release -> dateMatches(expected, release))
                .findFirst()
                .orElse(null);
        boolean dateExpected = !expected.expectedReleaseDate().isBlank();
        CheckOutcome dateOutcome = matchingDate != null || !dateExpected
                ? CheckOutcome.PASS
                : futureDateReview(expected) ? CheckOutcome.REVIEW : CheckOutcome.FAIL;
        checks.add(new FieldCheck(
                "release_date",
                expected.expectedReleaseDate(),
                releaseDates(tupleCandidates),
                dateExpected ? dateOutcome : CheckOutcome.NOT_APPLICABLE,
                blocking,
                "Date is evaluated within the platform-region release tuple"));

        boolean precisionMatched = tupleCandidates.stream().anyMatch(release ->
                precisionMatches(expected.expectedDatePrecision(), release.datePrecision())
                        && (!dateExpected || dateValueMatches(expected.expectedReleaseDate(), release.releaseDate())));
        checks.add(new FieldCheck(
                "date_precision",
                expected.expectedDatePrecision(),
                precisions(tupleCandidates),
                precisionMatched ? CheckOutcome.PASS
                        : futureDateReview(expected) ? CheckOutcome.REVIEW : CheckOutcome.FAIL,
                blocking,
                "Date precision must be preserved; year and TBD are not fabricated as exact days"));

        boolean statusMatched = tupleCandidates.stream().anyMatch(release ->
                statusMatches(expected.expectedStatus(), release.status()));
        CheckOutcome statusOutcome = statusMatched ? CheckOutcome.PASS : CheckOutcome.FAIL;
        checks.add(new FieldCheck(
                "status",
                expected.expectedStatus(),
                statuses(tupleCandidates),
                statusOutcome,
                blocking,
                "Status must belong to the same platform-region-date release tuple"));
    }

    private boolean dateMatches(ExpectedCase expected, ActualRelease actual) {
        return precisionMatches(expected.expectedDatePrecision(), actual.datePrecision())
                && (expected.expectedReleaseDate().isBlank()
                        || dateValueMatches(expected.expectedReleaseDate(), actual.releaseDate()));
    }

    private boolean dateValueMatches(String expected, String actual) {
        return expected.equals(actual);
    }

    private boolean precisionMatches(String expected, String actual) {
        return TextNormalizer.identifier(expected).equals(TextNormalizer.identifier(actual));
    }

    private boolean statusMatches(String expected, String actual) {
        return TextNormalizer.identifier(expected).equals(TextNormalizer.identifier(actual));
    }

    private boolean futureDateReview(ExpectedCase expected) {
        return "announced".equalsIgnoreCase(expected.expectedStatus())
                && ("upcoming_release".equals(expected.category())
                        || "delayed_imprecise".equals(expected.category()));
    }

    private FieldCheck alternativeTitleCheck(ExpectedCase expected, ActualCase actual) {
        if (expected.expectedAlternativeTitle().isBlank()) {
            return notApplicable("alternative_title");
        }
        boolean found = actual.alternativeTitles().stream().anyMatch(
                title -> titleMatches(expected.expectedAlternativeTitle(), title));
        return check(
                "alternative_title",
                expected.expectedAlternativeTitle(),
                String.join(" | ", actual.alternativeTitles()),
                found,
                false,
                "Localized and alternative-title misses are an accepted limitation");
    }

    private FieldCheck typeCheck(String expected, String actual, boolean blocking) {
        if (expected.isBlank()) {
            return notApplicable("type");
        }
        if (identifierMatches(expected, actual)) {
            return check("type", expected, actual, true, blocking,
                    "Provider type matches the expected canonical type");
        }
        boolean silentMerge = !isMainGame(expected) && isMainGame(actual);
        return new FieldCheck(
                "type",
                expected,
                actual,
                silentMerge || actual.isBlank() ? CheckOutcome.FAIL : CheckOutcome.REVIEW,
                blocking,
                silentMerge
                        ? "A distinct edition, port, remaster, DLC or expansion was mapped as a main game"
                        : "Provider taxonomy differs explicitly and requires canonical mapping review");
    }

    private FieldCheck optionalTitleCheck(String field, String expected, String actual, boolean blocking) {
        if (expected.isBlank()) {
            return notApplicable(field);
        }
        return check(field, expected, actual, titleMatches(expected, actual), blocking,
                "Parent identity is required when declared by the sample");
    }

    private FieldCheck check(
            String field,
            String expected,
            String actual,
            boolean passes,
            boolean blocking,
            String detail) {
        return new FieldCheck(
                field, expected, actual, passes ? CheckOutcome.PASS : CheckOutcome.FAIL,
                blocking, detail);
    }

    private FieldCheck notApplicable(String field) {
        return new FieldCheck(field, "", "", CheckOutcome.NOT_APPLICABLE, false, "");
    }

    private void addNotApplicable(List<FieldCheck> checks, String... fields) {
        for (String field : fields) {
            checks.add(notApplicable(field));
        }
    }

    private CaseOutcome outcome(List<FieldCheck> checks) {
        if (checks.stream().anyMatch(check ->
                check.outcome() == CheckOutcome.FAIL && check.blocking())) {
            return CaseOutcome.FAIL;
        }
        if (checks.stream().anyMatch(check ->
                check.outcome() == CheckOutcome.REVIEW || check.outcome() == CheckOutcome.FAIL)) {
            return CaseOutcome.REVIEW;
        }
        return CaseOutcome.PASS;
    }

    private boolean titleMatches(String expected, String actual) {
        return TextNormalizer.titleMatches(expected, actual);
    }

    private boolean identifierMatches(String expected, String actual) {
        return TextNormalizer.identifier(expected).equals(TextNormalizer.identifier(actual));
    }

    private boolean isMainGame(String value) {
        return "main_game".equals(TextNormalizer.identifier(value));
    }

    private String value(Object value) {
        return value == null ? "" : value.toString();
    }

    private String platforms(List<ActualRelease> releases) {
        return releases.stream().map(ActualRelease::platform).distinct().sorted().toList().toString();
    }

    private String regions(List<ActualRelease> releases) {
        return releases.stream().map(ActualRelease::region).distinct().sorted().toList().toString();
    }

    private String releaseDates(List<ActualRelease> releases) {
        return releases.stream().map(ActualRelease::releaseDate).distinct().sorted().toList().toString();
    }

    private String precisions(List<ActualRelease> releases) {
        return releases.stream().map(ActualRelease::datePrecision).distinct().sorted().toList().toString();
    }

    private String statuses(List<ActualRelease> releases) {
        return releases.stream().map(ActualRelease::status).distinct().sorted().toList().toString();
    }
}
