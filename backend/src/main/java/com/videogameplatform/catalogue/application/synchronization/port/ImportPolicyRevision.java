package com.videogameplatform.catalogue.application.synchronization.port;

/**
 * Which revision of the import policy decided a scan. A deferred work leaves no local trace, so a
 * period scanned under an earlier revision may still hold works the current policy would import;
 * coverage records the revision so that skipping a covered period never hides them.
 */
public final class ImportPolicyRevision {

    /**
     * Raise this whenever the set of importable work types grows. 1: until #456. 2: ports and
     * expanded games.
     */
    public static final int CURRENT = 2;

    private ImportPolicyRevision() {}
}
