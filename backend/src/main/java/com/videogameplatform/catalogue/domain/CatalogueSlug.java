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
        String trimmed = slugCharacters(lowercase);
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
        String trimmedSuffix = slugCharacters(discriminator.toLowerCase(Locale.ROOT));
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
        int end = candidate.length();
        while (end > 0 && candidate.charAt(end - 1) == '-') {
            end--;
        }
        return candidate.substring(0, end);
    }

    private static String slugCharacters(String input) {
        StringBuilder slug = new StringBuilder(input.length());
        boolean separatorPending = false;
        for (int index = 0; index < input.length(); index++) {
            char character = input.charAt(index);
            if ((character >= 'a' && character <= 'z') || (character >= '0' && character <= '9')) {
                if (separatorPending && !slug.isEmpty()) {
                    slug.append('-');
                }
                slug.append(character);
                separatorPending = false;
            } else if (!slug.isEmpty()) {
                separatorPending = true;
            }
        }
        return slug.toString();
    }
}
