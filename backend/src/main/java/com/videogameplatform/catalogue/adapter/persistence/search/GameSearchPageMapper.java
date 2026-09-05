package com.videogameplatform.catalogue.adapter.persistence.search;

import com.videogameplatform.catalogue.adapter.persistence.CatalogueCoverReferenceRowMapper;
import com.videogameplatform.catalogue.adapter.persistence.ReleaseDateRowMapper;
import com.videogameplatform.catalogue.application.CatalogueDataInvalidException;
import com.videogameplatform.catalogue.application.cover.port.CatalogueCoverReference;
import com.videogameplatform.catalogue.application.search.port.GameSearchReadPort.Item;
import com.videogameplatform.catalogue.application.search.port.GameSearchReadPort.ReleaseContext;
import com.videogameplatform.catalogue.application.search.port.GameSearchReadPort.Taxonomy;
import com.videogameplatform.catalogue.domain.ReleaseStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reconstructs only the bounded page; matching, ranking and pagination stay in SQL. */
final class GameSearchPageMapper {

    private GameSearchPageMapper() {}

    /**
     * Collapses the bounded release-context rows back into one item per game while preserving
     * the deterministic order PostgreSQL already applied.
     */
    static List<Item> map(ResultSet resultSet) throws SQLException {
        Map<String, MutableItem> items = new LinkedHashMap<>();
        try {
            while (resultSet.next()) {
                String gameId = resultSet.getString("game_id");
                MutableItem item = items.get(gameId);
                if (item == null) {
                    item =
                            new MutableItem(
                                    gameId,
                                    resultSet.getString("slug"),
                                    resultSet.getString("canonical_title"),
                                    resultSet.getString("matched_alias"),
                                    CatalogueCoverReferenceRowMapper.map(resultSet));
                    items.put(gameId, item);
                }
                if (resultSet.getString("platform_id") != null) {
                    item.releaseContext.add(releaseContext(resultSet));
                }
            }
        } catch (IllegalArgumentException
                | IllegalStateException
                | NullPointerException exception) {
            throw new CatalogueDataInvalidException(exception);
        }
        return items.values().stream().map(MutableItem::toItem).toList();
    }

    private static ReleaseContext releaseContext(ResultSet resultSet) throws SQLException {
        return new ReleaseContext(
                new Taxonomy(
                        resultSet.getString("platform_id"), resultSet.getString("platform_name")),
                new Taxonomy(resultSet.getString("region_id"), resultSet.getString("region_name")),
                ReleaseDateRowMapper.map(resultSet),
                ReleaseStatus.fromValue(resultSet.getString("release_status")),
                instant(resultSet));
    }

    private static java.time.Instant instant(ResultSet resultSet) throws SQLException {
        OffsetDateTime value = resultSet.getObject("last_synchronized_at", OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static final class MutableItem {

        private final String gameId;
        private final String slug;
        private final String canonicalTitle;
        private final String matchedAlias;
        private final CatalogueCoverReference cover;
        private final List<ReleaseContext> releaseContext = new ArrayList<>();

        private MutableItem(
                String gameId,
                String slug,
                String canonicalTitle,
                String matchedAlias,
                CatalogueCoverReference cover) {
            this.gameId = gameId;
            this.slug = slug;
            this.canonicalTitle = canonicalTitle;
            this.matchedAlias = matchedAlias;
            this.cover = cover;
        }

        private Item toItem() {
            return new Item(
                    gameId, slug, canonicalTitle, matchedAlias, cover, List.copyOf(releaseContext));
        }
    }
}
