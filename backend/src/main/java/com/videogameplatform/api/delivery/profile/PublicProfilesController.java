package com.videogameplatform.api.delivery.profile;

import com.videogameplatform.api.delivery.ApiRequestException;
import com.videogameplatform.api.delivery.catalogue.CatalogueCoverMapper;
import com.videogameplatform.api.generated.PublicProfilesApi;
import com.videogameplatform.api.generated.model.PageMetadata;
import com.videogameplatform.api.generated.model.ProblemCode;
import com.videogameplatform.api.generated.model.PublicPlayerProfile;
import com.videogameplatform.api.generated.model.PublicProfileGame;
import com.videogameplatform.api.generated.model.PublicProfileGamePage;
import com.videogameplatform.api.generated.model.PublicProfileRatingSort;
import com.videogameplatform.api.generated.model.PublicProfileRatingStatistics;
import com.videogameplatform.api.generated.model.PublicProfileSection;
import com.videogameplatform.api.generated.model.PublicProfileSettings;
import com.videogameplatform.api.generated.model.PublicProfileSettingsCommand;
import com.videogameplatform.api.generated.model.PublicProfileStatistics;
import com.videogameplatform.catalogue.application.wishlist.Wishlist;
import com.videogameplatform.identity.application.CurrentUser;
import com.videogameplatform.identity.application.CurrentUserUnavailableException;
import com.videogameplatform.identity.application.PlayerProfiles;
import com.videogameplatform.identity.application.PlayerProfiles.Rejected;
import com.videogameplatform.ratings.application.ListShareableRatings;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class PublicProfilesController implements PublicProfilesApi {
    private final CurrentUser user;
    private final PlayerProfiles profiles;
    private final ListShareableRatings ratings;
    private final Wishlist wishlist;
    private final CatalogueCoverMapper covers;

    public PublicProfilesController(
            CurrentUser user,
            PlayerProfiles profiles,
            ListShareableRatings ratings,
            Wishlist wishlist,
            CatalogueCoverMapper covers) {
        this.user = user;
        this.profiles = profiles;
        this.ratings = ratings;
        this.wishlist = wishlist;
        this.covers = covers;
    }

    @Override
    public ResponseEntity<PublicProfileSettings> getMyPublicProfileSettings() {
        return noStore(
                profiles.settings(owner())
                        .map(PublicProfilesController::settings)
                        .orElseGet(() -> new PublicProfileSettings(false, false, false)));
    }

    @Override
    public ResponseEntity<PublicProfileSettings> updateMyPublicProfileSettings(
            String csrfToken, PublicProfileSettingsCommand command) {
        var saved =
                user.changeAsCurrentUser(
                        id ->
                                profiles.save(
                                        id,
                                        new PlayerProfiles.Change(
                                                command.getAlias(),
                                                command.getRevision(),
                                                command.getPublished(),
                                                command.getShareRatings(),
                                                command.getShareWishlist())));
        return noStore(settings(saved));
    }

    @Override
    public ResponseEntity<PublicPlayerProfile> getPublicPlayerProfile(String alias) {
        var profile = publicProfile(alias);
        var sections = new LinkedHashSet<PublicProfileSection>(2);
        if (profile.shareRatings()) sections.add(PublicProfileSection.ratings);
        if (profile.shareWishlist()) sections.add(PublicProfileSection.wishlist);
        return noStore(new PublicPlayerProfile(profile.alias(), sections));
    }

    @Override
    public ResponseEntity<PublicProfileGamePage> listPublicProfileSection(
            String alias,
            PublicProfileSection section,
            PublicProfileRatingSort sort,
            Integer page,
            Integer pageSize) {
        var profile = sharedSection(alias, section);
        if (section == PublicProfileSection.wishlist && sort != null) {
            throw new ApiRequestException(ProblemCode.SORT_INVALID, "/query/sort");
        }
        var result = readSection(profile.userId(), section, ratingOrder(sort), page, pageSize);
        // The module-owned list read can finish after consent has been revoked.
        if (!sharedSection(alias, section).userId().equals(profile.userId())) throw missing();
        return noStore(result);
    }

    @Override
    public ResponseEntity<PublicProfileStatistics> getPublicProfileStatistics(String alias) {
        var profile = publicProfile(alias);
        var response = new PublicProfileStatistics();
        if (profile.shareRatings()) {
            var statistics = ratings.statisticsForSharing(profile.userId());
            response.ratings(
                    new PublicProfileRatingStatistics(statistics.total(), statistics.distribution())
                            .mean(statistics.mean())
                            .lowest(statistics.lowest())
                            .highest(statistics.highest()));
        }
        if (profile.shareWishlist()) {
            response.wishlistCount(wishlist.countForSharing(profile.userId()));
        }
        if (!profile.equals(publicProfile(alias))) throw missing();
        return noStore(response);
    }

    private static ListShareableRatings.Order ratingOrder(PublicProfileRatingSort sort) {
        return switch (sort) {
            case null -> ListShareableRatings.Order.RATING_DATE;
            case ratingDate -> ListShareableRatings.Order.RATING_DATE;
            case score -> ListShareableRatings.Order.SCORE;
            case releaseDate -> ListShareableRatings.Order.RELEASE_DATE;
            case interest -> ListShareableRatings.Order.INTEREST;
        };
    }

    private PublicProfileGamePage readSection(
            String owner,
            PublicProfileSection section,
            ListShareableRatings.Order order,
            int page,
            int pageSize) {
        if (section == PublicProfileSection.ratings) {
            var result = ratings.listForSharing(owner, order, page, pageSize);
            var items =
                    result.items().stream()
                            .map(
                                    game ->
                                            new PublicProfileGame(
                                                            game.gameId(),
                                                            covers.toResponse(game.cover()))
                                                    .slug(game.slug())
                                                    .canonicalTitle(game.title())
                                                    .score(game.score()))
                            .toList();
            return gamePage(items, page, pageSize, result.totalItems());
        }
        var result = wishlist.listForSharing(owner, page, pageSize);
        var items =
                result.items().stream()
                        .map(
                                game ->
                                        new PublicProfileGame(
                                                        game.id(), covers.toResponse(game.cover()))
                                                .slug(game.slug())
                                                .canonicalTitle(game.title()))
                        .toList();
        return gamePage(items, page, pageSize, result.totalItems());
    }

    private PlayerProfiles.Published sharedSection(String alias, PublicProfileSection section) {
        var profile = publicProfile(alias);
        boolean shared =
                switch (section) {
                    case ratings -> profile.shareRatings();
                    case wishlist -> profile.shareWishlist();
                };
        if (!shared) throw missing();
        return profile;
    }

    private PlayerProfiles.Published publicProfile(String alias) {
        return profiles.published(alias).orElseThrow(PublicProfilesController::missing);
    }

    private String owner() {
        return user.currentUserId().orElseThrow(CurrentUserUnavailableException::new);
    }

    private static Rejected missing() {
        return new Rejected(Rejected.Reason.NOT_FOUND);
    }

    private static PublicProfileSettings settings(PlayerProfiles.Settings saved) {
        return new PublicProfileSettings(
                        saved.published(), saved.shareRatings(), saved.shareWishlist())
                .alias(saved.alias())
                .revision(saved.revision());
    }

    private static PublicProfileGamePage gamePage(
            List<PublicProfileGame> items, int page, int pageSize, long total) {
        return new PublicProfileGamePage(
                items, new PageMetadata(page, pageSize, total, Math.ceilDiv(total, pageSize)));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
