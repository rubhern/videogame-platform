package com.videogameplatform.api.delivery.ratings;

import com.videogameplatform.api.delivery.ApiRequestException;
import com.videogameplatform.api.generated.RatingsApi;
import com.videogameplatform.api.generated.model.PersonalRating;
import com.videogameplatform.api.generated.model.PersonalRatingPage;
import com.videogameplatform.api.generated.model.ProblemCode;
import com.videogameplatform.api.generated.model.RatingDeleteResult;
import com.videogameplatform.api.generated.model.RatingWrite;
import com.videogameplatform.api.generated.model.RatingWriteResult;
import com.videogameplatform.identity.application.CurrentUser;
import com.videogameplatform.ratings.application.DeletePersonalRatingUseCase;
import com.videogameplatform.ratings.application.GetPersonalRatingUseCase;
import com.videogameplatform.ratings.application.PutPersonalRatingUseCase;
import java.net.URI;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Generated conditional HTTP adapter for UC-005, UC-006 and UC-007. */
@RestController
@RequestMapping("/api/v1")
public class PersonalRatingController implements RatingsApi {
    private final CurrentUser currentUser;
    private final GetPersonalRatingUseCase reads;
    private final PutPersonalRatingUseCase writes;
    private final DeletePersonalRatingUseCase deletes;
    private final RatingApiMapper mapper;

    PersonalRatingController(
            CurrentUser currentUser,
            GetPersonalRatingUseCase reads,
            PutPersonalRatingUseCase writes,
            DeletePersonalRatingUseCase deletes,
            RatingApiMapper mapper) {
        this.currentUser = currentUser;
        this.reads = reads;
        this.writes = writes;
        this.deletes = deletes;
        this.mapper = mapper;
    }

    @Override
    public ResponseEntity<PersonalRating> getMyRating(String gameId) {
        PersonalRating body = mapper.toResponse(reads.get(userId(), gameId));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .eTag(body.getEntityTag())
                .body(body);
    }

    @Override
    public ResponseEntity<RatingWriteResult> putMyRating(
            String csrfToken,
            String gameId,
            RatingWrite ratingWrite,
            String ifNoneMatch,
            String ifMatch) {
        boolean create = ifNoneMatch != null;
        if (create == (ifMatch != null)) {
            throw preconditionRequired();
        }
        if (create && !"*".equals(ifNoneMatch)) {
            throw headerMalformed("/headers/If-None-Match");
        }

        var result =
                create
                        ? writes.create(userId(), gameId, ratingWrite.getValue())
                        : writes.update(
                                userId(), gameId, ratingWrite.getValue(), versionToken(ifMatch));
        PersonalRating personal = mapper.toResponse(result.personalRating());
        RatingWriteResult body =
                new RatingWriteResult(personal, mapper.toResponse(result.statistics()));
        var response =
                create
                        ? ResponseEntity.created(
                                URI.create(
                                        "/api/v1/me/ratings/" + result.personalRating().gameId()))
                        : ResponseEntity.ok();
        return response.cacheControl(CacheControl.noStore())
                .eTag(personal.getEntityTag())
                .body(body);
    }

    @Override
    public ResponseEntity<RatingDeleteResult> deleteMyRating(
            String csrfToken, String ifMatch, String gameId) {
        var statistics = deletes.delete(userId(), gameId, versionToken(ifMatch));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new RatingDeleteResult(null, mapper.toResponse(statistics)));
    }

    @Override
    public ResponseEntity<PersonalRatingPage> listMyRatings(
            String q, String sort, String direction, Integer page, Integer pageSize) {
        // UC-008 is owned by #32. Preserve the pre-existing absence of this resource.
        return ResponseEntity.notFound().build();
    }

    private String userId() {
        return currentUser
                .currentUserId()
                .orElseThrow(() -> new IllegalStateException("Security filter admitted no user"));
    }

    /**
     * Extracts the opaque version token from a strong entity tag. An absent header is a missing
     * precondition (428); a present value that is not a quoted strong tag is malformed (400).
     */
    private static String versionToken(String entityTag) {
        if (entityTag == null) {
            throw preconditionRequired();
        }
        if (entityTag.length() < 3
                || entityTag.charAt(0) != '"'
                || entityTag.charAt(entityTag.length() - 1) != '"'
                || entityTag.regionMatches(true, 0, "W/", 0, 2)) {
            throw headerMalformed("/headers/If-Match");
        }
        return entityTag.substring(1, entityTag.length() - 1);
    }

    private static ApiRequestException preconditionRequired() {
        return new ApiRequestException(ProblemCode.PRECONDITION_REQUIRED, "/headers");
    }

    private static ApiRequestException headerMalformed(String pointer) {
        return new ApiRequestException(ProblemCode.REQUEST_MALFORMED, pointer);
    }
}
