package com.videogameplatform.catalogue.domain;

import java.text.Normalizer;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Normalized form of a catalogue title, alias or visitor query.
 *
 * <p>Normalization is case- and diacritic-insensitive and never rewrites the stored display
 * title: it only produces the comparable form used to match, rank and order. The identical
 * rule is stored in PostgreSQL as {@code catalogue.normalize_search_text}, so a normalized
 * query built here compares against normalized catalogue text without a second convention.
 *
 * <p>Letters written with a stroke rather than a combining mark are not diacritics and stay
 * unfolded, so a title such as {@code Ø} keeps its own searchable form.
 */
public record CatalogueSearchText(String normalized, List<String> tokens) {

    private static final Pattern COMBINING_MARKS = Pattern.compile("[\\u0300-\\u036f]+");
    private static final Pattern APOSTROPHES =
            Pattern.compile("[\\u0027\\u2019\\u02bc\\u00b4\\u0060]+");
    private static final Pattern NON_ALPHANUMERIC =
            Pattern.compile("[^\\p{IsAlphabetic}\\p{IsDigit}]+");
    private static final Pattern TOKEN_SEPARATOR = Pattern.compile(" ");

    public CatalogueSearchText {
        if (normalized == null) {
            throw new IllegalArgumentException("Normalized search text is required");
        }
        tokens = List.copyOf(tokens);
    }

    /** Normalizes any catalogue text; a text without alphanumeric content yields no tokens. */
    public static CatalogueSearchText of(String source) {
        if (source == null) {
            return new CatalogueSearchText("", List.of());
        }
        String decomposed = Normalizer.normalize(source, Normalizer.Form.NFD);
        String withoutMarks = COMBINING_MARKS.matcher(decomposed).replaceAll("");
        // PostgreSQL lower() uses simple character mapping. String.toLowerCase would
        // introduce contextual mappings (e.g. final Greek sigma) absent from stored titles.
        StringBuilder lowercase = new StringBuilder(withoutMarks.length());
        withoutMarks.codePoints().map(Character::toLowerCase).forEach(lowercase::appendCodePoint);
        String withoutApostrophes = APOSTROPHES.matcher(lowercase).replaceAll("");
        String normalized = NON_ALPHANUMERIC.matcher(withoutApostrophes).replaceAll(" ").strip();
        return new CatalogueSearchText(
                normalized,
                normalized.isEmpty() ? List.of() : List.of(TOKEN_SEPARATOR.split(normalized)));
    }

    public boolean isEmpty() {
        return tokens.isEmpty();
    }
}
