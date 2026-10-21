package com.videogameplatform.api.delivery.catalogue.release;

import com.videogameplatform.api.delivery.ConditionalRequestSupport;
import com.videogameplatform.api.generated.ReleaseCalendarApi;
import com.videogameplatform.api.generated.model.ReleaseCalendar;
import com.videogameplatform.api.generated.model.ReleaseCalendarDay;
import com.videogameplatform.api.generated.model.ReleaseCalendarNavigation;
import com.videogameplatform.api.generated.model.ReleaseWindow;
import com.videogameplatform.catalogue.application.releases.BrowseReleaseCalendarUseCase;
import com.videogameplatform.catalogue.application.releases.ReleaseCalendarResult;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public HTTP adapter for UC-016; it reads local counts only and never calls a provider. */
@RestController
@RequestMapping("/api/v1")
public class ReleaseCalendarController implements ReleaseCalendarApi {

    private final BrowseReleaseCalendarUseCase useCase;
    private final ConditionalRequestSupport conditionalRequests;
    private final ReleaseCalendarApiMetrics metrics;
    private final ReleaseHttpProperties properties;

    public ReleaseCalendarController(
            BrowseReleaseCalendarUseCase useCase,
            ConditionalRequestSupport conditionalRequests,
            ReleaseCalendarApiMetrics metrics,
            ReleaseHttpProperties properties) {
        this.useCase = useCase;
        this.conditionalRequests = conditionalRequests;
        this.metrics = metrics;
        this.properties = properties;
    }

    /** The contract pattern has already rejected a malformed month. */
    @Override
    public ResponseEntity<ReleaseCalendar> getReleaseCalendar(
            String month, Set<String> platformIds, Set<String> regionIds, String ifNoneMatch) {
        ReleaseCalendarResult result =
                useCase.browse(
                        new BrowseReleaseCalendarUseCase.Query(
                                Optional.ofNullable(month).map(YearMonth::parse),
                                toList(platformIds),
                                toList(regionIds)));
        ReleaseCalendar body = toResponse(result);
        String entityTag = conditionalRequests.strongEntityTag(body);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CACHE_CONTROL, properties.cacheControl());
        headers.setETag(entityTag);
        if (conditionalRequests.matches(ifNoneMatch, entityTag)) {
            return ResponseEntity.status(304).headers(headers).build();
        }

        metrics.recordCalendar(result);
        return ResponseEntity.ok().headers(headers).body(body);
    }

    private static List<String> toList(Set<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static ReleaseCalendar toResponse(ReleaseCalendarResult result) {
        ReleaseCalendarResult.Navigation source = result.navigation();
        ReleaseCalendarNavigation navigation =
                new ReleaseCalendarNavigation(source.first().toString(), source.last().toString());
        source.previous().ifPresent(month -> navigation.setPrevious(month.toString()));
        source.next().ifPresent(month -> navigation.setNext(month.toString()));
        return new ReleaseCalendar(
                result.month().toString(),
                result.evaluatedOn(),
                new ReleaseWindow(result.window().from(), result.window().to()),
                navigation,
                ReleaseApiMapper.toActiveFilters(result.activeFilters()),
                ReleaseApiMapper.toAvailableFilters(result.availableFilters()),
                result.days().stream()
                        .map(
                                day ->
                                        new ReleaseCalendarDay(
                                                day.date(), day.gameCount(), day.totalHype()))
                        .toList());
    }
}
