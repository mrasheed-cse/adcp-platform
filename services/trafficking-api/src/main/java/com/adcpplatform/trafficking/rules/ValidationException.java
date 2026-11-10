package com.adcpplatform.trafficking.rules;

import java.util.List;

/** Business-rule failure, returned to the caller as 400 with every invalid field listed. */
public class ValidationException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final transient List<FieldError> errors;

    public ValidationException(List<FieldError> errors) {
        super(errors.isEmpty() ? "validation failed" : errors.getFirst().field() + ": " + errors.getFirst().message());
        this.errors = List.copyOf(errors);
    }

    public ValidationException(String field, String message) {
        this(List.of(new FieldError(field, message)));
    }

    public List<FieldError> errors() { return errors; }

    public static void throwIfAny(List<FieldError> errors) {
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
    }
}
