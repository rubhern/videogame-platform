package com.videogameplatform.catalogue.adapter.provider.igdb;

import com.videogameplatform.catalogue.application.CatalogueDataInvalidException;
import com.videogameplatform.catalogue.application.cover.port.ProviderCoverReferenceResolver;
import java.net.URI;
import java.util.regex.Pattern;

/** Applies the approved IGDB direct-CDN cover policy from ADR-0001. */
public final class IgdbCoverReferenceResolver implements ProviderCoverReferenceResolver {

    private static final String PROVIDER = "IGDB";
    private static final String COVER_BASE =
            "https://images.igdb.com/igdb/image/upload/t_cover_big/";
    private static final Pattern IMAGE_ID = Pattern.compile("[A-Za-z0-9_-]+");

    @Override
    public ResolvedProviderCover resolve(String provider, String reference, String sourceUrl) {
        try {
            if (!PROVIDER.equalsIgnoreCase(provider)) {
                throw new IllegalArgumentException("Unsupported published cover provider");
            }
            if (!IMAGE_ID.matcher(reference).matches()) {
                throw new IllegalArgumentException("Invalid IGDB cover reference");
            }
            URI attributionUrl = URI.create(sourceUrl);
            if (!"https".equals(attributionUrl.getScheme())
                    || !"www.igdb.com".equalsIgnoreCase(attributionUrl.getHost())
                    || !attributionUrl.getPath().startsWith("/games/")) {
                throw new IllegalArgumentException("Invalid IGDB attribution URL");
            }
            return new ResolvedProviderCover(
                    URI.create(COVER_BASE + reference + ".webp"), PROVIDER, attributionUrl);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new CatalogueDataInvalidException(exception);
        }
    }
}
