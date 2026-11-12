package com.videogameplatform.catalogue.application.attention.internal;

import com.videogameplatform.catalogue.application.administration.EditorialAttentionFileInvalidException;
import com.videogameplatform.catalogue.application.administration.EditorialAttentionLists;
import com.videogameplatform.catalogue.application.attention.port.EditorialAttentionStore;
import com.videogameplatform.catalogue.application.attention.port.EditorialAttentionStore.GameCandidate;
import com.videogameplatform.catalogue.application.attention.port.EditorialAttentionStore.NewEntry;
import com.videogameplatform.catalogue.application.attention.port.EditorialAttentionStore.NewList;
import com.videogameplatform.catalogue.application.attention.port.EditorialAttentionStore.RangeReleases;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Matches an editorial file's entries to catalogued Games and stores its list (UC-023). An entry
 * is matched by its IGDB slug, else by a title that exactly one catalogued Game carries; the
 * operator's assignments override both.
 */
public final class EditorialAttentionService implements EditorialAttentionLists {

    /** Title matches read per title: enough to tell one Game from several. */
    private static final int TITLE_MATCHES = 2;

    private final EditorialAttentionStore store;
    private final Clock clock;

    public EditorialAttentionService(EditorialAttentionStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Override
    public Prompt prompt() {
        return EditorialAttentionPrompt.prompt();
    }

    @Override
    public List<ListSummary> lists() {
        return store.lists(OVERVIEW_LIMIT);
    }

    @Override
    public Preview preview(EditorialFile file) {
        Map<Integer, Match> matches = match(file);
        Set<String> games = new HashSet<>();
        matches.values().forEach(match -> match.game().ifPresent(game -> games.add(game.gameId())));
        Map<String, RangeReleases> releases =
                games.isEmpty() ? Map.of() : store.releasesInRange(games, file.from(), file.to());
        Set<String> seen = new HashSet<>();
        List<PreviewEntry> entries = new ArrayList<>();
        for (FileEntry entry : file.entries()) {
            Match match = matches.get(entry.rank());
            List<Warning> warnings = new ArrayList<>(match.warnings());
            Optional<GameMatch> game =
                    match.game()
                            .map(
                                    candidate -> {
                                        RangeReleases inRange = releases.get(candidate.gameId());
                                        if (inRange == null) {
                                            warnings.add(Warning.NO_RELEASE_IN_RANGE);
                                        } else if (entry.releaseDate().isPresent()
                                                && !inRange.exactDays().isEmpty()
                                                && !inRange.exactDays()
                                                        .contains(
                                                                entry.releaseDate()
                                                                        .orElseThrow())) {
                                            warnings.add(Warning.RELEASE_DATE_DIFFERS);
                                        }
                                        if (!seen.add(candidate.gameId())) {
                                            warnings.add(Warning.DUPLICATE_GAME);
                                        }
                                        return new GameMatch(
                                                candidate.gameId(),
                                                candidate.title(),
                                                candidate.slug(),
                                                match.kind(),
                                                inRange == null
                                                        ? Optional.empty()
                                                        : inRange.earliest());
                                    });
            entries.add(new PreviewEntry(entry, game, warnings));
        }
        Neighbours neighbours = neighbours(file);
        return new Preview(file, neighbours.replaced(), neighbours.conflict(), entries);
    }

    @Override
    public LoadOutcome load(EditorialFile file, List<Assignment> assignments) {
        Map<Integer, FileEntry> byRank = new LinkedHashMap<>();
        file.entries().forEach(entry -> byRank.put(entry.rank(), entry));
        Map<Integer, Optional<String>> chosen = new HashMap<>();
        for (int index = 0; index < assignments.size(); index++) {
            Assignment assignment = assignments.get(index);
            if (!byRank.containsKey(assignment.rank())
                    || chosen.put(assignment.rank(), assignment.gameId()) != null) {
                throw new EditorialAttentionFileInvalidException(
                        "/assignments/" + index + "/rank",
                        "An assignment names a rank of the file once.");
            }
        }
        Set<String> assigned = new LinkedHashSet<>();
        chosen.values().forEach(game -> game.ifPresent(assigned::add));
        Map<String, GameCandidate> assignedGames =
                assigned.isEmpty() ? Map.of() : store.findByIds(assigned);
        for (int index = 0; index < assignments.size(); index++) {
            Optional<String> game = assignments.get(index).gameId();
            if (game.isPresent() && !assignedGames.containsKey(game.orElseThrow())) {
                throw new EditorialAttentionFileInvalidException(
                        "/assignments/" + index + "/gameId",
                        "The assigned Game is not in the catalogue.");
            }
        }
        Map<Integer, Match> matches = match(file);
        // One entry per Game: the highest score, then the better rank.
        Map<String, NewEntry> entries = new LinkedHashMap<>();
        for (FileEntry entry : file.entries()) {
            Optional<String> game =
                    chosen.containsKey(entry.rank())
                            ? chosen.get(entry.rank())
                            : matches.get(entry.rank()).game().map(GameCandidate::gameId);
            game.ifPresent(
                    gameId ->
                            entries.merge(
                                    gameId,
                                    new NewEntry(
                                            gameId,
                                            entry.rank(),
                                            entry.score(),
                                            entry.title(),
                                            entry.rationale(),
                                            entry.sources()),
                                    (kept, offered) ->
                                            offered.score() > kept.score()
                                                            || (offered.score() == kept.score()
                                                                    && offered.rank() < kept.rank())
                                                    ? offered
                                                    : kept));
        }
        if (entries.isEmpty()) {
            throw new EditorialAttentionFileInvalidException(
                    "/entries", "No entry names a catalogued Game.");
        }
        Neighbours neighbours = neighbours(file);
        if (neighbours.conflict().isPresent()) {
            return new LoadOutcome.Conflict(neighbours.conflict().orElseThrow());
        }
        UUID listId = UUID.randomUUID();
        Optional<UUID> saved =
                store.save(
                        new NewList(
                                listId,
                                file.from(),
                                file.to(),
                                file.promptVersion(),
                                file.model(),
                                file.generatedAt(),
                                clock.instant(),
                                entries.values()),
                        neighbours.replaced().map(ListSummary::listId));
        if (saved.isEmpty()) {
            // Another list took part of the range between the check and the write.
            return neighbours(file)
                    .conflict()
                    .or(() -> neighbours(file).replaced())
                    .<LoadOutcome>map(LoadOutcome.Conflict::new)
                    .orElseThrow(() -> new IllegalStateException("A list overlap disappeared"));
        }
        return new LoadOutcome.Loaded(
                store.find(saved.orElseThrow())
                        .orElseThrow(() -> new IllegalStateException("A stored list is missing")));
    }

    @Override
    public Optional<ListDetail> list(UUID listId) {
        return store.find(listId);
    }

    @Override
    public boolean delete(UUID listId) {
        return store.delete(listId);
    }

    /** Each entry's automatic match, keyed by rank. */
    private Map<Integer, Match> match(EditorialFile file) {
        Set<String> slugs = new HashSet<>();
        Set<String> titles = new HashSet<>();
        file.entries()
                .forEach(
                        entry -> {
                            entry.igdbSlug().ifPresent(slugs::add);
                            titles.add(entry.title());
                        });
        Map<String, GameCandidate> bySlug =
                slugs.isEmpty() ? Map.of() : store.findByProviderSlug(slugs);
        Set<String> unmatchedTitles = new HashSet<>();
        file.entries().stream()
                .filter(entry -> entry.igdbSlug().map(bySlug::get).isEmpty())
                .forEach(entry -> unmatchedTitles.add(entry.title()));
        Map<String, List<GameCandidate>> byTitle =
                unmatchedTitles.isEmpty()
                        ? Map.of()
                        : store.findByTitle(unmatchedTitles, TITLE_MATCHES);
        Map<Integer, Match> matches = new HashMap<>();
        for (FileEntry entry : file.entries()) {
            Optional<GameCandidate> slugMatch = entry.igdbSlug().map(bySlug::get);
            if (slugMatch.isPresent()) {
                matches.put(entry.rank(), new Match(slugMatch, MatchKind.IGDB_SLUG, List.of()));
                continue;
            }
            List<GameCandidate> titled = byTitle.getOrDefault(entry.title(), List.of());
            matches.put(
                    entry.rank(),
                    switch (titled.size()) {
                        case 0 ->
                                new Match(
                                        Optional.empty(),
                                        MatchKind.TITLE,
                                        List.of(Warning.UNMATCHED));
                        case 1 ->
                                new Match(
                                        Optional.of(titled.getFirst()), MatchKind.TITLE, List.of());
                        default ->
                                new Match(
                                        Optional.empty(),
                                        MatchKind.TITLE,
                                        List.of(Warning.AMBIGUOUS_TITLE));
                    });
        }
        return matches;
    }

    /** The list with the same range, which a load replaces, and the first other overlapping list. */
    private Neighbours neighbours(EditorialFile file) {
        Optional<ListSummary> replaced = Optional.empty();
        Optional<ListSummary> conflict = Optional.empty();
        for (ListSummary list : store.overlapping(file.from(), file.to())) {
            if (list.from().equals(file.from()) && list.to().equals(file.to())) {
                replaced = Optional.of(list);
            } else if (conflict.isEmpty()) {
                conflict = Optional.of(list);
            }
        }
        return new Neighbours(replaced, conflict);
    }

    private record Match(Optional<GameCandidate> game, MatchKind kind, List<Warning> warnings) {}

    private record Neighbours(Optional<ListSummary> replaced, Optional<ListSummary> conflict) {}
}
