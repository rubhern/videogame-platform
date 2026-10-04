package com.videogameplatform.catalogue.application.localization;

import java.util.UUID;

/** Acquisition-only enrichment and bounded operator backfill; never a read dependency. */
public interface LocalizeCatalogueUseCase {
    Report localizeGame(UUID gameId);

    default Report localizeGame(UUID gameId, Runnable progress) {
        return localizeGame(gameId);
    }

    Batch backfill(String afterKind, UUID afterId, int limit);

    record Report(int translated, int reused, int skipped, int failed) {
        public Report plus(Report other) {
            return new Report(
                    translated + other.translated,
                    reused + other.reused,
                    skipped + other.skipped,
                    failed + other.failed);
        }
    }

    record Batch(String afterKind, UUID afterId, int inspected, boolean completed, Report report) {}
}
