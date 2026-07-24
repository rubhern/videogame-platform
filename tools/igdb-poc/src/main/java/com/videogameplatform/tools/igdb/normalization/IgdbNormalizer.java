package com.videogameplatform.tools.igdb.normalization;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.videogameplatform.tools.igdb.model.ActualCase;
import com.videogameplatform.tools.igdb.model.ActualRelease;
import com.videogameplatform.tools.igdb.sample.ExpectedCase;
import com.videogameplatform.tools.igdb.support.TextNormalizer;

public final class IgdbNormalizer {

    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IgdbNormalizer(ObjectMapper objectMapper) {
        this(objectMapper, Clock.systemUTC());
    }

    IgdbNormalizer(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public ActualCase normalize(ExpectedCase expected, String json) {
        Instant synchronizedAt = clock.instant();
        try {
            JsonNode candidates = objectMapper.readTree(json);
            if (!candidates.isArray()) {
                return normalizationFailure(expected, synchronizedAt, "IGDB response is not a JSON array");
            }
            CandidateSelection selection = select(expected, candidates);
            if (selection.node() == null) {
                return ActualCase.notFound(expected.caseId(), candidates.size(), synchronizedAt);
            }
            JsonNode game = selection.node();
            List<ActualRelease> normalizedReleases = releases(game);
            return new ActualCase(
                    expected.caseId(),
                    true,
                    game.path("id").isNumber() ? game.path("id").longValue() : null,
                    text(game, "name"),
                    canonicalIdentifier(nestedText(game, "game_type", "type")),
                    nestedText(game, "parent_game", "name"),
                    names(game.path("alternative_names"), "name"),
                    normalizedReleases,
                    !nestedText(game, "cover", "image_id").isBlank(),
                    game.path("genres").isArray() && !game.path("genres").isEmpty(),
                    hasCompany(game),
                    epochInstant(game.path("updated_at")),
                    synchronizedAt,
                    "IGDB",
                    candidates.size(),
                    selection.exactTitleMatchCount(),
                    releaseNormalizationError(normalizedReleases));
        } catch (Exception exception) {
            return normalizationFailure(expected, synchronizedAt, exception.getClass().getSimpleName()
                    + ": " + exception.getMessage());
        }
    }

    public ProviderSelection selectProvider(ExpectedCase expected, String searchJson) {
        try {
            JsonNode candidates = objectMapper.readTree(searchJson);
            if (!candidates.isArray()) {
                return new ProviderSelection(null, 0, 0, "IGDB search response is not a JSON array");
            }
            CandidateSelection selection = select(expected, candidates);
            Long providerId = selection.node() != null && selection.node().path("id").isNumber()
                    ? selection.node().path("id").longValue()
                    : null;
            return new ProviderSelection(
                    providerId,
                    candidates.size(),
                    selection.exactTitleMatchCount(),
                    "");
        } catch (Exception exception) {
            return new ProviderSelection(
                    null, 0, 0, exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
    }

    public ActualCase normalize(
            ExpectedCase expected,
            ProviderSelection selection,
            String detailsJson,
            String releasesJson) {
        Instant synchronizedAt = clock.instant();
        if (!selection.error().isBlank()) {
            return normalizationFailure(expected, synchronizedAt, selection.error());
        }
        if (selection.providerId() == null) {
            return ActualCase.notFound(expected.caseId(), selection.candidateCount(), synchronizedAt);
        }
        try {
            JsonNode details = objectMapper.readTree(detailsJson);
            JsonNode releases = objectMapper.readTree(releasesJson);
            if (!details.isArray() || details.isEmpty() || !releases.isArray()) {
                return normalizationFailure(
                        expected, synchronizedAt, "IGDB detail or release response has an invalid shape");
            }
            ObjectNode game = ((ObjectNode) details.get(0)).deepCopy();
            game.set("release_dates", ((ArrayNode) releases).deepCopy());
            ActualCase normalized = normalize(
                    expected,
                    objectMapper.createArrayNode().add(game).toString());
            return new ActualCase(
                    normalized.caseId(),
                    normalized.found(),
                    normalized.providerId(),
                    normalized.title(),
                    normalized.type(),
                    normalized.parentTitle(),
                    normalized.alternativeTitles(),
                    normalized.releases(),
                    normalized.coverAvailable(),
                    normalized.genreAvailable(),
                    normalized.companyAvailable(),
                    normalized.providerUpdatedAt(),
                    normalized.synchronizedAt(),
                    normalized.provenance(),
                    selection.candidateCount(),
                    selection.exactTitleMatchCount(),
                    normalized.normalizationError());
        } catch (Exception exception) {
            return normalizationFailure(expected, synchronizedAt, exception.getClass().getSimpleName()
                    + ": " + exception.getMessage());
        }
    }

    private CandidateSelection select(ExpectedCase expected, JsonNode candidates) {
        List<JsonNode> nodes = new ArrayList<>();
        candidates.forEach(nodes::add);
        int exactTitleMatches = (int) nodes.stream()
                .filter(node -> TextNormalizer.titleMatches(
                        expected.expectedTitle(), text(node, "name")))
                .count();
        int highestScore = nodes.stream()
                .mapToInt(node -> score(expected, node))
                .max()
                .orElse(Integer.MIN_VALUE);
        List<JsonNode> bestCandidates = nodes.stream()
                .filter(node -> highestScore >= 90 && score(expected, node) == highestScore)
                .toList();
        JsonNode selected = bestCandidates.size() == 1 ? bestCandidates.get(0) : null;
        return new CandidateSelection(selected, exactTitleMatches);
    }

    private int score(ExpectedCase expected, JsonNode candidate) {
        String name = text(candidate, "name");
        List<String> alternatives = names(candidate.path("alternative_names"), "name");
        int score = 0;
        if (TextNormalizer.titleMatches(expected.expectedTitle(), name)) {
            score = 120;
        } else if (!expected.expectedAlternativeTitle().isBlank()
                && alternatives.stream().anyMatch(alternative ->
                        TextNormalizer.titleMatches(expected.expectedAlternativeTitle(), alternative))) {
            score = 110;
        } else if (TextNormalizer.titleMatches(expected.searchQuery(), name)
                || alternatives.stream().anyMatch(alternative ->
                        TextNormalizer.titleMatches(expected.searchQuery(), alternative))) {
            score = 100;
        }
        String type = canonicalIdentifier(nestedText(candidate, "game_type", "type"));
        if (!expected.expectedType().isBlank() && !type.isBlank()) {
            score += type.equals(canonicalIdentifier(expected.expectedType())) ? 20 : -30;
        }
        String parent = nestedText(candidate, "parent_game", "name");
        if (!expected.expectedParentTitle().isBlank()) {
            score += TextNormalizer.titleMatches(expected.expectedParentTitle(), parent) ? 20 : -30;
        }
        JsonNode platforms = candidate.path("platforms");
        if (!expected.expectedPlatform().isBlank() && platforms.isArray() && !platforms.isEmpty()) {
            boolean platformMatches = names(platforms, "name").stream().anyMatch(
                    platform -> TextNormalizer.platformMatches(
                            expected.expectedPlatform(), platform));
            score += platformMatches ? 30 : -20;
        }
        Integer expectedYear = expectedYear(expected.expectedReleaseDate());
        JsonNode firstReleaseDate = candidate.path("first_release_date");
        if (expectedYear != null && firstReleaseDate.canConvertToLong()) {
            int candidateYear = Instant.ofEpochSecond(firstReleaseDate.longValue())
                    .atZone(ZoneOffset.UTC)
                    .getYear();
            if (candidateYear == expectedYear) {
                score += 15;
            }
        }
        return score;
    }

    private List<ActualRelease> releases(JsonNode game) {
        List<ActualRelease> releases = new ArrayList<>();
        JsonNode releaseNodes = game.path("release_dates");
        if (!releaseNodes.isArray()) {
            return releases;
        }
        String gameStatus = nestedText(game, "game_status", "status");
        for (JsonNode release : releaseNodes) {
            String precision = datePrecision(nestedText(release, "date_format", "format"), release);
            String date = releaseDate(precision, release);
            String rawStatus = nestedText(release, "status", "name");
            releases.add(new ActualRelease(
                    nestedText(release, "platform", "name"),
                    nestedText(release, "release_region", "region"),
                    date,
                    precision,
                    canonicalStatus(rawStatus, gameStatus, date, precision),
                    rawStatus));
        }
        return List.copyOf(releases);
    }

    private String datePrecision(String rawFormat, JsonNode release) {
        String format = rawFormat.toUpperCase(Locale.ROOT);
        if (format.contains("TBD")) {
            return "unknown";
        }
        if (format.contains("Q1") || format.contains("Q2")
                || format.contains("Q3") || format.contains("Q4")) {
            return "quarter";
        }
        if (format.equals("YYYY")) {
            return "year";
        }
        if (format.equals("YYYYMMMM") || format.equals("YYYYMM")) {
            return "month";
        }
        if (format.equals("YYYYMMMMDD") || format.equals("YYYYMMDD")) {
            return "day";
        }
        if (release.path("d").canConvertToInt()) {
            return "day";
        }
        if (release.path("m").canConvertToInt()) {
            return "month";
        }
        if (release.path("y").canConvertToInt()) {
            return "year";
        }
        return "unknown";
    }

    private String releaseDate(String precision, JsonNode release) {
        int year = release.path("y").asInt(0);
        int month = release.path("m").asInt(0);
        int day = release.path("d").asInt(0);
        return switch (precision) {
            case "day" -> release.path("date").canConvertToLong()
                    ? Instant.ofEpochSecond(release.path("date").longValue())
                            .atZone(ZoneOffset.UTC)
                            .toLocalDate()
                            .toString()
                    : validDate(year, month, day);
            case "month" -> year > 0 && month > 0 ? "%04d-%02d".formatted(year, month) : "";
            case "year" -> year > 0 ? Integer.toString(year) : "";
            case "quarter" -> year > 0 ? year + "-" + quarter(nestedText(release, "date_format", "format")) : "";
            default -> "";
        };
    }

    private String validDate(int year, int month, int day) {
        try {
            return LocalDate.of(year, month, day).toString();
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private String quarter(String format) {
        String upper = format.toUpperCase(Locale.ROOT);
        for (String quarter : List.of("Q1", "Q2", "Q3", "Q4")) {
            if (upper.contains(quarter)) {
                return quarter;
            }
        }
        return "";
    }

    private String canonicalStatus(String releaseStatus, String gameStatus, String date, String precision) {
        String combined = (releaseStatus + " " + gameStatus).toLowerCase(Locale.ROOT);
        if (combined.contains("cancel")) {
            return "cancelled";
        }
        if (combined.contains("delay")) {
            return "delayed";
        }
        if (combined.contains("delist")) {
            return "delisted";
        }
        if ("unknown".equals(precision) || date.isBlank()) {
            return gameStatus.isBlank() ? "unknown" : "announced";
        }
        int year = Integer.parseInt(date.substring(0, 4));
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        if ("day".equals(precision)) {
            return LocalDate.parse(date).isAfter(today) ? "announced" : "released";
        }
        return year > today.getYear() ? "announced" : "released";
    }

    private boolean hasCompany(JsonNode game) {
        JsonNode companies = game.path("involved_companies");
        if (!companies.isArray()) {
            return false;
        }
        for (JsonNode involved : companies) {
            if ((involved.path("developer").asBoolean(false)
                    || involved.path("publisher").asBoolean(false))
                    && !nestedText(involved, "company", "name").isBlank()) {
                return true;
            }
        }
        return false;
    }

    private List<String> names(JsonNode array, String field) {
        if (!array.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode node : array) {
            String value = text(node, field);
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : "";
    }

    private String nestedText(JsonNode node, String object, String field) {
        return text(node.path(object), field);
    }

    private Instant epochInstant(JsonNode node) {
        return node.canConvertToLong() ? Instant.ofEpochSecond(node.longValue()) : null;
    }

    private String canonicalIdentifier(String value) {
        return TextNormalizer.identifier(value);
    }

    private Integer expectedYear(String expectedReleaseDate) {
        if (expectedReleaseDate == null || expectedReleaseDate.length() < 4) {
            return null;
        }
        try {
            return Integer.valueOf(expectedReleaseDate.substring(0, 4));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String releaseNormalizationError(List<ActualRelease> releases) {
        boolean missingDate = releases.stream().anyMatch(release ->
                !"unknown".equals(release.datePrecision()) && release.releaseDate().isBlank());
        return missingDate ? "Release precision is known but its date cannot be normalized" : "";
    }

    private ActualCase normalizationFailure(ExpectedCase expected, Instant synchronizedAt, String error) {
        return new ActualCase(
                expected.caseId(), false, null, "", "", "", List.of(), List.of(), false,
                false, false, null, synchronizedAt, "IGDB", 0, 0, error);
    }

    private record CandidateSelection(JsonNode node, int exactTitleMatchCount) {
    }

    public record ProviderSelection(
            Long providerId,
            int candidateCount,
            int exactTitleMatchCount,
            String error) {
    }
}
