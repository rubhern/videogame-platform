package com.videogameplatform.catalogue.adapter.persistence.releases;

import com.videogameplatform.catalogue.adapter.persistence.CatalogueCoverReferenceRowMapper;
import com.videogameplatform.catalogue.adapter.persistence.ReleaseDateRowMapper;
import com.videogameplatform.catalogue.application.CatalogueDataInvalidException;
import com.videogameplatform.catalogue.application.cover.port.CatalogueCoverReference;
import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort.Item;
import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort.ReleaseRow;
import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort.Taxonomy;
import com.videogameplatform.catalogue.domain.ReleaseStatus;
import com.videogameplatform.catalogue.domain.ReviewStatus;
import com.videogameplatform.catalogue.domain.SourceKind;
import com.videogameplatform.catalogue.domain.VerificationLevel;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Folds the bounded release rows of the page back into one item per game, keeping SQL order. */
final class ReleaseGroupPageMapper {

    private ReleaseGroupPageMapper() {}

    /**
     * PostgreSQL already grouped, ordered and paged the games and bounded the releases under each
     * one. This only collapses the join rows into one item per game while preserving that order.
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
                                    CatalogueCoverReferenceRowMapper.map(resultSet));
                    items.put(gameId, item);
                }
                item.releases.add(releaseRow(resultSet, gameId));
            }
        } catch (IllegalArgumentException | IllegalStateException | NullPointerException exception) {
            throw new CatalogueDataInvalidException(exception);
        }
        return items.values().stream().map(MutableItem::toItem).toList();
    }

    private static ReleaseRow releaseRow(ResultSet resultSet, String gameId) throws SQLException {
        return new ReleaseRow(
                resultSet.getString("release_id"),
                gameId,
                new Taxonomy(
                        resultSet.getString("platform_id"), resultSet.getString("platform_name")),
                new Taxonomy(resultSet.getString("region_id"), resultSet.getString("region_name")),
                ReleaseDateRowMapper.map(resultSet),
                ReleaseStatus.fromValue(resultSet.getString("release_status")),
                SourceKind.fromValue(resultSet.getString("source_kind")),
                resultSet.getString("source_name"),
                resultSet.getString("source_entity_type"),
                instant(resultSet, "provider_updated_at"),
                instant(resultSet, "last_synchronized_at"),
                instant(resultSet, "last_verified_at"),
                VerificationLevel.fromValue(resultSet.getString("verification_level")),
                ReviewStatus.fromValue(resultSet.getString("review_status")));
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static final class MutableItem {

        private final String gameId;
        private final String slug;
        private final String canonicalTitle;
        private final CatalogueCoverReference cover;
        private final List<ReleaseRow> releases = new ArrayList<>();

        private MutableItem(
                String gameId,
                String slug,
                String canonicalTitle,
                CatalogueCoverReference cover) {
            this.gameId = gameId;
            this.slug = slug;
            this.canonicalTitle = canonicalTitle;
            this.cover = cover;
        }

        private Item toItem() {
            return new Item(gameId, slug, canonicalTitle, cover, List.copyOf(releases));
        }
    }
}
