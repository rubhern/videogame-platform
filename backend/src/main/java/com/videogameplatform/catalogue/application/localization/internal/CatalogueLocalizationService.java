package com.videogameplatform.catalogue.application.localization.internal;

import com.videogameplatform.catalogue.application.localization.LocalizeCatalogueUseCase;
import com.videogameplatform.catalogue.application.localization.port.CatalogueTranslationPort;
import com.videogameplatform.catalogue.application.localization.port.LocalizationEvents;
import com.videogameplatform.catalogue.application.localization.port.LocalizationEvents.Outcome;
import com.videogameplatform.catalogue.application.localization.port.LocalizationStore;
import com.videogameplatform.catalogue.application.localization.port.LocalizationStore.Target;
import com.videogameplatform.catalogue.application.localization.port.TranslationFailure;
import java.util.Set;
import java.util.UUID;

public final class CatalogueLocalizationService implements LocalizeCatalogueUseCase {
    private final LocalizationStore store;
    private final CatalogueTranslationPort translator;
    private final LocalizationEvents events;

    public CatalogueLocalizationService(
            LocalizationStore store,
            CatalogueTranslationPort translator,
            LocalizationEvents events) {
        this.store = store;
        this.translator = translator;
        this.events = events;
    }

    @Override
    public Report localizeGame(UUID gameId) {
        return localizeGame(gameId, () -> {});
    }

    @Override
    public Report localizeGame(UUID gameId, Runnable progress) {
        // Enrichment cannot turn an already committed valid Game into a failed synchronization.
        try {
            Report report = new Report(0, 0, 0, 0);
            for (Target target : store.gameTargets(gameId)) {
                report = report.plus(localize(target));
                progress.run();
            }
            return report;
        } catch (RuntimeException failure) {
            events.content(LocalizationStore.Kind.SUMMARY, Outcome.FAILED);
            return new Report(0, 0, 0, 1);
        }
    }

    @Override
    public Batch backfill(String kind, UUID id, int limit) {
        return scan(kind, id, limit, false);
    }

    @Override
    public Batch scan(String kind, UUID id, int limit) {
        return scan(kind, id, limit, true);
    }

    private Batch scan(String kind, UUID id, int limit, boolean advanceOnFailure) {
        if ((!kind.isEmpty() && !Set.of("GENRE", "GAME_MODE", "SUMMARY").contains(kind))
                || limit < 1
                || limit > 100) throw new IllegalArgumentException("Invalid localization batch");
        var targets = store.targetsAfter(kind, id, limit + 1);
        Report report = new Report(0, 0, 0, 0);
        int inspected = 0;
        String lastKind = kind;
        UUID lastId = id;
        for (Target target : targets.subList(0, Math.min(limit, targets.size()))) {
            if (Thread.currentThread().isInterrupted()) break;
            report = report.plus(localize(target));
            inspected++;
            lastKind = target.kind().name();
            lastId = target.id();
        }
        // The management command replays the batch if any target failed or was busy.
        if (!advanceOnFailure && report.failed() > 0) {
            lastKind = kind;
            lastId = id;
        }
        Batch batch =
                new Batch(
                        lastKind,
                        lastId,
                        inspected,
                        inspected == targets.size() && (advanceOnFailure || report.failed() == 0),
                        report);
        events.backfill(batch);
        return batch;
    }

    private Report localize(Target target) {
        UUID claim = null;
        try {
            if (target.current()) return result(target, Outcome.SKIPPED);
            var cached = store.translated(target.fingerprint());
            if (cached.isPresent()) return reuse(target, cached.orElseThrow());
            var acquired = store.claim(target);
            if (acquired.isEmpty()) {
                // Another process might have completed between lookup and reservation.
                cached = store.translated(target.fingerprint());
                if (cached.isPresent()) return reuse(target, cached.orElseThrow());
                return result(target, Outcome.BUSY);
            }
            claim = acquired.orElseThrow();
            var translation = translator.translate(normalize(target.source()));
            if (translation == null
                    || translation.text() == null
                    || translation.text().isBlank()
                    || !fits(target, translation.text())
                    || translation.text().indexOf('\u0000') >= 0
                    || translation.revision() == null
                    || translation.revision().isBlank()
                    || translation.revision().length() > 200)
                throw new IllegalArgumentException("Invalid translation output");
            if (!store.complete(target, claim, translation)) return result(target, Outcome.BUSY);
            if (!store.publish(target)) return result(target, Outcome.SKIPPED);
            if (target.kind() != LocalizationStore.Kind.SUMMARY)
                events.content(target.kind(), Outcome.FALLBACK);
            return result(target, Outcome.TRANSLATED);
        } catch (RuntimeException failure) {
            if (claim != null
                    && !(failure instanceof TranslationFailure ambiguous
                            && ambiguous.mayStillBeRunning())) {
                try {
                    store.release(target.fingerprint(), claim);
                } catch (RuntimeException ignored) {
                    /* lease expires */
                }
            }
            return result(target, Outcome.FAILED);
        }
    }

    private Report reuse(Target target, CatalogueTranslationPort.Translation translation) {
        if (!fits(target, translation.text())) return result(target, Outcome.FAILED);
        return result(target, store.publish(target) ? Outcome.REUSED : Outcome.SKIPPED);
    }

    private static boolean fits(Target target, String text) {
        int bound = target.kind() == LocalizationStore.Kind.SUMMARY ? 20000 : 200;
        return text.codePointCount(0, text.length()) <= bound;
    }

    private Report result(Target target, Outcome outcome) {
        events.content(target.kind(), outcome);
        return switch (outcome) {
            case TRANSLATED -> new Report(1, 0, 0, 0);
            case REUSED -> new Report(0, 1, 0, 0);
            case SKIPPED -> new Report(0, 0, 1, 0);
            case BUSY, FAILED -> new Report(0, 0, 0, 1);
            case FALLBACK -> throw new IllegalArgumentException("Fallback is an additional event");
        };
    }

    public static String normalize(String source) {
        return source.replaceAll("[ \t\r\n]+", " ").trim();
    }
}
