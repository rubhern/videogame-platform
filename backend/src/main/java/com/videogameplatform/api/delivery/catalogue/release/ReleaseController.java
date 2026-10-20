package com.videogameplatform.api.delivery.catalogue.release;

import com.videogameplatform.api.delivery.ConditionalRequestSupport;
import com.videogameplatform.api.delivery.catalogue.DiscoveryScoresReader;
import com.videogameplatform.api.generated.ReleasesApi;
import com.videogameplatform.api.generated.model.ReleasePage;
import com.videogameplatform.api.generated.model.ReleaseSort;
import com.videogameplatform.catalogue.application.releases.BrowseReleasesResult;
import com.videogameplatform.catalogue.application.releases.BrowseReleasesUseCase;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public HTTP adapter for UC-001. */
@RestController
@RequestMapping("/api/v1")
public class ReleaseController implements ReleasesApi {

    private final BrowseReleasesUseCase useCase;
    private final ReleaseApiMapper mapper;
    private final ConditionalRequestSupport conditionalRequests;
    private final ReleaseApiMetrics metrics;
    private final ReleaseHttpProperties properties;
    private final DiscoveryScoresReader scores;

    public ReleaseController(
            BrowseReleasesUseCase useCase,
            ReleaseApiMapper mapper,
            ConditionalRequestSupport conditionalRequests,
            ReleaseApiMetrics metrics,
            ReleaseHttpProperties properties,
            DiscoveryScoresReader scores) {
        this.useCase = useCase;
        this.mapper = mapper;
        this.conditionalRequests = conditionalRequests;
        this.metrics = metrics;
        this.properties = properties;
        this.scores = scores;
    }

    @Override
    public ResponseEntity<ReleasePage> listReleases(
            LocalDate from,
            LocalDate to,
            ReleaseSort sort,
            Boolean includeApproximateDates,
            Set<String> platformIds,
            Set<String> regionIds,
            Integer page,
            Integer pageSize,
            String ifNoneMatch) {
        ReleaseSort order = sort == null ? ReleaseSort.releaseDate : sort;
        BrowseReleasesResult result =
                useCase.browse(
                        new BrowseReleasesUseCase.Query(
                                from,
                                to,
                                toApplicationSort(order),
                                Boolean.TRUE.equals(includeApproximateDates),
                                toList(platformIds),
                                toList(regionIds),
                                page,
                                pageSize));
        ReleasePage body = mapper.toResponse(result);
        var scoreBatch =
                scores.read(
                        body.getItems().stream()
                                .map(
                                        com.videogameplatform.api.generated.model.ReleaseItem
                                                ::getGameId)
                                .collect(java.util.stream.Collectors.toSet()));
        body.getItems().forEach(item -> item.setScores(scoreBatch.get(item.getGameId())));
        String entityTag = conditionalRequests.strongEntityTag(body);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CACHE_CONTROL, properties.cacheControl());
        headers.setETag(entityTag);
        if (conditionalRequests.matches(ifNoneMatch, entityTag)) {
            return ResponseEntity.status(304).headers(headers).build();
        }

        metrics.recordResult(order.getValue(), body.getItems().size());
        return ResponseEntity.ok().headers(headers).body(body);
    }

    private static List<String> toList(Set<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static BrowseReleasesUseCase.Sort toApplicationSort(ReleaseSort sort) {
        return switch (sort) {
            case releaseDate -> BrowseReleasesUseCase.Sort.RELEASE_DATE;
            case hype -> BrowseReleasesUseCase.Sort.HYPE;
            case rating -> BrowseReleasesUseCase.Sort.RATING;
            case externalPlayerScore -> BrowseReleasesUseCase.Sort.EXTERNAL_PLAYER_SCORE;
        };
    }
}
