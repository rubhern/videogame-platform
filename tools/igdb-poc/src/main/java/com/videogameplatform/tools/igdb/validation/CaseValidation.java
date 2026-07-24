package com.videogameplatform.tools.igdb.validation;

import java.util.List;

public record CaseValidation(
        String caseId,
        String category,
        String criticality,
        Long providerId,
        String selectedTitle,
        List<FieldCheck> checks,
        CaseOutcome outcome) {
}
