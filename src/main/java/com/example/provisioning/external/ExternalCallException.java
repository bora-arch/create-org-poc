package com.example.provisioning.external;

import lombok.Getter;

/**
 * Thrown by {@link ExternalOrganizationClient} implementations when a
 * downstream call fails. Carries a machine-readable {@code errorCode}
 * (HTTP status-shaped in the mock) and a human message.
 */
@Getter
public class ExternalCallException extends RuntimeException {

    private final String errorCode;

    public ExternalCallException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
