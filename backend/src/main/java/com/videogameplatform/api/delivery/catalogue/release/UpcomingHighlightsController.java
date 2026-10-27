package com.videogameplatform.api.delivery.catalogue.release;

import com.videogameplatform.api.delivery.ConditionalRequestSupport;
import com.videogameplatform.api.delivery.catalogue.CatalogueCoverMapper;
import com.videogameplatform.api.delivery.catalogue.ReleaseSummaryMapper;
import com.videogameplatform.api.generated.UpcomingHighlightsApi;
import com.videogameplatform.api.generated.model.Genre;
import com.videogameplatform.api.generated.model.Platform;
import com.videogameplatform.api.generated.model.PlatformUpcomingHighlights;
import com.videogameplatform.api.generated.model.Region;
import com.videogameplatform.api.generated.model.ReleaseWindow;
import com.videogameplatform.api.generated.model.UpcomingHighlightItem;
import com.videogameplatform.catalogue.application.releases.BrowseUpcomingHighlightsUseCase;
import com.videogameplatform.catalogue.application.releases.UpcomingHighlightsResult;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public HTTP adapter for UC-020 (Próximos destacados); it reads local state only and never calls a
 * provider.
 */
@RestController
@RequestMapping("/api/v1")
public class UpcomingHighlightsController implements UpcomingHighlightsApi {

    private final BrowseUpcomingHighlightsUseCase useCase;
    private final CatalogueCoverMapper coverMapper;
    private final ConditionalRequestSupport conditionalRequests;
    private final UpcomingHighlightsApiMetrics metrics;
    private final ReleaseHttpProperties properties;

    UpcomingHighlightsController(
            BrowseUpcomingHighlightsUseCase useCase,
            CatalogueCoverMapper coverMapper,
            ConditionalRequestSupport conditionalRequests,
            UpcomingHighlightsApiMetrics metrics,
            ReleaseHttpProperties properties) {
        this.useCase = useCase;
        this.coverMapper = coverMapper;
        this.conditionalRequests = conditionalRequests;
        this.metrics = metrics;
        this.properties = properties;
    }

    @Override
    public ResponseEntity<PlatformUpcomingHighlights> getPlatformUpcomingHighlights(
            Set<String> platformIds, String ifNoneMatch) {
        UpcomingHighlightsResult result =
                useCase.browse(new BrowseUpcomingHighlightsUseCase.Query(normalized(platformIds)));
        PlatformUpcomingHighlights body = toResponse(result);
        String entityTag = conditionalRequests.strongEntityTag(body);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CACHE_CONTROL, properties.cacheControl());
        headers.setETag(entityTag);
        if (conditionalRequests.matches(ifNoneMatch, entityTag)) {
            return ResponseEntity.status(304).headers(headers).build();
        }

        metrics.recordSelection(result);
        return ResponseEntity.ok().headers(headers).body(body);
    }

    /** Trimmed, without blanks and de-duplicated in first-seen order. */
    private static List<String> normalized(Set<String> values) {
        return List.copyOf(
                values.stream()
                        .map(String::trim)
                        .filter(value -> !value.isEmpty())
                        .collect(Collectors.toCollection(LinkedHashSet::new)));
    }

    private PlatformUpcomingHighlights toResponse(UpcomingHighlightsResult result) {
        return new PlatformUpcomingHighlights(
                result.platforms().stream()
                        .map(platform -> new Platform(platform.id(), platform.name()))
                        .collect(Collectors.toCollection(LinkedHashSet::new)),
                result.evaluatedOn(),
                new ReleaseWindow(result.window().from(), result.window().to()),
                FeaturedReleaseController.toSelection(result.selection()),
                result.items().stream().map(this::toItem).toList());
    }

    private UpcomingHighlightItem toItem(UpcomingHighlightsResult.Item item) {
        UpcomingHighlightsResult.PlatformRelease release = item.platformRelease();
        return new UpcomingHighlightItem(
                item.position(),
                item.gameId(),
                item.slug(),
                item.canonicalTitle(),
                coverMapper.toResponse(item.primaryCover()),
                FeaturedReleaseController.toImage(item.image()),
                item.genres().stream()
                        .map(term -> new Genre(term.code(), term.name()))
                        .collect(Collectors.toCollection(LinkedHashSet::new)),
                ReleaseSummaryMapper.toResponse(
                        new Platform(release.platform().id(), release.platform().name()),
                        new Region(release.region().id(), release.region().name()),
                        release.releaseDate(),
                        release.status()));
    }
}
