package com.videogameplatform.catalogue.adapter.persistence;

import com.videogameplatform.catalogue.domain.ReleaseDate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;

/** Reads the persisted closed date/precision variant shared by every catalogue read. */
public final class ReleaseDateRowMapper {

    private ReleaseDateRowMapper() {}

    public static ReleaseDate map(ResultSet resultSet) throws SQLException {
        return switch (resultSet.getString("date_precision")) {
            case "day" -> new ReleaseDate.Day(resultSet.getObject("exact_date", LocalDate.class));
            case "month" ->
                    new ReleaseDate.Month(
                            YearMonth.of(
                                    resultSet.getInt("release_year"),
                                    resultSet.getInt("release_month")));
            case "quarter" ->
                    new ReleaseDate.Quarter(
                            resultSet.getInt("release_year"), resultSet.getInt("release_quarter"));
            case "year" -> new ReleaseDate.YearOnly(Year.of(resultSet.getInt("release_year")));
            case "unknown" -> new ReleaseDate.Unknown();
            default -> throw new IllegalArgumentException("Unsupported persisted date precision");
        };
    }
}
