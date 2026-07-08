package com.example.provisioning.api.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Standard error payload returned for 4xx/5xx responses")
public record ApiErrorResponse(
    @Schema(description = "HTTP status code", example = "400")
    int status,

    @Schema(description = "Short, machine-readable error code", example = "VALIDATION_FAILED")
    String error,

    @Schema(description = "Human-readable error message", example = "Request validation failed")
    String message,

    @Schema(description = "When the error was generated", example = "2026-07-08T12:34:56Z")
    Instant timestamp,

    @Schema(description = "Field-level validation violations; present only for validation errors")
    List<FieldViolation> violations
) {
    @Schema(description = "A single field validation violation")
    public record FieldViolation(
        @Schema(description = "Offending request field", example = "name")
        String field,
        @Schema(description = "Why the field is invalid", example = "must not be blank")
        String message
    ) {
    }

    public static ApiErrorResponse of(int status, String error, String message) {
        return new ApiErrorResponse(status, error, message, Instant.now(), null);
    }

    public static ApiErrorResponse of(int status, String error, String message,
                                      List<FieldViolation> violations) {
        return new ApiErrorResponse(status, error, message, Instant.now(), violations);
    }
}
