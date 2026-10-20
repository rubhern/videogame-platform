package com.videogameplatform.catalogue.application.releases.internal;

import com.videogameplatform.catalogue.application.releases.RecordCommunityScoresUseCase;
import com.videogameplatform.catalogue.application.releases.port.CommunityScoreStore;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Framework-independent maintenance of the community score projection (ADR-0029). */
public final class CommunityScoreService implements RecordCommunityScoresUseCase {

    private final CommunityScoreStore store;

    public CommunityScoreService(CommunityScoreStore store) {
        this.store = store;
    }

    @Override
    public void replace(String gameId, CommunityScore score) {
        if (score == null) {
            throw new IllegalArgumentException("Community score is required");
        }
        store.replace(UUID.fromString(gameId), score);
    }

    @Override
    public Set<String> unprojected(Collection<String> gameIds) {
        if (gameIds.size() > MAX_BATCH) {
            throw new IllegalArgumentException("At most " + MAX_BATCH + " games per batch");
        }
        if (gameIds.isEmpty()) {
            return Set.of();
        }
        return store
                .unprojected(gameIds.stream().map(UUID::fromString).distinct().toList())
                .stream()
                .map(UUID::toString)
                .collect(Collectors.toUnmodifiableSet());
    }
}
