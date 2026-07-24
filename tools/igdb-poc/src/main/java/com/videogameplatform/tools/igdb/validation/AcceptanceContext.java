package com.videogameplatform.tools.igdb.validation;

import com.videogameplatform.tools.igdb.model.ExecutionStats;

public record AcceptanceContext(
        String executionMode,
        double configuredRequestsPerSecond,
        int expectedCaseCount,
        int actualCaseCount,
        int securityLeakCount,
        ExecutionStats executionStats) {

    public boolean authenticatedRun() {
        return "run".equals(executionMode);
    }
}
