package com.videogameplatform.api.delivery.catalogue.preferences;

import com.videogameplatform.api.delivery.ConditionalRequestSupport;
import com.videogameplatform.api.delivery.catalogue.release.ReleaseHttpProperties;
import com.videogameplatform.api.generated.model.PlatformList;
import com.videogameplatform.catalogue.application.preferences.ListPlatformsUseCase;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * Public HTTP adapter for the selectable platforms. The list never varies on a session, so it keeps
 * the public discovery caching with a validator over the represented list.
 */
@Component
public final class PlatformListEndpoint {
    private final ListPlatformsUseCase platforms;
    private final ConditionalRequestSupport conditionalRequests;
    private final ReleaseHttpProperties properties;

    PlatformListEndpoint(
            ListPlatformsUseCase platforms,
            ConditionalRequestSupport conditionalRequests,
            ReleaseHttpProperties properties) {
        this.platforms = platforms;
        this.conditionalRequests = conditionalRequests;
        this.properties = properties;
    }

    public ResponseEntity<PlatformList> list(String ifNoneMatch) {
        PlatformList body =
                new PlatformList(
                        platforms.list().platforms().stream()
                                .map(PlatformPreferencesMapper::toResponse)
                                .toList());
        String entityTag = conditionalRequests.strongEntityTag(body);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CACHE_CONTROL, properties.cacheControl());
        headers.setETag(entityTag);
        if (conditionalRequests.matches(ifNoneMatch, entityTag)) {
            return ResponseEntity.status(304).headers(headers).build();
        }
        return ResponseEntity.ok().headers(headers).body(body);
    }
}
