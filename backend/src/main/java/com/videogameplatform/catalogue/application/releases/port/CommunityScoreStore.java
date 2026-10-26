package com.videogameplatform.catalogue.application.releases.port;

import com.videogameplatform.catalogue.application.releases.RecordCommunityScoresUseCase.CommunityScore;
import java.util.List;
import java.util.UUID;

/** Outbound port for Catalogue's community score projection; it joins the caller's transaction. */
public interface CommunityScoreStore {

    /** Upserts the score of a game with ratings, or deletes it for a game without any. */
    void replace(UUID gameId, CommunityScore score);

    /** The given games that hold no complete projected score, unrounded mean included. */
    List<UUID> unprojected(List<UUID> gameIds);
}
