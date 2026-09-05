package com.videogameplatform.api.delivery;

import com.videogameplatform.api.generated.model.ProblemCode;

public final class ApiRequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ProblemCode code;
    private final String pointer;

    public ApiRequestException(ProblemCode code, String pointer) {
        super(code.getValue());
        this.code = code;
        this.pointer = pointer;
    }

    public ProblemCode code() {
        return code;
    }

    public String pointer() {
        return pointer;
    }
}
