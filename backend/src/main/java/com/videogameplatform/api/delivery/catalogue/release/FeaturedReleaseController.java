package com.videogameplatform.api.delivery.catalogue.release;

import com.videogameplatform.api.delivery.ApiRequestException;
import com.videogameplatform.api.delivery.ConditionalRequestSupport;
import com.videogameplatform.api.delivery.catalogue.CatalogueSummaryMapper;
import com.videogameplatform.api.delivery.catalogue.DiscoveryScoresReader;
import com.videogameplatform.api.generated.FeaturedReleasesApi;
import com.videogameplatform.api.generated.model.Attribution;
import com.videogameplatform.api.generated.model.FallbackFeaturedImage;
import com.videogameplatform.api.generated.model.FeaturedGranularity;
import com.videogameplatform.api.generated.model.FeaturedImage;
import com.videogameplatform.api.generated.model.FeaturedLogo;
import com.videogameplatform.api.generated.model.FeaturedNavigation;
import com.videogameplatform.api.generated.model.FeaturedReleaseItem;
import com.videogameplatform.api.generated.model.FeaturedReleases;
import com.videogameplatform.api.generated.model.FeaturedSelection;
import com.videogameplatform.api.generated.model.Genre;
import com.videogameplatform.api.generated.model.ProblemCode;
import com.videogameplatform.api.generated.model.ProviderFeaturedImage;
import com.videogameplatform.api.generated.model.ReleaseItem;
import com.videogameplatform.api.generated.model.ReleaseWindow;
import com.videogameplatform.catalogue.application.cover.CatalogueCover;
import com.videogameplatform.catalogue.application.releases.BrowseFeaturedReleasesUseCase;
import com.videogameplatform.catalogue.application.releases.BrowseFeaturedReleasesUseCase.Granularity;
import com.videogameplatform.catalogue.application.releases.FeaturedReleasesResult;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
    private static final String WEEK_POINTER = "/query/week";
    private static final String MONTH_POINTER = "/query/month";

    private final BrowseFeaturedReleasesUseCase useCase;
    private final ReleaseApiMapper releases;
    private final ConditionalRequestSupport conditionalRequests;
    private final FeaturedReleaseApiMetrics metrics;
    private final ReleaseHttpProperties properties;
    private final DiscoveryScoresReader scores;

    public FeaturedReleaseController(
            BrowseFeaturedReleasesUseCase useCase,
            ReleaseApiMapper releases,
            ConditionalRequestSupport conditionalRequests,
            FeaturedReleaseApiMetrics metrics,
            ReleaseHttpProperties properties,
            DiscoveryScoresReader scores) {
        this.useCase = useCase;
        this.releases = releases;
        this.conditionalRequests = conditionalRequests;
        this.metrics = metrics;
        this.properties = properties;
        this.scores = scores;
    }

    @Override
    public ResponseEntity<FeaturedReleases> getFeaturedReleases(
            FeaturedGranularity granularity,
            String week,
            String month,
            Set<String> platformIds,
            String ifNoneMatch) {
        BrowseFeaturedReleasesUseCase.Query query =
                withPlatforms(toQuery(granularity, week, month), platformIds);
        FeaturedReleasesResult result = useCase.browse(query);
        FeaturedReleases body = toResponse(result);
        var scoreBatch =
                scores.read(
                        body.getItems().stream()
                                .map(FeaturedReleaseItem::getGameId)
                                .collect(Collectors.toSet()));
        body.getItems().forEach(item -> item.setScores(scoreBatch.get(item.getGameId())));
        String entityTag = conditionalRequests.strongEntityTag(body);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CACHE_CONTROL, properties.cacheControl());
        headers.setETag(entityTag);
        if (conditionalRequests.matches(ifNoneMatch, entityTag)) {
            return ResponseEntity.status(304).headers(headers).build();
        }

        metrics.recordSelection(
                result.granularity(),
                result.selection(),
                query.periodStart().isPresent(),
                result.items());
        return ResponseEntity.ok().headers(headers).body(body);
    }

    private static BrowseFeaturedReleasesUseCase.Query withPlatforms(
            BrowseFeaturedReleasesUseCase.Query query, Set<String> platformIds) {
        return new BrowseFeaturedReleasesUseCase.Query(
                query.granularity(),
                query.periodStart(),
                platformIds == null ? List.of() : List.copyOf(platformIds));
    }

    /**
     * The contract patterns have already rejected a malformed week or month. A request names one
     * granularity: its own period parameter, or none for the current period. Omitted, the
     * granularity follows the period parameter and otherwise stays the month, as before weeks.
     */
    private static BrowseFeaturedReleasesUseCase.Query toQuery(
            FeaturedGranularity granularity, String week, String month) {
        if (week != null && month != null) {
            throw new ApiRequestException(ProblemCode.FILTER_INVALID, WEEK_POINTER);
        }
        Granularity selected;
        if (granularity != null) {
            selected =
                    switch (granularity) {
                        case week -> Granularity.WEEK;
                        case month -> Granularity.MONTH;
                    };
        } else if (week != null) {
            selected = Granularity.WEEK;
        } else {
            selected = Granularity.MONTH;
        }
        return switch (selected) {
            case WEEK -> {
                if (month != null) {
                    throw new ApiRequestException(ProblemCode.FILTER_INVALID, MONTH_POINTER);
                }
                yield new BrowseFeaturedReleasesUseCase.Query(
                        selected, Optional.ofNullable(week).map(FeaturedReleaseController::monday));
            }
            case MONTH -> {
                if (week != null) {
                    throw new ApiRequestException(ProblemCode.FILTER_INVALID, WEEK_POINTER);
                }
                yield new BrowseFeaturedReleasesUseCase.Query(
                        selected,
                        Optional.ofNullable(month).map(value -> YearMonth.parse(value).atDay(1)));
            }
        };
    }

    private static LocalDate monday(String week) {
        return IsoWeekParameter.monday(week)
                .orElseThrow(
                        () -> new ApiRequestException(ProblemCode.FILTER_INVALID, WEEK_POINTER));
    }

    private static String period(Granularity granularity, LocalDate start) {
        return switch (granularity) {
            case WEEK -> IsoWeekParameter.format(start);
            case MONTH -> YearMonth.from(start).toString();
        };
    }

    private FeaturedReleases toResponse(FeaturedReleasesResult result) {
        Granularity granularity = result.granularity();
        FeaturedNavigation navigation = new FeaturedNavigation();
        result.navigation()
                .previous()
                .ifPresent(start -> navigation.setPrevious(period(granularity, start)));
        result.navigation()
                .next()
                .ifPresent(start -> navigation.setNext(period(granularity, start)));
        FeaturedReleases response =
                new FeaturedReleases(
                        switch (granularity) {
                            case WEEK -> FeaturedGranularity.week;
                            case MONTH -> FeaturedGranularity.month;
                        },
                        result.evaluatedOn(),
                        new ReleaseWindow(result.window().from(), result.window().to()),
                        navigation,
                        toSelection(result.selection()),
                        result.items().stream().map(this::toItem).toList());
        String current = period(granularity, result.window().from());
        switch (granularity) {
            case WEEK -> response.setWeek(current);
            case MONTH -> response.setMonth(current);
        }
        return response;
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
