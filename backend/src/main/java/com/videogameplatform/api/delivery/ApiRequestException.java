package com.videogameplatform.api.delivery;

final class ApiRequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final String pointer;

    ApiRequestException(String code, String pointer) {
        super(code);
        this.code = code;
        this.pointer = pointer;
    }

    String code() {
        return code;
    }

    String pointer() {
        return pointer;
    }
}
