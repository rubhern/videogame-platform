package com.videogameplatform.api.delivery.catalogue.release;

import com.videogameplatform.api.delivery.catalogue.CatalogueCoverMapper;
import com.videogameplatform.api.delivery.catalogue.DiscoveryScoresReader;
import com.videogameplatform.api.generated.WishlistApi;
import com.videogameplatform.api.generated.model.PageMetadata;
import com.videogameplatform.api.generated.model.ReleaseCalendar;
import com.videogameplatform.api.generated.model.ReleasePage;
import com.videogameplatform.api.generated.model.ReleaseSort;
import com.videogameplatform.api.generated.model.WishlistGame;
import com.videogameplatform.api.generated.model.WishlistMove;
import com.videogameplatform.api.generated.model.WishlistPage;
import com.videogameplatform.api.generated.model.WishlistStatus;
import com.videogameplatform.catalogue.application.releases.BrowseReleaseCalendarUseCase;
import com.videogameplatform.catalogue.application.releases.BrowseReleasesUseCase;
import com.videogameplatform.catalogue.application.wishlist.Wishlist;
import com.videogameplatform.identity.application.CurrentUser;
import com.videogameplatform.identity.application.CurrentUserUnavailableException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class WishlistController implements WishlistApi {
    private final CurrentUser user;
    private final Wishlist wishlist;
    private final CatalogueCoverMapper covers;
    private final BrowseReleasesUseCase releases;
    private final BrowseReleaseCalendarUseCase calendar;
    private final ReleaseApiMapper releaseMapper;
    private final DiscoveryScoresReader scores;

    public WishlistController(
            CurrentUser user,
            Wishlist wishlist,
            CatalogueCoverMapper covers,
            BrowseReleasesUseCase releases,
            BrowseReleaseCalendarUseCase calendar,
            ReleaseApiMapper releaseMapper,
            DiscoveryScoresReader scores) {
        this.user = user;
        this.wishlist = wishlist;
        this.covers = covers;
        this.releases = releases;
        this.calendar = calendar;
        this.releaseMapper = releaseMapper;
        this.scores = scores;
    }

    @Override
    public ResponseEntity<WishlistPage> listMyWishlist(Integer page, Integer pageSize) {
        var result = wishlist.list(owner(), page, pageSize);
        var items =
                result.items().stream()
                        .map(
                                game ->
                                        new WishlistGame(
                                                        game.id(),
                                                        covers.toResponse(game.cover()),
                                                        WishlistGame.TimingEnum.valueOf(
                                                                game.timing().name()),
                                                        ReleaseApiMapper.toReleaseDate(
                                                                game.releaseDate()))
                                                .slug(game.slug())
                                                .canonicalTitle(game.title()))
                        .toList();
        return privateResponse(
                new WishlistPage(
                        items,
                        new PageMetadata(
                                page,
                                pageSize,
                                result.totalItems(),
                                Math.ceilDiv(result.totalItems(), pageSize))));
    }

    @Override
    public ResponseEntity<WishlistStatus> getMyWishlistedGames(List<String> gameIds) {
        return privateResponse(new WishlistStatus(wishlist.savedGameIds(owner(), gameIds)));
    }

    @Override
    public ResponseEntity<Void> addMyWish(String csrfToken, String gameId) {
        user.changeAsCurrentUser(
                id -> {
                    wishlist.add(id, gameId);
                    return null;
                });
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @Override
    public ResponseEntity<Void> removeMyWish(String csrfToken, String gameId) {
        user.changeAsCurrentUser(
                id -> {
                    wishlist.remove(id, gameId);
                    return null;
                });
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @Override
    public ResponseEntity<Void> moveMyWish(String gameId, String csrfToken, WishlistMove move) {
        user.changeAsCurrentUser(
                id -> {
                    wishlist.move(
                            id,
                            gameId,
                            move.getAnchorGameId(),
                            Wishlist.Placement.valueOf(move.getPlacement().name()));
                    return null;
                });
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @Override
    public ResponseEntity<ReleasePage> listMyWishlistedReleases(
            LocalDate from,
            LocalDate to,
            ReleaseSort sort,
            Boolean includeApproximateDates,
            Set<String> platformIds,
            Set<String> regionIds,
            Integer page,
            Integer pageSize) {
        var result =
                releases.browse(
                        new BrowseReleasesUseCase.Query(
                                from,
                                to,
                                ReleaseController.toApplicationSort(sort),
                                Boolean.TRUE.equals(includeApproximateDates),
                                values(platformIds),
                                values(regionIds),
                                page,
                                pageSize,
                                owner()));
        return privateResponse(releaseMapper.toScoredResponse(result, scores));
    }

    @Override
    public ResponseEntity<ReleaseCalendar> getMyWishlistedReleaseCalendar(
            String month, Boolean includePast, Set<String> platformIds, Set<String> regionIds) {
        var result =
                calendar.browse(
                        new BrowseReleaseCalendarUseCase.Query(
                                Optional.ofNullable(month).map(YearMonth::parse),
                                values(platformIds),
                                values(regionIds),
                                Boolean.TRUE.equals(includePast),
                                owner()));
        return privateResponse(ReleaseCalendarController.toResponse(result));
    }

    private String owner() {
        return user.currentUserId().orElseThrow(CurrentUserUnavailableException::new);
    }

    private static <T> ResponseEntity<T> privateResponse(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static List<String> values(Set<String> ids) {
        return ids == null ? List.of() : List.copyOf(ids);
    }
}
