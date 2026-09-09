package com.videogameplatform.catalogue.application.synchronization.port;

import com.videogameplatform.catalogue.domain.ReleaseDate;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Outbound port for the approved external catalogue provider.
 *
 * <p>Everything crossing this port is already product vocabulary: platform and region codes the
 * local taxonomy owns, closed date variants, a closed work-type vocabulary, and an opaque provider
 * reference. Provider transport models, provider taxonomy names and raw payloads stay inside the
 * adapter (EXT-001, EXT-002).
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
     * <p>Platform and region are local taxonomy codes, never provider labels. Subscription or
     * promotional availability is not a release and never reaches this record (REL-006).
     */
    record ProviderRelease(
            String providerId,
            String platformCode,
            String regionCode,
            ReleaseDate date,
            ProviderReleaseSignal signal) {
        public ProviderRelease {
            if (providerId == null || providerId.isBlank()) {
                throw new IllegalArgumentException("A release requires an external identity");
            }
        }
    }

    /** A provider cover reference that already satisfies the approved ADR-0001 shape. */
    record ProviderCover(String reference, String sourceUrl) {}
}
