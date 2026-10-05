package com.videogameplatform.api.delivery.catalogue.release;

import com.videogameplatform.api.delivery.ConditionalRequestSupport;
import com.videogameplatform.api.delivery.catalogue.CatalogueSummaryMapper;
import com.videogameplatform.api.generated.FeaturedReleasesApi;
import com.videogameplatform.api.generated.model.Attribution;
import com.videogameplatform.api.generated.model.FallbackFeaturedImage;
import com.videogameplatform.api.generated.model.FeaturedImage;
import com.videogameplatform.api.generated.model.FeaturedLogo;
import com.videogameplatform.api.generated.model.FeaturedReleaseItem;
import com.videogameplatform.api.generated.model.FeaturedReleases;
import com.videogameplatform.api.generated.model.FeaturedSelection;
import com.videogameplatform.api.generated.model.Genre;
import com.videogameplatform.api.generated.model.ProviderFeaturedImage;
import com.videogameplatform.api.generated.model.ReleaseItem;
import com.videogameplatform.api.generated.model.ReleaseWindow;
import com.videogameplatform.catalogue.application.cover.CatalogueCover;
import com.videogameplatform.catalogue.application.releases.BrowseFeaturedReleasesUseCase;
import com.videogameplatform.catalogue.application.releases.FeaturedReleasesResult;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public HTTP adapter for UC-010; it reads local state only and never calls a provider. */
@RestController
@RequestMapping("/api/v1")
public class FeaturedReleaseController implements FeaturedReleasesApi {

    private static final String FALLBACK_IMAGE_PATH = "/assets/featured/fallback.svg";

    private final BrowseFeaturedReleasesUseCase useCase;
    private final ReleaseApiMapper releases;
    private final ConditionalRequestSupport conditionalRequests;
    private final FeaturedReleaseApiMetrics metrics;
    private final ReleaseHttpProperties properties;

    public FeaturedReleaseController(
            BrowseFeaturedReleasesUseCase useCase,
            ReleaseApiMapper releases,
            ConditionalRequestSupport conditionalRequests,
            FeaturedReleaseApiMetrics metrics,
            ReleaseHttpProperties properties) {
        this.useCase = useCase;
        this.releases = releases;
        this.conditionalRequests = conditionalRequests;
        this.metrics = metrics;
        this.properties = properties;
    }

    @Override
    public ResponseEntity<FeaturedReleases> getFeaturedReleases(String month, String ifNoneMatch) {
        // The contract pattern has already rejected a malformed month before this point.
        Optional<YearMonth> requested = Optional.ofNullable(month).map(YearMonth::parse);
        FeaturedReleasesResult result =
                useCase.browse(new BrowseFeaturedReleasesUseCase.Query(requested));
        FeaturedReleases body = toResponse(result);
        String entityTag = conditionalRequests.strongEntityTag(body);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CACHE_CONTROL, properties.cacheControl());
        headers.setETag(entityTag);
        if (conditionalRequests.matches(ifNoneMatch, entityTag)) {
            return ResponseEntity.status(304).headers(headers).build();
        }

        metrics.recordSelection(result.selection(), requested.isPresent(), result.items());
        return ResponseEntity.ok().headers(headers).body(body);
    }

    private FeaturedReleases toResponse(FeaturedReleasesResult result) {
        return new FeaturedReleases(
                result.month().toString(),
                result.evaluatedOn(),
                new ReleaseWindow(result.window().from(), result.window().to()),
                toSelection(result.selection()),
                result.items().stream().map(this::toItem).toList());
    }

    private FeaturedReleaseItem toItem(FeaturedReleasesResult.Item item) {
        ReleaseItem release = releases.toItem(item.release());
        FeaturedReleaseItem response =
                new FeaturedReleaseItem(
                        release.getGameId(),
                        release.getSlug(),
                        release.getCanonicalTitle(),
                        release.getPrimaryCover(),
                        toImage(item.image()),
                        release.getReleases());
        item.logo().map(FeaturedReleaseController::toLogo).ifPresent(response::setLogo);
        response.setGenres(
                item.release().genres().stream()
                        .map(term -> new Genre(term.code(), term.name()))
                        .collect(Collectors.toCollection(LinkedHashSet::new)));
        item.summary().map(CatalogueSummaryMapper::toResponse).ifPresent(response::setSummary);
        return response;
    }

    private static FeaturedImage toImage(FeaturedReleasesResult.FeaturedImage source) {
        return switch (source) {
            case FeaturedReleasesResult.FeaturedImage.Provider provider ->
                    new ProviderFeaturedImage(
                            switch (provider.kind()) {
                                case ARTWORK -> ProviderFeaturedImage.KindEnum.ARTWORK;
                                case SCREENSHOT -> ProviderFeaturedImage.KindEnum.SCREENSHOT;
                                case COVER -> ProviderFeaturedImage.KindEnum.COVER;
                            },
                            switch (provider.presentation()) {
                                case FILL -> ProviderFeaturedImage.PresentationEnum.FILL;
                                case CONTAIN -> ProviderFeaturedImage.PresentationEnum.CONTAIN;
                            },
                            provider.url(),
                            provider.compactUrl(),
                            provider.alternativeText(),
                            toAttribution(provider.attribution()));
            case FeaturedReleasesResult.FeaturedImage.Product product ->
                    new FallbackFeaturedImage(
                            FallbackFeaturedImage.KindEnum.FALLBACK,
                            FallbackFeaturedImage.PresentationEnum.FILL,
                            FALLBACK_IMAGE_PATH,
                            FALLBACK_IMAGE_PATH,
                            product.alternativeText(),
                            null);
        };
    }

    private static FeaturedLogo toLogo(FeaturedReleasesResult.FeaturedLogo source) {
        return new FeaturedLogo(
                source.url(), source.alternativeText(), toAttribution(source.attribution()));
    }

    private static Attribution toAttribution(CatalogueCover.Attribution source) {
        return new Attribution(source.label(), source.sourceUrl());
    }

    private static FeaturedSelection toSelection(FeaturedReleasesResult.Selection source) {
        return switch (source) {
            case FeaturedReleasesResult.Selection.Ranked ranked ->
                    new FeaturedSelection(FeaturedSelection.StatusEnum.RANKED)
                            .popularityFreshness(
                                    switch (ranked.popularityFreshness()) {
                                        case FRESH ->
                                                FeaturedSelection.PopularityFreshnessEnum.FRESH;
                                        case STALE ->
                                                FeaturedSelection.PopularityFreshnessEnum.STALE;
                                    })
                            .popularityObservedAt(
                                    ranked.popularityObservedAt().atOffset(ZoneOffset.UTC));
            case FeaturedReleasesResult.Selection.PopularityUnavailable _ ->
                    new FeaturedSelection(FeaturedSelection.StatusEnum.POPULARITY_UNAVAILABLE);
            case FeaturedReleasesResult.Selection.NoQualifyingReleases _ ->
                    new FeaturedSelection(FeaturedSelection.StatusEnum.NO_QUALIFYING_RELEASES);
        };
    }
}
