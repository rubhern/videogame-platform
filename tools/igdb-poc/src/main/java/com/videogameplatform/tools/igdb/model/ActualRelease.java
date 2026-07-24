package com.videogameplatform.tools.igdb.model;

public record ActualRelease(
        String platform,
        String region,
        String releaseDate,
        String datePrecision,
        String status,
        String rawStatus) {
}
