package com.diagramio.domain.exception;

/** Gốc của mọi ngoại lệ nghiệp vụ. Tầng API đổi chúng thành mã HTTP. */
public abstract sealed class DomainException extends RuntimeException
        permits ConflictException, ForbiddenException, InvalidInputException,
                NotFoundException, UnauthorizedException {

    protected DomainException(String message) {
        super(message);
    }
}
