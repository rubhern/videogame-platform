package com.videogameplatform.identity.application;

/** Readiness of the protected automatic executor; never executes work on a web request. */
@FunctionalInterface
public interface DeletionExecutorAvailability {
    boolean available();
}
