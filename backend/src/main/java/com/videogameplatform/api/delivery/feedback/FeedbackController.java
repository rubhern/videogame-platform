package com.videogameplatform.api.delivery.feedback;

import com.videogameplatform.api.delivery.ApiRequestException;
import com.videogameplatform.api.generated.FeedbackApi;
import com.videogameplatform.api.generated.model.FeedbackSubmission;
import com.videogameplatform.api.generated.model.ProblemCode;
import com.videogameplatform.feedback.application.SubmitFeedback;
import com.videogameplatform.feedback.application.SubmitFeedback.Category;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class FeedbackController implements FeedbackApi {

    private final SubmitFeedback feedback;
    private final HttpServletRequest request;

    FeedbackController(SubmitFeedback feedback, HttpServletRequest request) {
        this.feedback = feedback;
        this.request = request;
    }

    @Override
    public ResponseEntity<Void> submitFeedback(FeedbackSubmission submission) {
        if (request.getQueryString() != null) {
            throw new ApiRequestException(ProblemCode.REQUEST_PARAMETER_UNKNOWN, "/query");
        }
        Category category =
                submission.getCategory() == null
                        ? null
                        : Category.valueOf(submission.getCategory().name());
        return switch (feedback.submit(category, submission.getMessage(), submission.getEmail())) {
            case ACCEPTED ->
                    ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
            case INVALID_CATEGORY -> throw invalid("/body/category");
            case INVALID_MESSAGE -> throw invalid("/body/message");
            case INVALID_EMAIL -> throw invalid("/body/email");
            case BUSY -> throw new ApiRequestException(ProblemCode.SERVICE_OVERLOADED, "/request");
            case UNAVAILABLE ->
                    throw new ApiRequestException(ProblemCode.FEEDBACK_UNAVAILABLE, "/request");
        };
    }

    private static ApiRequestException invalid(String pointer) {
        return new ApiRequestException(ProblemCode.FEEDBACK_INVALID, pointer);
    }
}
