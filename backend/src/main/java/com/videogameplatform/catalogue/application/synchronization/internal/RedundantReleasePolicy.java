package com.videogameplatform.catalogue.application.synchronization.internal;

import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderRegion;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderRelease;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueSynchronizationStore.PublishedRelease;
import com.videogameplatform.catalogue.domain.ReleaseDate;
import com.videogameplatform.catalogue.domain.ReleaseStage;
import com.videogameplatform.catalogue.domain.ReleaseStatus;
import com.videogameplatform.catalogue.domain.VerificationLevel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Collapses redundant provider evidence of one commercial Release inside one complete Game
 * aggregate (ADR-0017).
 *
 * <p>A Release is one game + platform + region + date + status tuple. When returned references
 * share that tuple and exactly one of them states a known stage while every other states none, the
 * others add nothing to it: the one with the stage is the Release, and the others are redundant
 * evidence, neither stored nor counted as returned, so a stored redundant reference is removed like
 * any obsolete one. The evidence itself selects the kept reference; no title, order or identifier
 * tie-break is involved.
 *
 * <p>Any other shared tuple is a conflict that fails the Game: two known stages, equal or not, no
 * known stage at all, or redundant evidence that is verified. An Unknown stage is missing evidence,
 * never proof of duplication on its own.
 */
final class RedundantReleasePolicy {

    private RedundantReleasePolicy() {}

    /** The references to reconcile, in provider order, and how many were redundant. */
    record Normalized(List<ProviderRelease> releases, int redundant) {

        Set<String> references() {
            Set<String> references = new HashSet<>();
            releases.forEach(release -> references.add(release.providerId()));
            return references;
        }
    }

    /** References share one release tuple without one of them standing for the Release. */
    static final class ConflictingReleaseEvidence extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        ConflictingReleaseEvidence() {
            super("Release references conflict on one release tuple");
        }
    }

    private record Tuple(
            String platformProviderId,
            String regionProviderId,
            ReleaseDate date,
            ReleaseStatus status) {

        static Tuple of(ProviderRelease release) {
            return new Tuple(
                    release.platform().providerId(),
                    release.region().map(ProviderRegion::providerId).orElse(null),
                    release.date(),
                    ReleaseStatusPolicy.persistedStatus(release.signal()));
        }
    }

    /**
     * @param releases the complete returned release set, each reference unique
     * @param published the Game's published releases by provider reference
     * @throws ConflictingReleaseEvidence when references share a tuple and none stands for it
     */
    static Normalized normalize(
            List<ProviderRelease> releases, Map<String, PublishedRelease> published) {
        Map<Tuple, List<ProviderRelease>> byTuple = new LinkedHashMap<>();
        releases.forEach(
                release ->
                        byTuple.computeIfAbsent(Tuple.of(release), _ -> new ArrayList<>())
                                .add(release));
        Set<String> redundant = new HashSet<>();
        for (List<ProviderRelease> group : byTuple.values()) {
            if (group.size() == 1) {
                continue;
            }
            List<ProviderRelease> staged =
                    group.stream()
                            .filter(release -> stage(release, published) != ReleaseStage.UNKNOWN)
                            .toList();
            if (staged.size() != 1) {
                throw new ConflictingReleaseEvidence();
            }
            for (ProviderRelease release : group) {
                if (release == staged.getFirst()) {
                    continue;
                }
                PublishedRelease previous = published.get(release.providerId());
                if (previous != null
                        && previous.verificationLevel() == VerificationLevel.VERIFIED) {
                    // REL-008: normalization never discards verified evidence.
                    throw new ConflictingReleaseEvidence();
                }
                redundant.add(release.providerId());
            }
        }
        return new Normalized(
                releases.stream()
                        .filter(release -> !redundant.contains(release.providerId()))
                        .toList(),
                redundant.size());
    }

    /** The stage reconciliation would keep: Unknown evidence never erases an established stage. */
    private static ReleaseStage stage(
            ProviderRelease release, Map<String, PublishedRelease> published) {
        PublishedRelease previous = published.get(release.providerId());
        return release.stage() == ReleaseStage.UNKNOWN && previous != null
                ? previous.stage()
                : release.stage();
    }
}
