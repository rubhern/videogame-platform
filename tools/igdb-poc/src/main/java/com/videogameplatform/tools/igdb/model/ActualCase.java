package com.videogameplatform.tools.igdb.model;

import java.time.Instant;
import java.util.List;

public record ActualCase(
        String caseId,
        boolean found,
        Long providerId,
        String title,
        String type,
        String parentTitle,
        List<String> alternativeTitles,
        List<ActualRelease> releases,
        boolean coverAvailable,
        boolean genreAvailable,
        boolean companyAvailable,
        Instant providerUpdatedAt,
        Instant synchronizedAt,
        String provenance,
        int candidateCount,
        int exactTitleMatchCount,
        String normalizationError) {

    public static ActualCase notFound(String caseId, int candidateCount, Instant synchronizedAt) {
        return new ActualCase(
                caseId, false, null, "", "", "", List.of(), List.of(), false, false,
                false, null, synchronizedAt, "IGDB", candidateCount, 0, "");
    }
}
