/**
 * Read-only application contract for catalogue acquisition evidence: durable synchronization
 * coverage and the bounded state of retained synchronization runs.
 *
 * <p>It reads local evidence only. Nothing here starts, schedules or reaches a synchronization or
 * the provider, so inbound delivery may depend on it without reaching either.
 */
@org.springframework.modulith.NamedInterface("acquisition")
package com.videogameplatform.catalogue.application.acquisition;
