package com.videogameplatform.catalogue.adapter.persistence;

import com.videogameplatform.catalogue.application.cover.port.CatalogueCoverReference;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Reads the persisted cover columns shared by every catalogue read into one port shape. */
public final class CatalogueCoverReferenceRowMapper {

    private CatalogueCoverReferenceRowMapper() {}

    public static CatalogueCoverReference map(ResultSet resultSet) throws SQLException {
        String alternativeText = resultSet.getString("cover_alternative_text");
        return switch (resultSet.getString("cover_usage_mode")) {
            case "product_owned" ->
                    new CatalogueCoverReference.Product(
                            resultSet.getString("cover_reference"), alternativeText);
            case "provider_cdn_reference" -> {
                String sourceUrl = resultSet.getString("cover_source_url");
                yield sourceUrl == null
                        ? new CatalogueCoverReference.Unavailable()
                        : new CatalogueCoverReference.Provider(
                                resultSet.getString("cover_source"),
                                resultSet.getString("cover_reference"),
                                alternativeText,
                                sourceUrl);
            }
            default -> throw new IllegalArgumentException("Unsupported persisted cover usage mode");
        };
    }
}
