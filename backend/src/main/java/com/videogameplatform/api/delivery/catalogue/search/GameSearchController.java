package com.videogameplatform.api.delivery.catalogue.search;

import com.videogameplatform.api.delivery.ConditionalRequestSupport;
import com.videogameplatform.api.delivery.catalogue.details.GameDetailsEndpoint;
import com.videogameplatform.api.generated.CatalogueApi;
import com.videogameplatform.api.generated.model.GameDetails;
import com.videogameplatform.api.generated.model.GameSearchPage;
import com.videogameplatform.catalogue.application.search.SearchCatalogueResult;
import com.videogameplatform.catalogue.application.search.SearchCatalogueUseCase;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Generated Catalogue HTTP adapter for UC-002 and UC-003. */
@RestController
@RequestMapping("/api/v1")
public class GameSearchController implements CatalogueApi {

    private final GameDetailsEndpoint details;
    private final SearchCatalogueUseCase useCase;
    private final GameSearchApiMapper mapper;
    private final ConditionalRequestSupport conditionalRequests;
    private final GameSearchApiMetrics metrics;
    private final GameSearchHttpProperties properties;

    GameSearchController(
            SearchCatalogueUseCase useCase,
            GameSearchApiMapper mapper,
            ConditionalRequestSupport conditionalRequests,
            GameSearchApiMetrics metrics,
            GameSearchHttpProperties properties,
            GameDetailsEndpoint details) {
        this.details = details;
        this.useCase = useCase;
        this.mapper = mapper;
        this.conditionalRequests = conditionalRequests;
        this.metrics = metrics;
        this.properties = properties;
    }

    @Override
    public ResponseEntity<GameSearchPage> searchGames(
            String q, Integer page, Integer pageSize, String ifNoneMatch) {
        SearchCatalogueResult result =
                useCase.search(new SearchCatalogueUseCase.Query(q, page, pageSize));
        GameSearchPage body = mapper.toResponse(result);
        String entityTag = conditionalRequests.strongEntityTag(body);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CACHE_CONTROL, properties.cacheControl());
        headers.setETag(entityTag);
        if (conditionalRequests.matches(ifNoneMatch, entityTag)) {
            return ResponseEntity.status(304).headers(headers).build();
        }

        metrics.recordResult(body.getPage().getTotalItems());
        return ResponseEntity.ok().headers(headers).body(body);
    }

    @Override
    public ResponseEntity<GameDetails> getGame(String gameId, String ifNoneMatch) {
        return details.get(gameId, ifNoneMatch);
    }
}
