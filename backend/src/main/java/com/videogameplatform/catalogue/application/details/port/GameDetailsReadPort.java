package com.videogameplatform.catalogue.application.details.port;

import com.videogameplatform.catalogue.application.cover.port.CatalogueCoverReference;
import com.videogameplatform.catalogue.application.details.GameDetailsResult;
import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort;
import java.util.List;
import java.util.Optional;

/** Reads a complete game in one publication, never silently truncating release evidence. */
public interface GameDetailsReadPort {
    int MAX_RELEASES = 256;
    int MAX_ALIASES = 100;

    /** Developer and publisher credits together; one company can hold both. */
    int MAX_COMPANY_CREDITS = 100;

    int MAX_GENRES = 50;
    int MAX_GAME_MODES = 50;

    Optional<Game> find(String gameId);

    /**
     * {@code releases} holds every release of the game, platform by platform with platforms in
     * order of their earliest known date, and each platform's releases in presented-release
     * precedence with the earliest date first: the first release of a platform, and of a platform
     * and region, is the one presented there; later ones are additional records. Developers and
     * publishers are ordered by case-insensitive name, then identity; genres and game modes by
     * case-insensitive name, then code. Each may be empty: absent metadata is never invented.
     */
    record Game(
            String gameId,
            String slug,
            String canonicalTitle,
            List<String> aliases,
            GameDetailsResult.Summary summary,
            List<GameDetailsResult.Company> developers,
            List<GameDetailsResult.Company> publishers,
            List<GameDetailsResult.Term> genres,
            List<GameDetailsResult.Term> gameModes,
            CatalogueCoverReference cover,
            List<ReleaseBrowseReadPort.ReleaseRow> releases) {
        public Game {
            aliases = List.copyOf(aliases);
            developers = List.copyOf(developers);
            publishers = List.copyOf(publishers);
            genres = List.copyOf(genres);
            gameModes = List.copyOf(gameModes);
            releases = List.copyOf(releases);
        }
    }
}
