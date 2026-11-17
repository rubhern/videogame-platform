package com.videogameplatform.api.delivery.catalogue.administration;

import com.videogameplatform.api.delivery.ApiRequestException;
import com.videogameplatform.api.generated.EditorialAttentionApi;
import com.videogameplatform.api.generated.model.EditorialAttentionAssignment;
import com.videogameplatform.api.generated.model.EditorialAttentionGameMatch;
import com.videogameplatform.api.generated.model.EditorialAttentionListDetail;
import com.videogameplatform.api.generated.model.EditorialAttentionListEntry;
import com.videogameplatform.api.generated.model.EditorialAttentionListSummary;
import com.videogameplatform.api.generated.model.EditorialAttentionLoad;
import com.videogameplatform.api.generated.model.EditorialAttentionOverview;
import com.videogameplatform.api.generated.model.EditorialAttentionPreview;
import com.videogameplatform.api.generated.model.EditorialAttentionPreviewEntry;
import com.videogameplatform.api.generated.model.EditorialAttentionPrompt;
import com.videogameplatform.api.generated.model.EditorialAttentionUpload;
import com.videogameplatform.api.generated.model.EditorialAttentionWarning;
import com.videogameplatform.api.generated.model.ProblemCode;
import com.videogameplatform.catalogue.application.administration.EditorialAttentionLists;
import com.videogameplatform.catalogue.application.administration.EditorialAttentionLists.Assignment;
import com.videogameplatform.catalogue.application.administration.EditorialAttentionLists.ListDetail;
import com.videogameplatform.catalogue.application.administration.EditorialAttentionLists.ListSummary;
import com.videogameplatform.catalogue.application.administration.EditorialAttentionLists.LoadOutcome;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Editorial anticipation lists for the catalogue operator (UC-023). The security boundary has
 * already required the operator authority, and CSRF proof for every command. Responses are
 * operational state and are never stored.
 */
@RestController
@RequestMapping("/api/v1")
public class EditorialAttentionController implements EditorialAttentionApi {

    private static final String LIST_ID_POINTER = "/path/listId";

    private final EditorialAttentionLists lists;

    EditorialAttentionController(EditorialAttentionLists lists) {
        this.lists = lists;
    }

    @Override
    public ResponseEntity<EditorialAttentionOverview> getEditorialAttention() {
        EditorialAttentionLists.Prompt prompt = lists.prompt();
        return ok(
                new EditorialAttentionOverview(
                        new EditorialAttentionPrompt(prompt.version(), prompt.template()),
                        lists.lists().stream()
                                .map(EditorialAttentionController::summary)
                                .toList()));
    }

    @Override
    public ResponseEntity<EditorialAttentionPreview> previewEditorialAttentionFile(
            String xCsrfToken, EditorialAttentionUpload upload) {
        EditorialAttentionLists.Preview preview =
                lists.preview(EditorialAttentionFileReader.read(upload.getContent()));
        EditorialAttentionLists.EditorialFile file = preview.file();
        EditorialAttentionPreview body =
                new EditorialAttentionPreview(
                        file.from(),
                        file.to(),
                        file.promptVersion(),
                        preview.entries().stream()
                                .map(EditorialAttentionController::previewEntry)
                                .toList());
        file.model().ifPresent(body::setModel);
        file.generatedAt()
                .map(EditorialAttentionController::timestamp)
                .ifPresent(body::setGeneratedAt);
        preview.replaces().map(EditorialAttentionController::summary).ifPresent(body::setReplaces);
        preview.conflict().map(EditorialAttentionController::summary).ifPresent(body::setConflict);
        return ok(body);
    }

    @Override
    public ResponseEntity<EditorialAttentionListDetail> loadEditorialAttentionList(
            String xCsrfToken, EditorialAttentionLoad load) {
        EditorialAttentionLists.EditorialFile file =
                EditorialAttentionFileReader.read(load.getContent());
        List<Assignment> assignments =
                load.getAssignments().stream()
                        .map(EditorialAttentionController::assignment)
                        .toList();
        return switch (lists.load(file, assignments)) {
            case LoadOutcome.Loaded loaded ->
                    ResponseEntity.status(HttpStatus.CREATED)
                            .cacheControl(CacheControl.noStore())
                            .body(detail(loaded.list()));
            case LoadOutcome.Conflict ignored ->
                    throw new ApiRequestException(
                            ProblemCode.EDITORIAL_ATTENTION_RANGE_CONFLICT, "/content/range");
        };
    }

    @Override
    public ResponseEntity<EditorialAttentionListDetail> getEditorialAttentionList(String listId) {
        return ok(
                detail(
                        identity(listId)
                                .flatMap(lists::list)
                                .orElseThrow(EditorialAttentionController::notFound)));
    }

    @Override
    public ResponseEntity<Void> deleteEditorialAttentionList(String xCsrfToken, String listId) {
        if (!identity(listId).map(lists::delete).orElse(false)) {
            throw notFound();
        }
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private static Assignment assignment(EditorialAttentionAssignment assignment) {
        return new Assignment(
                assignment.getRank(),
                Optional.ofNullable(assignment.getGameId()).map(UUID::toString));
    }

    private static EditorialAttentionPreviewEntry previewEntry(
            EditorialAttentionLists.PreviewEntry preview) {
        EditorialAttentionLists.FileEntry entry = preview.entry();
        EditorialAttentionPreviewEntry body =
                new EditorialAttentionPreviewEntry(
                        entry.rank(),
                        entry.title(),
                        entry.score(),
                        preview.warnings().stream()
                                .map(
                                        warning ->
                                                EditorialAttentionWarning.fromValue(
                                                        warning.name().toLowerCase(Locale.ROOT)))
                                .toList());
        entry.igdbSlug().ifPresent(body::setIgdbSlug);
        entry.releaseDate().ifPresent(body::setReleaseDate);
        entry.rationale().ifPresent(body::setRationale);
        preview.match()
                .map(
                        match -> {
                            EditorialAttentionGameMatch game =
                                    new EditorialAttentionGameMatch(
                                            UUID.fromString(match.gameId()),
                                            match.title(),
                                            match.slug(),
                                            EditorialAttentionGameMatch.MatchedByEnum.fromValue(
                                                    match.matchedBy()
                                                            .name()
                                                            .toLowerCase(Locale.ROOT)));
                            match.releaseInRange().ifPresent(game::setReleaseInRange);
                            return game;
                        })
                .ifPresent(body::setMatch);
        return body;
    }

    private static EditorialAttentionListDetail detail(ListDetail detail) {
        return new EditorialAttentionListDetail(
                summary(detail.summary()),
                detail.entries().stream()
                        .map(
                                entry -> {
                                    EditorialAttentionListEntry body =
                                            new EditorialAttentionListEntry(
                                                    entry.rank(),
                                                    UUID.fromString(entry.gameId()),
                                                    entry.title(),
                                                    entry.slug(),
                                                    entry.fileTitle(),
                                                    entry.score(),
                                                    entry.sources());
                                    entry.rationale().ifPresent(body::setRationale);
                                    return body;
                                })
                        .toList());
    }

    private static EditorialAttentionListSummary summary(ListSummary list) {
        EditorialAttentionListSummary body =
                new EditorialAttentionListSummary(
                        list.listId().toString(),
                        list.from(),
                        list.to(),
                        list.promptVersion(),
                        timestamp(list.loadedAt()),
                        list.entryCount());
        list.model().ifPresent(body::setModel);
        list.generatedAt()
                .map(EditorialAttentionController::timestamp)
                .ifPresent(body::setGeneratedAt);
        return body;
    }

    /** An identity that is not a list's is simply not found. */
    private static Optional<UUID> identity(String listId) {
        try {
            UUID id = UUID.fromString(listId);
            return id.toString().equalsIgnoreCase(listId) ? Optional.of(id) : Optional.empty();
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    private static ApiRequestException notFound() {
        return new ApiRequestException(
                ProblemCode.EDITORIAL_ATTENTION_LIST_NOT_FOUND, LIST_ID_POINTER);
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
