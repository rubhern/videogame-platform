package com.videogameplatform.catalogue.application.synchronization.port;

import com.videogameplatform.catalogue.domain.FeaturedMediaPolicy;
import com.videogameplatform.catalogue.domain.ReleaseDate;
import com.videogameplatform.catalogue.domain.ReleaseStage;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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

    /**
     * The game logos of up to one provider page of Games, in the provider's stable order.
     *
     * <p>The answer is complete for every requested reference or the call fails as a whole with
     * {@link ProviderRequestException}; a Game without a usable logo is absent. Only metadata
     * crosses: never an image binary.
     */
    LogoBatch logos(List<String> providerIds);

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
            List<ProviderImage> images,
            Optional<String> attributionUrl,
            List<ProviderRelease> releases,
            List<ProviderMappingFailure> mappingFailures,
            Optional<ProviderFeaturedEvidence> featuredEvidence) {

        public ProviderWork(
                String providerId,
                String title,
                ProviderWorkType type,
                Instant providerUpdatedAt,
                Optional<ProviderCover> cover,
                List<ProviderImage> images,
                Optional<String> attributionUrl,
                List<ProviderRelease> releases,
                List<ProviderMappingFailure> mappingFailures) {
            this(
                    providerId,
                    title,
                    type,
                    providerUpdatedAt,
                    cover,
                    images,
                    attributionUrl,
                    releases,
                    mappingFailures,
                    Optional.empty());
        }

        public ProviderWork {
            featuredEvidence =
                    java.util.Objects.requireNonNull(featuredEvidence, "featuredEvidence");
            images = List.copyOf(images);
            attributionUrl = java.util.Objects.requireNonNull(attributionUrl, "attributionUrl");
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
            ProviderReleaseSignal signal,
            ReleaseStage stage) {
        public ProviderRelease {
            if (providerId == null || providerId.isBlank()) {
                throw new IllegalArgumentException("A release requires an external identity");
            }
            if (platform == null) {
                throw new IllegalArgumentException("A release requires a platform reference");
            }
            region = java.util.Objects.requireNonNull(region, "region");
            stage = java.util.Objects.requireNonNull(stage, "stage");
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
                throw new IllegalArgumentException(
                        "A platform reference requires an external identity");
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
                throw new IllegalArgumentException(
                        "A region reference requires an external identity");
            }
        }
    }

    /** A provider cover reference that already satisfies the approved ADR-0001 shape. */
    record ProviderCover(String reference, String sourceUrl) {}

    /**
     * One provider artwork, screenshot or logo as metadata only: an opaque image reference that
     * already satisfies the approved ADR-0001 shape, its pixel dimensions, and whether it is
     * transparent or animated. The featured-media policy chooses among them; the binary is never
     * fetched.
     */
    record ProviderImage(
            FeaturedMediaPolicy.ImageKind kind,
            String reference,
            int width,
            int height,
            boolean transparent,
            boolean animated,
            boolean titleArtwork)
            implements FeaturedMediaPolicy.Image {
        public ProviderImage(
                FeaturedMediaPolicy.ImageKind kind,
                String reference,
                int width,
                int height,
                boolean transparent,
                boolean animated) {
            this(kind, reference, width, height, transparent, animated, false);
        }

        public ProviderImage {
            java.util.Objects.requireNonNull(kind, "kind");
            if (reference == null || reference.isBlank()) {
                throw new IllegalArgumentException("A provider image requires a reference");
            }
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("A provider image requires its dimensions");
            }
        }
    }

    /** Logos keyed by the requested provider Game reference; absent means none is usable. */
    record LogoBatch(Map<String, List<ProviderImage>> logos, ProviderCallStatistics statistics) {
        public LogoBatch {
            logos = Map.copyOf(logos);
        }
    }

    /** Normalized evidence for monthly featured eligibility; no provider parent identity escapes. */
    record ProviderFeaturedEvidence(
            Optional<LocalDate> firstReleaseDate, boolean edition, Optional<Long> hypes) {
        public ProviderFeaturedEvidence {
            java.util.Objects.requireNonNull(firstReleaseDate, "firstReleaseDate");
            java.util.Objects.requireNonNull(hypes, "hypes");
            if (hypes.isPresent() && hypes.orElseThrow() <= 0) {
                throw new IllegalArgumentException("An attention count must be positive");
            }
        }
    }
}
