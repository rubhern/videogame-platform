package com.videogameplatform.catalogue.adapter.provider.igdb;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.regex.Pattern;

/** Bounded IGDB queries. Game IDs provide page-key order within a fixed release-date window. */
final class IgdbQueries {

    static final String GAMES_ENDPOINT = "games";
    static final String RELEASE_DATES_ENDPOINT = "release_dates";

    private static final Pattern PROVIDER_ID = Pattern.compile("[0-9]{1,19}");
    private static final String WORK_FIELDS =
            "fields id,name,url,created_at,updated_at,game_type.type,game_status.status,"
                    + "cover.image_id;";

    private IgdbQueries() {}

    static String releaseGames(LocalDate fromDate, LocalDate toDate, long afterGameId, int limit) {
        long from = fromDate.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        long to = toDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        return "fields id,game; where date >= "
                + from
                + " & date < "
                + to
                + " & game > "
                + afterGameId
                + "; sort game asc; limit "
                + limit
                + ";";
    }

    static String worksById(List<String> providerIds) {
        return WORK_FIELDS
                + " where id = ("
                + identifierList(providerIds)
                + ");"
                + " sort id asc; limit "
                + providerIds.size()
                + ";";
    }

    static String releaseDatesForGames(List<String> providerIds, int limit) {
        return "fields game,id,date,y,m,d,date_format.format,release_region.region,status.name,"
                + "platform.slug,platform.name;"
                + " where game = ("
                + identifierList(providerIds)
                + ");"
                + " sort id asc; limit "
                + limit
                + ";";
    }

    private static String identifierList(List<String> providerIds) {
        if (providerIds.isEmpty()) {
            throw new IllegalArgumentException("An IGDB identifier list cannot be empty");
        }
        return String.join(",", providerIds.stream().map(IgdbQueries::numeric).toList());
    }

    /** A provider identifier is always numeric; anything else never reaches a query. */
    private static String numeric(String providerId) {
        if (providerId == null || !PROVIDER_ID.matcher(providerId).matches()) {
            throw new IllegalArgumentException("Unsupported IGDB game identifier");
        }
        return providerId;
    }
}
