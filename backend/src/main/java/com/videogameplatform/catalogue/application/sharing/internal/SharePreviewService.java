package com.videogameplatform.catalogue.application.sharing.internal;

import com.videogameplatform.catalogue.application.cover.CatalogueCover;
import com.videogameplatform.catalogue.application.cover.port.CatalogueCoverReference;
import com.videogameplatform.catalogue.application.sharing.GetSharePreviewUseCase;
import com.videogameplatform.catalogue.application.sharing.port.ProviderShareCoverResolver;
import com.videogameplatform.catalogue.application.sharing.port.SharePreviewReadPort;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Framework-independent link-preview reads; an identifier that is no UUID names nothing. */
public final class SharePreviewService implements GetSharePreviewUseCase {
    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");

    private final SharePreviewReadPort read;
    private final ProviderShareCoverResolver covers;

    public SharePreviewService(SharePreviewReadPort read, ProviderShareCoverResolver covers) {
        this.read = read;
        this.covers = covers;
    }

    @Override
    public Optional<GamePreview> game(String gameId) {
        return identifier(gameId)
                .flatMap(read::findGame)
                .map(
                        game ->
                                new GamePreview(
                                        game.gameId(),
                                        game.slug(),
                                        game.canonicalTitle(),
                                        game.summary(),
                                        cover(game.cover())));
    }

    @Override
    public Optional<String> platformName(String platformId) {
        return identifier(platformId).flatMap(read::findPlatformName);
    }

    private CatalogueCover cover(CatalogueCoverReference reference) {
        return switch (reference) {
            case CatalogueCoverReference.Provider provider -> {
                var resolved =
                        covers.resolveShare(
                                provider.provider(), provider.reference(), provider.sourceUrl());
                yield new CatalogueCover.Provider(
                        resolved.url(),
                        provider.alternativeText(),
                        new CatalogueCover.Attribution(
                                resolved.attributionLabel(), resolved.attributionUrl()));
            }
            case CatalogueCoverReference.Product product ->
                    new CatalogueCover.Product(product.assetPath(), product.alternativeText());
            case CatalogueCoverReference.Unavailable unavailable ->
                    new CatalogueCover.Unavailable();
        };
    }

    private static Optional<UUID> identifier(String value) {
        return value != null && UUID_TEXT.matcher(value).matches()
                ? Optional.of(UUID.fromString(value))
                : Optional.empty();
    }
}
