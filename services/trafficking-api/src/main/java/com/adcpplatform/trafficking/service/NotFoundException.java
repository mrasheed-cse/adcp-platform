package com.adcpplatform.trafficking.service;

public class NotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public NotFoundException(String what, String id) {
        super(what + " " + id + " not found");
    }
}
