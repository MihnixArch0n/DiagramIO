package com.diagramio.domain.exception;

/** Dữ liệu vi phạm quy tắc nghiệp vụ, ví dụ JSON sơ đồ quá 2 MB (HTTP 400). */
public non-sealed class InvalidInputException extends DomainException {

    public InvalidInputException(String message) {
        super(message);
    }
}
