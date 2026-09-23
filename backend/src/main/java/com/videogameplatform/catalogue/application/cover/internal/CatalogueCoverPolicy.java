package com.videogameplatform.catalogue.application.cover.internal;

import com.videogameplatform.catalogue.application.cover.CatalogueCover;
import com.videogameplatform.catalogue.application.cover.port.CatalogueCoverReference;
import com.videogameplatform.catalogue.application.cover.port.ProviderCoverReferenceResolver;

/** Resolves a persisted cover reference into the single ADR-0001 cover state. */
public final class CatalogueCoverPolicy {

    private final ProviderCoverReferenceResolver coverResolver;

    public CatalogueCoverPolicy(ProviderCoverReferenceResolver coverResolver) {
        this.coverResolver = coverResolver;
    }

    public CatalogueCover resolve(CatalogueCoverReference reference) {
        return switch (reference) {
            case CatalogueCoverReference.Provider provider -> {
                ProviderCoverReferenceResolver.ResolvedProviderCover resolved =
                        coverResolver.resolve(
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
}
