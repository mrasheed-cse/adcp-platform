package com.adcpplatform.salesagent.mediabuy;

import com.adcpplatform.salesagent.model.Models.AdcpError;

import java.util.List;

/** Business-rule failure that maps to an AdCP errors[] response rather than a protocol error. */
public class AdcpException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<AdcpError> errors;

    public AdcpException(List<AdcpError> errors) {
        super(errors.isEmpty() ? "AdCP error" : errors.getFirst().message());
        this.errors = List.copyOf(errors);
    }

    public AdcpException(String code, String message, String field) {
        this(List.of(new AdcpError(code, message, field)));
    }

    public List<AdcpError> errors() {
        return errors;
    }
}
