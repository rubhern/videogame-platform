package com.videogameplatform.api.delivery.frontend;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.web.util.HtmlUtils;

/**
 * The packaged SPA entry point with its page-metadata block replaced per request (ADR-0049).
 *
 * <p>{@code frontend/index.html} marks the block with {@link #START} and {@link #END}; the
 * template is read once and is immutable, so rendering is string concatenation over a few
 * kilobytes. Every value is HTML-escaped and every description shortened by {@link #describe}.
 */
final class FrontendDocument {
    static final String START = "<!-- page-metadata:start -->";
    static final String END = "<!-- page-metadata:end -->";

    /** The longest description, in characters, that previews and result snippets show whole. */
    static final int DESCRIPTION_LIMIT = 160;

    private static final String INDENT = "\n    ";

    private final String before;
    private final String after;

    FrontendDocument(String template) {
        Objects.requireNonNull(template, "template");
        int start = template.indexOf(START);
        int end = template.indexOf(END);
        if (start < 0
                || end < start
                || template.indexOf(START, start + 1) >= 0
                || template.indexOf(END, end + 1) >= 0) {
            throw new IllegalStateException(
                    "The packaged index.html has no single page-metadata block; rebuild the"
                            + " frontend");
        }
        this.before = template.substring(0, start);
        this.after = template.substring(end + END.length());
    }

    static FrontendDocument of(byte[] template) {
        return new FrontendDocument(new String(template, StandardCharsets.UTF_8));
    }

    /**
     * The document for one page. {@code origin} is the request's public origin, without a
     * trailing slash, and makes the canonical and image URLs absolute as previews require.
     */
    String render(PageMetadata page, String origin) {
        String description = describe(page.description());
        StringBuilder head = new StringBuilder(START);
        line(head, "<title>" + escape(page.title()) + "</title>");
        meta(head, "name", "description", description);
        if (!page.indexable()) {
            meta(head, "name", "robots", "noindex");
        }
        if (page.canonicalPath() != null) {
            line(
                    head,
                    "<link rel=\"canonical\" href=\""
                            + escape(origin + page.canonicalPath())
                            + "\" />");
        }
        meta(head, "property", "og:site_name", PageMetadata.SITE_NAME);
        meta(head, "property", "og:locale", "es_ES");
        meta(head, "property", "og:type", "website");
        meta(head, "property", "og:title", page.title());
        meta(head, "property", "og:description", description);
        if (page.canonicalPath() != null) {
            meta(head, "property", "og:url", origin + page.canonicalPath());
        }
        PageMetadata.Image image = page.image();
        meta(head, "property", "og:image", absolute(image.url(), origin));
        meta(head, "property", "og:image:alt", image.alternativeText());
        if (image.width() != null && image.height() != null) {
            meta(head, "property", "og:image:width", image.width().toString());
            meta(head, "property", "og:image:height", image.height().toString());
        }
        meta(head, "name", "twitter:card", "summary_large_image");
        line(head, END);
        return before + head + after;
    }

    /**
     * The description rule: whitespace runs collapse to one space; text longer than {@link
     * #DESCRIPTION_LIMIT} characters is cut at the last word boundary that leaves room for an
     * ellipsis, or mid-word when no boundary lies in the second half. Code points are never split.
     */
    static String describe(String text) {
        String normalized = text.strip().replaceAll("\\s+", " ");
        if (normalized.codePointCount(0, normalized.length()) <= DESCRIPTION_LIMIT) {
            return normalized;
        }
        int cut = normalized.offsetByCodePoints(0, DESCRIPTION_LIMIT - 1);
        int space = normalized.lastIndexOf(' ', cut);
        if (space > cut / 2) cut = space;
        return normalized.substring(0, cut).replaceAll("[\\s\\p{Punct}]+$", "") + "…";
    }

    private static String absolute(String url, String origin) {
        return url.startsWith("/") ? origin + url : url;
    }

    private static void meta(StringBuilder head, String attribute, String key, String value) {
        line(head, "<meta " + attribute + "=\"" + key + "\" content=\"" + escape(value) + "\" />");
    }

    private static void line(StringBuilder head, String value) {
        head.append(INDENT).append(value);
    }

    /** Escapes {@code & < > " '} only; accented letters stay literal UTF-8. */
    private static String escape(String value) {
        return HtmlUtils.htmlEscape(value, StandardCharsets.UTF_8.name());
    }
}
