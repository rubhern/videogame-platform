package com.videogameplatform.api.delivery.catalogue;

import com.videogameplatform.api.generated.model.EditorialSummary;
import com.videogameplatform.api.generated.model.GameSummaryText;
import com.videogameplatform.api.generated.model.Provenance;
import com.videogameplatform.api.generated.model.SourcedSummary;
import com.videogameplatform.catalogue.application.details.GameDetailsResult;

/** One HTTP mapping for catalogue summaries on game details and featured discovery. */
public final class CatalogueSummaryMapper {
    private CatalogueSummaryMapper() {}

    public static GameSummaryText toResponse(GameDetailsResult.Summary summary) {
        if ("editorial".equals(summary.kind())) {
            return new EditorialSummary("editorial", summary.text(), summary.language());
        }
        var source = summary.provenance();
        var result =
                new SourcedSummary(
                        "sourced",
                        summary.text(),
                        summary.language(),
                        new Provenance(
                                Provenance.SourceKindEnum.valueOf(source.sourceKind().name()),
                                source.sourceName(),
                                source.sourceEntityType()));
        if (summary.translation() != null) {
            var translation = summary.translation();
            result.setTranslation(
                    new com.videogameplatform.api.generated.model.SummaryTranslation(
                            "machine_translation",
                            translation.sourceText(),
                            translation.sourceLanguage(),
                            translation.current()));
        }
        return result;
    }
}
