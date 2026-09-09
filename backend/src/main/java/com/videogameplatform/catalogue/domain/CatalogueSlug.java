package com.videogameplatform.catalogue.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The navigation form of a catalogue title.
 *
 * <p>A slug is navigation, never identity (GAME-003): it is derived from the display title, it is
 * unique in current catalogue state, and nothing resolves a game by it during synchronization. It
 * is derived once when a work is imported and then left alone, so an upstream title correction
 * cannot silently break an existing link.
 */
public record CatalogueSlug(String value) {

    private static final Pattern COMBINING_MARKS = Pattern.compile("[\\u0300-\\u036f]+");
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]++");
    private static final Pattern TRIM_SEPARATORS = Pattern.compile("(?:^-+|-+$)");
    private static final Pattern VALID = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final int MAX_LENGTH = 180;

    public CatalogueSlug {
        if (value == null || !VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("A catalogue slug must be lowercase and hyphenated");
        }
    }

    /**
     * Derives a slug from a title, or throws when the title carries no usable characters.
     *
     * <p>Latin diacritics are folded. Other writing systems use a neutral base which the caller
     * distinguishes by internal Game identity; the stored display title is never rewritten.
     */
    public static CatalogueSlug fromTitle(String title) {
        String decomposed = Normalizer.normalize(title == null ? "" : title, Normalizer.Form.NFD);
        String withoutMarks = COMBINING_MARKS.matcher(decomposed).replaceAll("");
        String lowercase = withoutMarks.toLowerCase(Locale.ROOT);
        String hyphenated = NON_ALPHANUMERIC.matcher(lowercase).replaceAll("-");
        String trimmed = TRIM_SEPARATORS.matcher(hyphenated).replaceAll("");
        if (trimmed.isEmpty()) {
            if (title != null && title.codePoints().anyMatch(Character::isLetterOrDigit)) {
                return new CatalogueSlug("game");
            }
            throw new IllegalArgumentException("A catalogue title must yield a navigable slug");
        }
        return new CatalogueSlug(truncate(trimmed));
    }

    /**
     * A distinct slug for a title shared by several Games.
     *
     * <p>The caller supplies the internal Game identity, never a provider reference. The stored
     * slug remains stable across reconciliation runs.
     */
    public CatalogueSlug distinguishedBy(String discriminator) {
        String suffix =
                NON_ALPHANUMERIC.matcher(discriminator.toLowerCase(Locale.ROOT)).replaceAll("-");
        String trimmedSuffix = TRIM_SEPARATORS.matcher(suffix).replaceAll("");
        if (trimmedSuffix.isEmpty()) {
            throw new IllegalArgumentException("A slug discriminator must be navigable");
        }
        int room = MAX_LENGTH - trimmedSuffix.length() - 1;
        String base = value.length() > room ? trim(value.substring(0, Math.max(1, room))) : value;
        return new CatalogueSlug(base + "-" + trimmedSuffix);
    }

    private static String truncate(String candidate) {
        return candidate.length() <= MAX_LENGTH
                ? candidate
                : trim(candidate.substring(0, MAX_LENGTH));
    }

    private static String trim(String candidate) {
        return TRIM_SEPARATORS.matcher(candidate).replaceAll("");
    }
}
