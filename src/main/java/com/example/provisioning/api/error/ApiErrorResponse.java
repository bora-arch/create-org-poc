package com.example.provisioning.api.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(
    int status,
    String error,
    String message,
    Instant timestamp,
    List<FieldViolation> violations
) {
    public record FieldViolation(String field, String message) {
    }

    public static ApiErrorResponse of(int status, String error, String message) {
        return new ApiErrorResponse(status, error, message, Instant.now(), null);
    }

    public static ApiErrorResponse of(int status, String error, String message,
                                      List<FieldViolation> violations) {
        return new ApiErrorResponse(status, error, message, Instant.now(), violations);
    }
}
