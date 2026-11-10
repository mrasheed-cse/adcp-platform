package com.adcpplatform.trafficking.service;

/** The request conflicts with the current state (stale version, invalid status change). */
public class ConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ConflictException(String message) {
        super(message);
    }
}
