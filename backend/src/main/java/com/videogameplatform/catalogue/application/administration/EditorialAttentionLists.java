package com.videogameplatform.catalogue.application.administration;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The catalogue operator's editorial anticipation lists (UC-023, ATT-004): the prompt an operator
 * gives a language model, the preview of the file it returns, and the lists that rank release
 * attention.
 *
 * <p>A list covers one date range and scores up to fifty catalogued Games from 1 to 100. Loading a
 * file for a range that already has a list replaces it; a file whose range overlaps another list is
 * refused. Nothing here reads the provider.
 */
public interface EditorialAttentionLists {

    /** The most entries one file may hold. */
    int MAX_ENTRIES = 50;

    /** The longest range one list may cover: a calendar quarter. */
    int MAX_RANGE_DAYS = 92;

    /** The most lists one overview returns, newest range first. */
    int OVERVIEW_LIMIT = 36;

    /** The only file schema version this application reads. */
    int SCHEMA_VERSION = 1;

    Prompt prompt();

    /** The retained lists, newest range first, bounded by {@link #OVERVIEW_LIMIT}. */
    List<ListSummary> lists();

    /** How the file would load: each entry's catalogue match and warnings, and any list it meets. */
    Preview preview(EditorialFile file);

    /**
     * Loads the file. {@code assignments} override the automatic match of the entries they name by
     * rank: a Game identity assigns it, an empty one leaves the entry out. Entries without a Game
     * are left out, and a Game named twice keeps its highest score.
     *
     * @throws EditorialAttentionFileInvalidException when an assignment names an unknown rank or
     *     Game, or no entry would name a Game
     */
    LoadOutcome load(EditorialFile file, List<Assignment> assignments);

    Optional<ListDetail> list(UUID listId);

    /** Whether a list was deleted. */
    boolean delete(UUID listId);

    /** The prompt template; {@code {{FROM}}} and {@code {{TO}}} stand for the range's ISO days. */
    record Prompt(String version, String template) {}

    /** A validated editorial anticipation file. */
    record EditorialFile(
            LocalDate from,
            LocalDate to,
            String promptVersion,
            Optional<String> model,
            Optional<Instant> generatedAt,
            List<FileEntry> entries) {
        public EditorialFile {
            if (from == null) {
                throw invalid("/range/from", "The range needs a first day.");
            }
            if (to == null) {
                throw invalid("/range/to", "The range needs a last day.");
            }
            if (to.isBefore(from)
                    || java.time.temporal.ChronoUnit.DAYS.between(from, to) >= MAX_RANGE_DAYS) {
                throw invalid("/range", "The range must be ordered and at most 92 days long.");
            }
            promptVersion =
                    text(promptVersion, 64, "/generator/prompt")
                            .orElseThrow(
                                    () ->
                                            invalid(
                                                    "/generator/prompt",
                                                    "The file must name its prompt version."));
            model =
                    Objects.requireNonNull(model, "model")
                            .flatMap(value -> text(value, 100, "/generator/model"));
            generatedAt = Objects.requireNonNull(generatedAt, "generatedAt");
            entries = List.copyOf(entries);
            if (entries.isEmpty() || entries.size() > MAX_ENTRIES) {
                throw invalid("/entries", "A file holds between 1 and 50 entries.");
            }
            Set<Integer> ranks = new java.util.HashSet<>();
            for (int index = 0; index < entries.size(); index++) {
                if (!ranks.add(entries.get(index).rank())) {
                    throw invalid("/entries/" + index + "/rank", "Each rank appears once.");
                }
            }
        }
    }

    /** One entry of an editorial file, as the file states it. */
    record FileEntry(
            int rank,
            Optional<String> igdbSlug,
            String title,
            Optional<LocalDate> releaseDate,
            int score,
            Optional<String> rationale,
            List<String> sources) {
        /**
         * Lowercase letters, digits and inner hyphens; checked without backtracking. IGDB tells
         * apart games sharing a title with a {@code --N} suffix, so a double hyphen is valid.
         */
        private static final Pattern SLUG_CHARACTERS = Pattern.compile("[a-z0-9-]+");

        public FileEntry {
            if (rank < 1 || rank > MAX_ENTRIES) {
                throw new IllegalArgumentException("rank");
            }
            if (score < 1 || score > 100) {
                throw new IllegalArgumentException("score");
            }
            igdbSlug = Objects.requireNonNull(igdbSlug, "igdbSlug").map(String::strip);
            if (igdbSlug.isPresent()
                    && (igdbSlug.orElseThrow().length() > 200 || !isSlug(igdbSlug.orElseThrow()))) {
                throw new IllegalArgumentException("igdbSlug");
            }
            title =
                    text(title, 200, "title")
                            .orElseThrow(() -> new IllegalArgumentException("title"));
            releaseDate = Objects.requireNonNull(releaseDate, "releaseDate");
            rationale =
                    Objects.requireNonNull(rationale, "rationale")
                            .flatMap(
                                    value -> {
                                        String stripped = value.strip();
                                        if (stripped.length() > 500) {
                                            throw new IllegalArgumentException("rationale");
                                        }
                                        return stripped.isEmpty()
                                                ? Optional.empty()
                                                : Optional.of(stripped);
                                    });
            sources = List.copyOf(sources);
            if (sources.size() > 10
                    || sources.stream()
                            .anyMatch(source -> source.isBlank() || source.length() > 500)) {
                throw new IllegalArgumentException("sources");
            }
        }

        private static boolean isSlug(String value) {
            return SLUG_CHARACTERS.matcher(value).matches()
                    && !value.startsWith("-")
                    && !value.endsWith("-");
        }
    }

    /** The operator's choice for the entry of one rank: a catalogued Game, or none. */
    record Assignment(int rank, Optional<String> gameId) {
        public Assignment {
            gameId = Objects.requireNonNull(gameId, "gameId");
        }
    }

    record Preview(
            EditorialFile file,
            Optional<ListSummary> replaces,
            Optional<ListSummary> conflict,
            List<PreviewEntry> entries) {
        public Preview {
            entries = List.copyOf(entries);
        }
    }

    record PreviewEntry(FileEntry entry, Optional<GameMatch> match, List<Warning> warnings) {
        public PreviewEntry {
            warnings = List.copyOf(warnings);
        }
    }

    /**
     * The catalogued Game an entry names, how it was found, and the earliest exact day of its
     * releases inside the range, when it has one.
     */
    record GameMatch(
            String gameId,
            String title,
            String slug,
            MatchKind matchedBy,
            Optional<LocalDate> releaseInRange) {}

    enum MatchKind {
        IGDB_SLUG,
        TITLE
    }

    enum Warning {
        /** No catalogued Game carries the slug or exactly the title. */
        UNMATCHED,
        /** Several catalogued Games carry exactly the title; the operator chooses. */
        AMBIGUOUS_TITLE,
        /** The Game has no release in the range, so the entry ranks nothing there. */
        NO_RELEASE_IN_RANGE,
        /** The Game's exact release days in the range do not include the file's date. */
        RELEASE_DATE_DIFFERS,
        /** An earlier entry already names the same Game; the highest score is kept. */
        DUPLICATE_GAME
    }

    sealed interface LoadOutcome {
        record Loaded(ListDetail list) implements LoadOutcome {}

        /** Another list covers part of the range; nothing was stored. */
        record Conflict(ListSummary list) implements LoadOutcome {}
    }

    record ListSummary(
            UUID listId,
            LocalDate from,
            LocalDate to,
            String promptVersion,
            Optional<String> model,
            Optional<Instant> generatedAt,
            Instant loadedAt,
            int entryCount) {}

    record ListDetail(ListSummary summary, List<ListEntry> entries) {
        public ListDetail {
            entries = List.copyOf(entries);
        }
    }

    record ListEntry(
            int rank,
            String gameId,
            String title,
            String slug,
            String fileTitle,
            int score,
            Optional<String> rationale,
            List<String> sources) {
        public ListEntry {
            sources = List.copyOf(sources);
        }
    }

    private static Optional<String> text(String value, int maxLength, String pointer) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String stripped = value.strip();
        if (stripped.length() > maxLength) {
            throw invalid(pointer, "The text is longer than " + maxLength + " characters.");
        }
        return Optional.of(stripped);
    }

    private static EditorialAttentionFileInvalidException invalid(String pointer, String reason) {
        return new EditorialAttentionFileInvalidException(pointer, reason);
    }
}
