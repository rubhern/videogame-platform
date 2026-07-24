package com.videogameplatform.tools.igdb.sample;

import java.net.URI;

public record ExpectedCase(
        String caseId,
        String category,
        String searchQuery,
        String expectedTitle,
        String expectedType,
        String expectedParentTitle,
        String expectedPlatform,
        String expectedRegion,
        String expectedReleaseDate,
        String expectedDatePrecision,
        String expectedStatus,
        String expectedAlternativeTitle,
        String criticality,
        URI evidence) {

    public boolean isBlocking() {
        return "blocking".equalsIgnoreCase(criticality);
    }

    public boolean isAlternativeTitleCase() {
        return !expectedAlternativeTitle.isBlank();
    }
}
