package com.videogameplatform.catalogue.adapter.operator;

import com.videogameplatform.catalogue.application.localization.LocalizeCatalogueUseCase;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;

/** Only on private management, following existing repair commands. */
@Endpoint(id = "cataloguelocalize")
public final class CatalogueLocalizationEndpoint {
    private final LocalizeCatalogueUseCase localization;

    public CatalogueLocalizationEndpoint(LocalizeCatalogueUseCase localization) {
        this.localization = localization;
    }

    @WriteOperation
    public Object localize(
            @Nullable String afterKind, @Nullable String afterId, @Nullable Integer limit) {
        try {
            return localization.backfill(
                    afterKind == null ? "" : afterKind,
                    afterId == null ? new UUID(0, 0) : UUID.fromString(afterId),
                    limit == null ? 10 : limit);
        } catch (IllegalArgumentException invalid) {
            return new WebEndpointResponse<>(Map.of("code", "INVALID_LOCALIZATION_REQUEST"), 400);
        }
    }
}
