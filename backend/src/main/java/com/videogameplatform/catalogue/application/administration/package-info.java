/**
 * Application contract for catalogue operator commands: requesting one bounded synchronization,
 * and stopping or abandoning one identified run.
 *
 * <p>A request validates its interval and takes the single synchronization ownership; the run
 * itself continues outside the caller. A stop is recorded for the run's worker to act on at its
 * next safe point, and an abandonment fences a run whose worker no longer proves it is alive.
 * Nothing here exposes the synchronization internals, the provider or how the run is executed,
 * so inbound delivery may depend on it without reaching either.
 */
@org.springframework.modulith.NamedInterface("administration")
package com.videogameplatform.catalogue.application.administration;
