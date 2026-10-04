package com.videogameplatform.catalogue.application.details;

import com.videogameplatform.catalogue.application.cover.CatalogueCover;
import com.videogameplatform.catalogue.application.releases.BrowseReleasesResult;
import java.time.LocalDate;
import java.util.List;

/** One complete, bounded game context; deliberately contains no rating or user state. */
public record GameDetailsResult(
        String gameId,
        String slug,
        String canonicalTitle,
        List<String> aliases,
        Summary summary,
        List<Company> developers,
        List<Company> publishers,
        List<Term> genres,
        List<Term> gameModes,
        CatalogueCover primaryCover,
        List<BrowseReleasesResult.Release> releases,
        LocalDate evaluatedOn) {
    public record Summary(
            String kind,
            String text,
            String language,
            BrowseReleasesResult.Provenance provenance,
            Translation translation) {
        public Summary(
                String kind,
                String text,
                String language,
                BrowseReleasesResult.Provenance provenance) {
            this(kind, text, language, provenance, null);
        }
    }

    public record Translation(String sourceText, String sourceLanguage, boolean current) {}

    /** A credited company: its product identity and current name. */
    public record Company(String companyId, String name) {}

    /** A genre or game mode: its stable product code and display name. */
    public record Term(String code, String name) {}
}
