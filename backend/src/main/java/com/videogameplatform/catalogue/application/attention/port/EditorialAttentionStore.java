package com.videogameplatform.catalogue.application.attention.port;

import com.videogameplatform.catalogue.application.administration.EditorialAttentionLists.ListDetail;
import com.videogameplatform.catalogue.application.administration.EditorialAttentionLists.ListSummary;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Outbound port for editorial anticipation lists and the catalogue lookups that match their
 * entries. Every lookup is keyed and bounded by the at most fifty entries of one file.
 */
public interface EditorialAttentionStore {

    /** The current catalogue Game whose provider page carries each given IGDB slug. */
    Map<String, GameCandidate> findByProviderSlug(Set<String> slugs);

    /**
     * The current catalogue Games whose normalized title equals each given title's, at most
     * {@code perTitle} each in identity order.
     */
    Map<String, List<GameCandidate>> findByTitle(Set<String> titles, int perTitle);

    /** The current catalogue Games among the given identities. */
    Map<String, GameCandidate> findByIds(Set<String> gameIds);

    /** What each given Game releases inside the inclusive range; a Game without one is absent. */
    Map<String, RangeReleases> releasesInRange(Set<String> gameIds, LocalDate from, LocalDate to);

    /** The lists whose range overlaps the inclusive range, earliest first. */
    List<ListSummary> overlapping(LocalDate from, LocalDate to);

    /** At most {@code limit} lists, newest range first. */
    List<ListSummary> lists(int limit);

    Optional<ListDetail> find(UUID listId);

    /**
     * Stores the list in one transaction, first deleting {@code replaced} when present. Empty when
     * another list overlapping the range was stored concurrently; nothing changes then.
     */
    Optional<UUID> save(NewList list, Optional<UUID> replaced);

    boolean delete(UUID listId);

    record GameCandidate(String gameId, String title, String slug) {}

    /** Whether the Game has releases in the range, and the exact days among them. */
    record RangeReleases(Set<LocalDate> exactDays) {
        public RangeReleases {
            exactDays = Set.copyOf(exactDays);
        }

        public Optional<LocalDate> earliest() {
            return exactDays.stream().min(LocalDate::compareTo);
        }
    }

    record NewList(
            UUID listId,
            LocalDate from,
            LocalDate to,
            String promptVersion,
            Optional<String> model,
            Optional<Instant> generatedAt,
            Instant loadedAt,
            Collection<NewEntry> entries) {
        public NewList {
            entries = List.copyOf(entries);
        }
    }

    record NewEntry(
            String gameId,
            int rank,
            int score,
            String fileTitle,
            Optional<String> rationale,
            List<String> sources) {
        public NewEntry {
            sources = List.copyOf(sources);
        }
    }
}
