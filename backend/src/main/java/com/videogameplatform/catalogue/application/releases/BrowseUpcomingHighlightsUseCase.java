package com.videogameplatform.catalogue.application.releases;

import java.util.List;

/**
 * Public application contract for UC-020 (Próximos destacados): the most anticipated games still to
 * come on one platform scope inside the moving six-calendar-month horizon.
 */
public interface BrowseUpcomingHighlightsUseCase {

    /**
     * @throws ReleaseQueryValidationException with {@code PLATFORM_NOT_SUPPORTED} when the scope
     *     is empty or a platform is not part of the local taxonomy
     */
    UpcomingHighlightsResult browse(Query query);

    /**
     * The product platform identifiers of one platform or one platform family, combined with OR.
     * The visitor never supplies the evaluation date or the horizon.
     */
    record Query(List<String> platformIds) {
        public Query {
            platformIds = List.copyOf(platformIds);
        }
    }
}
