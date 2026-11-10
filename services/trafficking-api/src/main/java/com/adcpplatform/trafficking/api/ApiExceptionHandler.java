package com.adcpplatform.trafficking.api;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.adcpplatform.trafficking.rules.FieldError;
import com.adcpplatform.trafficking.rules.ValidationException;
import com.adcpplatform.trafficking.service.ConflictException;
import com.adcpplatform.trafficking.service.NotFoundException;

/** Errors as RFC 9457 problem details; validation errors list every invalid field. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ValidationException.class)
    public ProblemDetail validation(ValidationException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Some fields are invalid");
        p.setProperty("errors", e.errors());
        return p;
    }

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail notFound(NotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ProblemDetail conflict(ConflictException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail concurrentEdit(OptimisticLockingFailureException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Changed by someone else at the same time; reload and retry");
    }

    /** Two creates with the same external_ref at the same moment: the loser retries and gets the winner's row. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail duplicate(DataIntegrityViolationException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Conflicts with existing data (for example a duplicate id or external_ref); retry");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail unreadable(HttpMessageNotReadableException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request body is not valid JSON for this endpoint");
        p.setProperty("errors", List.of(new FieldError("body", "check field names and types (dates are ISO 8601)")));
        return p;
    }
}
