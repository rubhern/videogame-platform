package com.videogameplatform.api.delivery.catalogue.administration;

import com.videogameplatform.catalogue.application.administration.EditorialAttentionFileInvalidException;
import com.videogameplatform.catalogue.application.administration.EditorialAttentionLists;
import com.videogameplatform.catalogue.application.administration.EditorialAttentionLists.EditorialFile;
import com.videogameplatform.catalogue.application.administration.EditorialAttentionLists.FileEntry;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the text a language model returned as an editorial anticipation file. It tolerates what
 * models add around the document, a Markdown code fence and members the schema does not name, but
 * never guesses a value: every invalid member is reported with a JSON Pointer into the file.
 */
final class EditorialAttentionFileReader {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private EditorialAttentionFileReader() {}

    static EditorialFile read(String content) {
        JsonNode root;
        try {
            root = JSON.readTree(unfenced(content));
        } catch (JacksonException malformed) {
            throw invalid("", "The file is not a JSON document.");
        }
        if (root == null || !root.isObject()) {
            throw invalid("", "The file is not a JSON object.");
        }
        JsonNode version = root.get("schemaVersion");
        if (version == null
                || !version.isIntegralNumber()
                || version.asInt() != EditorialAttentionLists.SCHEMA_VERSION) {
            throw invalid("/schemaVersion", "Only schema version 1 is supported.");
        }
        JsonNode range = object(root, "range", "/range");
        JsonNode generator = object(root, "generator", "/generator");
        JsonNode entries = root.get("entries");
        if (entries == null || !entries.isArray()) {
            throw invalid("/entries", "The file needs an entries array.");
        }
        if (entries.size() > EditorialAttentionLists.MAX_ENTRIES) {
            throw invalid("/entries", "A file holds at most 50 entries.");
        }
        List<FileEntry> read = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            read.add(entry(entries.get(index), "/entries/" + index));
        }
        return new EditorialFile(
                date(range, "from", "/range/from").orElse(null),
                date(range, "to", "/range/to").orElse(null),
                text(generator, "prompt", "/generator/prompt").orElse(null),
                text(generator, "model", "/generator/model"),
                instant(root, "generatedAt", "/generatedAt"),
                read);
    }

    private static FileEntry entry(JsonNode entry, String pointer) {
        if (entry == null || !entry.isObject()) {
            throw invalid(pointer, "An entry is a JSON object.");
        }
        int rank =
                integer(entry, "rank", pointer + "/rank", 1, EditorialAttentionLists.MAX_ENTRIES);
        int score = integer(entry, "score", pointer + "/score", 1, 100);
        List<String> sources = new ArrayList<>();
        JsonNode sourceArray = entry.get("sources");
        if (sourceArray != null && !sourceArray.isNull()) {
            if (!sourceArray.isArray()) {
                throw invalid(pointer + "/sources", "Sources are an array of addresses.");
            }
            for (int index = 0; index < sourceArray.size(); index++) {
                JsonNode source = sourceArray.get(index);
                if (!source.isString()) {
                    throw invalid(pointer + "/sources/" + index, "A source is an address.");
                }
                sources.add(source.asString().strip());
            }
        }
        try {
            return new FileEntry(
                    rank,
                    text(entry, "igdbSlug", pointer + "/igdbSlug"),
                    text(entry, "title", pointer + "/title").orElse(null),
                    date(entry, "releaseDate", pointer + "/releaseDate"),
                    score,
                    text(entry, "rationale", pointer + "/rationale"),
                    sources);
        } catch (EditorialAttentionFileInvalidException tooLong) {
            throw invalid(pointer + "/" + tooLong.pointer(), tooLong.getMessage());
        } catch (IllegalArgumentException member) {
            throw invalid(pointer + "/" + member.getMessage(), "The value is not accepted.");
        }
    }

    /** The document without a surrounding Markdown code fence. */
    private static String unfenced(String content) {
        String stripped = content.strip();
        if (stripped.startsWith("```")) {
            int firstLine = stripped.indexOf('\n');
            int closing = stripped.lastIndexOf("```");
            if (firstLine > 0 && closing > firstLine) {
                return stripped.substring(firstLine + 1, closing);
            }
        }
        return stripped;
    }

    private static JsonNode object(JsonNode parent, String name, String pointer) {
        JsonNode node = parent.get(name);
        if (node == null || !node.isObject()) {
            throw invalid(pointer, "The file needs this object.");
        }
        return node;
    }

    private static Optional<String> text(JsonNode parent, String name, String pointer) {
        JsonNode node = parent.get(name);
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        if (!node.isString()) {
            throw invalid(pointer, "The value is text.");
        }
        String value = node.asString().strip();
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    private static Optional<LocalDate> date(JsonNode parent, String name, String pointer) {
        Optional<String> value = text(parent, name, pointer);
        try {
            return value.map(LocalDate::parse);
        } catch (DateTimeParseException invalidDate) {
            throw invalid(pointer, "Use an ISO day: YYYY-MM-DD.");
        }
    }

    private static Optional<Instant> instant(JsonNode parent, String name, String pointer) {
        Optional<String> value = text(parent, name, pointer);
        try {
            return value.map(text -> OffsetDateTime.parse(text).toInstant());
        } catch (DateTimeParseException invalidInstant) {
            throw invalid(pointer, "Use an ISO-8601 instant with its offset.");
        }
    }

    private static int integer(JsonNode parent, String name, String pointer, int min, int max) {
        JsonNode node = parent.get(name);
        if (node == null
                || !node.isNumber()
                || !node.canConvertToExactIntegral()
                || !node.canConvertToInt()
                || node.asInt() < min
                || node.asInt() > max) {
            throw invalid(pointer, "Use an integer from " + min + " to " + max + ".");
        }
        return node.asInt();
    }

    private static EditorialAttentionFileInvalidException invalid(String pointer, String reason) {
        return new EditorialAttentionFileInvalidException(pointer, reason);
    }
}
