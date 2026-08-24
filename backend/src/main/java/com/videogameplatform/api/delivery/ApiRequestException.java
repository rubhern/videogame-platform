package com.videogameplatform.api.delivery;

import com.videogameplatform.api.generated.model.ProblemCode;

final class ApiRequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ProblemCode code;
    private final String pointer;

    ApiRequestException(ProblemCode code, String pointer) {
        super(code.getValue());
        this.code = code;
        this.pointer = pointer;
    }

    ProblemCode code() {
        return code;
    }

    String pointer() {
        return pointer;
    }
}
