package com.videogameplatform.catalogue.adapter.provider.igdb;

import com.videogameplatform.catalogue.adapter.provider.igdb.model.IgdbGamePayload;
import com.videogameplatform.catalogue.adapter.provider.igdb.model.IgdbInvolvedCompanyPayload;
import com.videogameplatform.catalogue.adapter.provider.igdb.model.IgdbTermPayload;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderCompany;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderGameDetails;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderSummary;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort.ProviderTerm;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Normalizes the IGDB summary, company credits, genres and game modes of one game.
 *
 * <p>IGDB credits a company through a company-credit record carrying role flags; only the
 * developer and publisher roles are product vocabulary, so porting-only and supporting-only credits
 * never leave this class, and a company credited twice is one company with both roles. The answer
 * is all or nothing: any incoherent value yields no details, so the Game keeps its last valid ones
 * rather than publishing a partial picture. A missing value is the provider stating none.
 */
final class IgdbGameDetailsMapper {

    /** IGDB writes its game summaries in English. */
    static final String SUMMARY_LANGUAGE = "en";

    /** Credits per game, any role; a larger expansion is not plausible metadata. */
    private static final int MAX_COMPANY_CREDITS = 200;

    private static final int MAX_COMPANY_NAME = 300;
    private static final int MAX_TERM_NAME = 200;

    private IgdbGameDetailsMapper() {}

    static Optional<ProviderGameDetails> map(IgdbGamePayload game) {
        try {
            Optional<ProviderSummary> summary = summary(game.summary());
            Credits credits = credits(game.involvedCompanies());
            return Optional.of(
                    new ProviderGameDetails(
                            summary,
                            credits.developers(),
                            credits.publishers(),
                            terms(game.genres()),
                            terms(game.gameModes())));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    private static Optional<ProviderSummary> summary(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        if (normalized.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("A summary cannot carry a NUL character");
        }
        return Optional.of(new ProviderSummary(normalized, SUMMARY_LANGUAGE));
    }

    private record Credits(List<ProviderCompany> developers, List<ProviderCompany> publishers) {}

    private static Credits credits(List<IgdbInvolvedCompanyPayload> credits) {
        if (credits == null) {
            return new Credits(List.of(), List.of());
        }
        if (credits.size() > MAX_COMPANY_CREDITS) {
            throw new IllegalArgumentException("Too many company credits");
        }
        Map<String, ProviderCompany> developers = new LinkedHashMap<>();
        Map<String, ProviderCompany> publishers = new LinkedHashMap<>();
        for (IgdbInvolvedCompanyPayload credit : credits) {
            if (credit == null) {
                throw new IllegalArgumentException("Unreadable company credit");
            }
            boolean developer = Boolean.TRUE.equals(credit.developer());
            boolean publisher = Boolean.TRUE.equals(credit.publisher());
            if (!developer && !publisher) {
                continue;
            }
            if (credit.company() == null
                    || credit.company().id() == null
                    || credit.company().id() <= 0) {
                throw new IllegalArgumentException("A credit requires its company");
            }
            ProviderCompany company =
                    new ProviderCompany(
                            Long.toString(credit.company().id()),
                            name(credit.company().name(), MAX_COMPANY_NAME));
            if (developer) {
                developers.putIfAbsent(company.providerId(), company);
            }
            if (publisher) {
                publishers.putIfAbsent(company.providerId(), company);
            }
        }
        return new Credits(List.copyOf(developers.values()), List.copyOf(publishers.values()));
    }

    private static List<ProviderTerm> terms(List<IgdbTermPayload> payloads) {
        if (payloads == null) {
            return List.of();
        }
        if (payloads.size() > ProviderGameDetails.MAX_ENTRIES) {
            throw new IllegalArgumentException("Too many terms");
        }
        Map<String, ProviderTerm> terms = new LinkedHashMap<>();
        for (IgdbTermPayload payload : payloads) {
            if (payload == null || payload.id() == null || payload.id() <= 0) {
                throw new IllegalArgumentException("A term requires its identity");
            }
            terms.putIfAbsent(
                    Long.toString(payload.id()),
                    new ProviderTerm(
                            Long.toString(payload.id()),
                            name(payload.name(), MAX_TERM_NAME),
                            payload.slug()));
        }
        return new ArrayList<>(terms.values());
    }

    private static String name(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A name is required");
        }
        String stripped = value.strip();
        if (stripped.length() > maxLength) {
            throw new IllegalArgumentException("A name exceeds its bound");
        }
        return stripped;
    }
}
