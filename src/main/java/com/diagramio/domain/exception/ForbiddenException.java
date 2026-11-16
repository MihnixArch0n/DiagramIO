package com.diagramio.domain.exception;

/** Đã xác định được người dùng nhưng không đủ quyền (HTTP 403). */
public non-sealed class ForbiddenException extends DomainException {

    public ForbiddenException(String message) {
        super(message);
    }
}
