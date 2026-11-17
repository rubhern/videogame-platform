package com.videogameplatform.api.delivery.catalogue.platformgames;

import com.videogameplatform.api.delivery.ConditionalRequestSupport;
import com.videogameplatform.api.delivery.catalogue.DiscoveryScoresReader;
import com.videogameplatform.api.generated.PlatformCatalogueApi;
import com.videogameplatform.api.generated.model.PlatformGameReleaseState;
import com.videogameplatform.api.generated.model.PlatformGamesPage;
import com.videogameplatform.api.generated.model.PlatformGamesSort;
import com.videogameplatform.catalogue.application.platformgames.BrowsePlatformGamesUseCase;
import com.videogameplatform.catalogue.application.platformgames.PlatformGames;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public HTTP adapter for UC-019 (Explorar catálogo). */
@RestController
@RequestMapping("/api/v1")
public class PlatformGamesController implements PlatformCatalogueApi {

    /**
     * Scores change independently of the catalogue, so a stored page always revalidates before
     * reuse; the validator covers every game, presented release and reading.
     */
    static final String CACHE_CONTROL = "public, no-cache";

    private final BrowsePlatformGamesUseCase useCase;
    private final PlatformGamesApiMapper mapper;
    private final DiscoveryScoresReader scores;
    private final ConditionalRequestSupport conditionalRequests;
    private final PlatformGamesApiMetrics metrics;

    PlatformGamesController(
            BrowsePlatformGamesUseCase useCase,
            PlatformGamesApiMapper mapper,
            DiscoveryScoresReader scores,
            ConditionalRequestSupport conditionalRequests,
            PlatformGamesApiMetrics metrics) {
        this.useCase = useCase;
        this.mapper = mapper;
        this.scores = scores;
        this.conditionalRequests = conditionalRequests;
        this.metrics = metrics;
    }

    @Override
    public ResponseEntity<PlatformGamesPage> browsePlatformGames(
            Set<String> platformIds,
            String q,
            PlatformGameReleaseState releaseState,
            Integer fromYear,
            Integer toYear,
            Set<String> genreIds,
            PlatformGamesSort sort,
            Integer page,
            Integer pageSize,
            String ifNoneMatch) {
        PlatformGamesSort requested = sort == null ? PlatformGamesSort.release_date_desc : sort;
        PlatformGames result =
                useCase.browse(
                        new BrowsePlatformGamesUseCase.Query(
                                normalized(platformIds),
                                q,
                                PlatformGamesApiMapper.toReleaseState(releaseState),
                                fromYear,
                                toYear,
                                normalized(genreIds),
                                PlatformGamesApiMapper.toSort(requested),
                                page,
                                pageSize));
        // Scores enrich only the returned page, after selection, ordering and paging.
        var scoreBatch =
                scores.read(
                        result.items().stream()
                                .map(PlatformGames.Item::gameId)
                                .collect(Collectors.toSet()));
        PlatformGamesPage body = mapper.toResponse(result, scoreBatch);
        String entityTag = conditionalRequests.strongEntityTag(body);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
        headers.setETag(entityTag);
        if (conditionalRequests.matches(ifNoneMatch, entityTag)) {
            return ResponseEntity.status(304).headers(headers).build();
        }

        metrics.recordPage(requested, body.getItems().size());
        return ResponseEntity.ok().headers(headers).body(body);
    }

    /** Trimmed, without blanks and de-duplicated in first-seen order. */
    private static List<String> normalized(Set<String> values) {
        if (values == null) {
            return List.of();
        }
        return List.copyOf(
                values.stream()
                        .map(String::trim)
                        .filter(value -> !value.isEmpty())
                        .collect(Collectors.toCollection(LinkedHashSet::new)));
    }
}
