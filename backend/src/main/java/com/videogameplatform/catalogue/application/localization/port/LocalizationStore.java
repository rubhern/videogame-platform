package com.videogameplatform.catalogue.application.localization.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LocalizationStore {
    enum Kind {
        GENRE,
        GAME_MODE,
        SUMMARY
    }

    record Target(Kind kind, UUID id, String source, String fingerprint, boolean current) {}

    List<Target> gameTargets(UUID gameId);

    List<Target> targetsAfter(String kind, UUID id, int limit);

    Optional<CatalogueTranslationPort.Translation> translated(String fingerprint);

    /** Durable lease; expired claims may be replaced, and every completion is fenced. */
    Optional<UUID> claim(Target target);

    boolean complete(Target target, UUID claim, CatalogueTranslationPort.Translation result);

    void release(String fingerprint, UUID claim);

    /** Compare current source and ownership again in the same transaction as publication. */
    boolean publish(Target target);
}
