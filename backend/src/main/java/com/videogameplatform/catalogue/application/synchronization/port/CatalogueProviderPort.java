package com.videogameplatform.catalogue.application.synchronization.port;

import com.videogameplatform.catalogue.domain.ReleaseDate;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Outbound port for the approved external catalogue provider.
 *
 * <p>Everything crossing this port is already product vocabulary: typed provider taxonomy
 * references with descriptive names, closed date variants, a closed work-type vocabulary, and an
 * opaque provider reference. Provider transport models and raw payloads stay inside the adapter
 * (EXT-001, EXT-002). The product taxonomy identity these references resolve to is owned by the
 * store, which reuses a known reference or creates the product entity as part of the accepted state.
 */
public interface CatalogueProviderPort {

    /** Provenance name recorded on everything this port produces. */
    String providerName();

    /** Whether provider access is configured; a run reports {@code SYNCHRONIZATION_DISABLED} otherwise. */
    boolean isConfigured();

    /** One bounded page of Games represented by release dates in the inclusive window. */
    ReleasePage releaseGames(LocalDate from, LocalDate to, long afterGameId, int limit);

    /** Complete bounded provider state for the specified games; missing works remain missing. */
    ProviderWorkBatch fetchWorks(List<String> providerIds);

    record ReleasePage(
            List<String> gameIds,
            int inspected,
            long nextGameId,
            boolean completed,
            ProviderCallStatistics statistics) {
        public ReleasePage {
            gameIds = List.copyOf(gameIds);
        }
    }

    record ProviderWorkBatch(List<ProviderWork> works, ProviderCallStatistics statistics) {

        public ProviderWorkBatch {
            works = List.copyOf(works);
        }
    }

    /**
     * One provider work normalized into product vocabulary.
     *
     * <p>{@code providerId} is a typed external reference, never the product identity of a game.
     */
    record ProviderWork(
            String providerId,
            String title,
            ProviderWorkType type,
            Instant providerUpdatedAt,
            Optional<ProviderCover> cover,
            List<ProviderRelease> releases,
            List<ProviderMappingFailure> mappingFailures) {

        public ProviderWork {
            releases = List.copyOf(releases);
            mappingFailures = List.copyOf(mappingFailures);
        }
    }

    /**
     * A normalized commercial Release.
     *
     * <p>Platform and region are typed provider taxonomy references the store resolves to product
     * identity, never provider labels used as identity. An absent region resolves to the product
     * 'unknown' sentinel rather than being guessed. Subscription or promotional availability is not
     * a release and never reaches this record (REL-006).
     */
    record ProviderRelease(
            String providerId,
            ProviderPlatform platform,
            Optional<ProviderRegion> region,
            ReleaseDate date,
            ProviderReleaseSignal signal) {
        public ProviderRelease {
            if (providerId == null || providerId.isBlank()) {
                throw new IllegalArgumentException("A release requires an external identity");
            }
            if (platform == null) {
                throw new IllegalArgumentException("A release requires a platform reference");
            }
            region = region == null ? Optional.empty() : region;
        }
    }

    /**
     * A typed provider platform reference. {@code providerId} is the stable identity; {@code name}
     * and {@code slug} are descriptive metadata that may seed a product display name or code but
     * never establish identity.
     */
    record ProviderPlatform(String providerId, String name, String slug) {
        public ProviderPlatform {
            if (providerId == null || providerId.isBlank()) {
                throw new IllegalArgumentException("A platform reference requires an external identity");
            }
        }
    }

    /**
     * A typed provider release-region reference. {@code providerId} is the stable identity;
     * {@code name} is descriptive metadata only.
     */
    record ProviderRegion(String providerId, String name) {
        public ProviderRegion {
            if (providerId == null || providerId.isBlank()) {
                throw new IllegalArgumentException("A region reference requires an external identity");
            }
        }
    }

    /** A provider cover reference that already satisfies the approved ADR-0001 shape. */
    record ProviderCover(String reference, String sourceUrl) {}
}
