package com.videogameplatform.catalogue.adapter.persistence;

import com.videogameplatform.catalogue.application.details.GameDetailsResult;
import com.videogameplatform.catalogue.application.releases.BrowseReleasesResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

/** The existing summary representation, including last-valid local Spanish translations. */
public final class CatalogueSummaryRowMapper {
    private CatalogueSummaryRowMapper() {}

    public static GameDetailsResult.Summary map(ResultSet rs) throws SQLException {
        String source = rs.getString("summary_source_kind");
        return new GameDetailsResult.Summary(
                rs.getString("summary_kind"),
                rs.getString("translated_text") == null
                        ? rs.getString("summary_text")
                        : rs.getString("translated_text"),
                rs.getString("translated_text") == null ? rs.getString("summary_language") : "es",
                source == null
                        ? null
                        : new BrowseReleasesResult.Provenance(
                                BrowseReleasesResult.Source.valueOf(
                                        source.toUpperCase(Locale.ROOT)),
                                rs.getString("summary_source_name"),
                                rs.getString("summary_source_entity_type")),
                rs.getString("translated_text") == null
                        ? null
                        : new GameDetailsResult.Translation(
                                rs.getString("summary_text"),
                                rs.getString("summary_language"),
                                rs.getBoolean("translation_current")));
    }
}
