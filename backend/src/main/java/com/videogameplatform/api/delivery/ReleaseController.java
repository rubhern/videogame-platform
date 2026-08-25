package com.videogameplatform.api.delivery;

import com.videogameplatform.api.generated.ReleasesApi;
import com.videogameplatform.api.generated.model.ReleasePage;
import com.videogameplatform.api.generated.model.ReleaseView;
import com.videogameplatform.catalogue.application.BrowseReleasesResult;
import com.videogameplatform.catalogue.application.BrowseReleasesUseCase;
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

    ReleaseController(
            BrowseReleasesUseCase useCase,
            ReleaseApiMapper mapper,
            ConditionalRequestSupport conditionalRequests,
            ReleaseApiMetrics metrics,
            ReleaseHttpProperties properties) {
        this.useCase = useCase;
        this.mapper = mapper;
        this.conditionalRequests = conditionalRequests;
        this.metrics = metrics;
        this.properties = properties;
    }

    @Override
    public ResponseEntity<ReleasePage> listReleases(
            ReleaseView view,
            String platformId,
            String regionId,
            Integer page,
            Integer pageSize,
            String ifNoneMatch) {
        long startedAt = metrics.start();
        try {
            BrowseReleasesResult result =
                    useCase.browse(
                            new BrowseReleasesUseCase.Query(
                                    toApplicationView(view), platformId, regionId, page, pageSize));
            ReleasePage body = mapper.toResponse(result);
            String entityTag = conditionalRequests.strongEntityTag(body);

            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.CACHE_CONTROL, properties.cacheControl());
            headers.setETag(entityTag);
            if (conditionalRequests.matches(ifNoneMatch, entityTag)) {
                metrics.complete(
                        view.getValue(), ReleaseApiMetrics.Outcome.NOT_MODIFIED, null, startedAt);
                return ResponseEntity.status(304).headers(headers).build();
            }

            ReleaseApiMetrics.Outcome outcome =
                    body.getItems().isEmpty()
                            ? ReleaseApiMetrics.Outcome.EMPTY
                            : ReleaseApiMetrics.Outcome.SUCCESS;
            metrics.complete(view.getValue(), outcome, body.getItems().size(), startedAt);
            return ResponseEntity.ok().headers(headers).body(body);
        } catch (RuntimeException exception) {
            metrics.failure(view == null ? null : view.getValue(), exception, startedAt);
            throw exception;
        }
    }

    private static BrowseReleasesUseCase.View toApplicationView(ReleaseView view) {
        return switch (view) {
            case recent -> BrowseReleasesUseCase.View.RECENT;
            case upcoming -> BrowseReleasesUseCase.View.UPCOMING;
        };
    }
}
