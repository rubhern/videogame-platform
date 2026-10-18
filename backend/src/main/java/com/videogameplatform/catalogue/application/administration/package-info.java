/**
 * Application contract for catalogue operator commands: requesting one bounded synchronization.
 *
 * <p>A request validates its interval and takes the single synchronization ownership; the run
 * itself continues outside the caller. Nothing here exposes the synchronization internals, the
 * provider or how the run is executed, so inbound delivery may depend on it without reaching
 * either.
 */
@org.springframework.modulith.NamedInterface("administration")
package com.videogameplatform.catalogue.application.administration;
