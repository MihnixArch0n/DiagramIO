package com.diagramio.domain.exception;

/** Tài nguyên không tồn tại hoặc không được phép biết là có tồn tại (HTTP 404). */
public non-sealed class NotFoundException extends DomainException {

    public NotFoundException(String message) {
        super(message);
    }
}
