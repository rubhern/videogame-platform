package com.videogameplatform.api.delivery.feedback;

import com.videogameplatform.api.delivery.ApiRequestException;
import com.videogameplatform.api.generated.model.FeedbackSubmission;
import com.videogameplatform.api.generated.model.ProblemCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

/** Bounds even chunked bodies and prevents coercion of non-string command fields. */
@ControllerAdvice(assignableTypes = FeedbackController.class)
class FeedbackBodyLimit extends RequestBodyAdviceAdapter {

    private static final int MAX_BYTES = 32 * 1024;
    private final ObjectReader json;

    FeedbackBodyLimit(ObjectMapper mapper) {
        json =
                mapper.reader()
                        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
    }

    @Override
    public boolean supports(
            MethodParameter parameter,
            Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType) {
        return FeedbackSubmission.class.equals(targetType);
    }

    @Override
    public HttpInputMessage beforeBodyRead(
            HttpInputMessage input,
            MethodParameter parameter,
            Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType)
            throws IOException {
        if (input.getHeaders().getContentLength() > MAX_BYTES) {
            throw tooLarge();
        }
        byte[] bytes = input.getBody().readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) {
            throw tooLarge();
        }

        try {
            var object = json.readTree(bytes);
            if (!object.isObject()) {
                throw malformed();
            }
            for (String field : new String[] {"category", "message", "email"}) {
                var value = object.get(field);
                if (value != null && !value.isString()) {
                    throw new ApiRequestException(ProblemCode.FEEDBACK_INVALID, "/body/" + field);
                }
            }
        } catch (tools.jackson.core.JacksonException invalidJson) {
            throw malformed();
        }

        return new HttpInputMessage() {
            @Override
            public HttpHeaders getHeaders() {
                return input.getHeaders();
            }

            @Override
            public InputStream getBody() {
                return new ByteArrayInputStream(bytes);
            }
        };
    }

    private static ApiRequestException tooLarge() {
        return new ApiRequestException(ProblemCode.REQUEST_TOO_LARGE, "/body");
    }

    private static ApiRequestException malformed() {
        return new ApiRequestException(ProblemCode.REQUEST_MALFORMED, "/body");
    }
}
