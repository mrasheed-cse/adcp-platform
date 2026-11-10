package com.adcpplatform.trafficking.rules;

/** One invalid field; field uses the API's snake_case name. */
public record FieldError(String field, String message) {}
