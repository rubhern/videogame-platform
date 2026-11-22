package com.videogameplatform.api.delivery.catalogue.release;

import com.videogameplatform.api.delivery.catalogue.CatalogueCoverMapper;
import com.videogameplatform.api.generated.LibraryApi;
import com.videogameplatform.api.generated.model.LibraryCompletion;
import com.videogameplatform.api.generated.model.LibraryEntryState;
import com.videogameplatform.api.generated.model.LibraryGame;
import com.videogameplatform.api.generated.model.LibraryPage;
import com.videogameplatform.api.generated.model.LibraryState;
import com.videogameplatform.api.generated.model.LibraryStateChange;
import com.videogameplatform.api.generated.model.LibraryStateCounts;
import com.videogameplatform.api.generated.model.LibraryStates;
import com.videogameplatform.api.generated.model.PageMetadata;
import com.videogameplatform.catalogue.application.wishlist.GameLibrary;
import com.videogameplatform.identity.application.CurrentUser;
import com.videogameplatform.identity.application.CurrentUserUnavailableException;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Mis juegos: the current principal's private game states (UC-026, #591). */
@RestController
@RequestMapping("/api/v1")
public class LibraryController implements LibraryApi {
    private final CurrentUser user;
    private final GameLibrary library;
    private final CatalogueCoverMapper covers;

    public LibraryController(CurrentUser user, GameLibrary library, CatalogueCoverMapper covers) {
        this.user = user;
        this.library = library;
        this.covers = covers;
    }

    @Override
    public ResponseEntity<LibraryPage> listMyLibrary(
            LibraryState state, Integer page, Integer pageSize) {
        var result =
                library.list(
                        owner(),
                        Optional.ofNullable(state).map(LibraryController::toApplication),
                        page,
                        pageSize);
        var items =
                result.items().stream()
                        .map(
                                game ->
                                        new LibraryGame(
                                                        game.id(),
                                                        covers.toResponse(game.cover()),
                                                        toResponse(game.state()),
                                                        game.stateChangedAt()
                                                                .atOffset(ZoneOffset.UTC),
                                                        game.released())
                                                .slug(game.slug())
                                                .canonicalTitle(game.title()))
                        .toList();
        var counts = result.counts();
        return privateResponse(
                new LibraryPage(
                        items,
                        new LibraryStateCounts(
                                counts.total(),
                                counts.wished(),
                                counts.backlog(),
                                counts.playing(),
                                counts.completed(),
                                counts.abandoned()),
                        new PageMetadata(
                                page,
                                pageSize,
                                result.totalItems(),
                                Math.ceilDiv(result.totalItems(), pageSize)),
                        result.ratedWithoutState()));
    }

    @Override
    public ResponseEntity<LibraryCompletion> completeMyRatedGames(String csrfToken) {
        int completed = user.changeAsCurrentUser(library::completeRated);
        return privateResponse(new LibraryCompletion(completed));
    }

    @Override
    public ResponseEntity<LibraryStates> getMyGameStates(List<String> gameIds) {
        return privateResponse(
                new LibraryStates(
                        library.states(owner(), gameIds).stream()
                                .map(
                                        entry ->
                                                new LibraryEntryState(
                                                        entry.gameId(), toResponse(entry.state())))
                                .toList()));
    }

    @Override
    public ResponseEntity<Void> setMyGameState(
            String csrfToken, String gameId, LibraryStateChange change) {
        user.changeAsCurrentUser(
                id -> {
                    library.setState(id, gameId, toApplication(change.getState()));
                    return null;
                });
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @Override
    public ResponseEntity<Void> removeMyGameFromLibrary(String csrfToken, String gameId) {
        user.changeAsCurrentUser(
                id -> {
                    library.remove(id, gameId);
                    return null;
                });
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private static GameLibrary.State toApplication(LibraryState state) {
        return GameLibrary.State.valueOf(state.name());
    }

    private static LibraryState toResponse(GameLibrary.State state) {
        return LibraryState.valueOf(state.name());
    }

    private String owner() {
        return user.currentUserId().orElseThrow(CurrentUserUnavailableException::new);
    }

    private static <T> ResponseEntity<T> privateResponse(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
