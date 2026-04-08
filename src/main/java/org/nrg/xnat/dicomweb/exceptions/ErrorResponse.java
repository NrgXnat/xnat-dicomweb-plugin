/*
 * XNAT DICOMweb Plugin
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 */

package org.nrg.xnat.dicomweb.exceptions;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Structured error response for DICOMweb API
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    private final String error;
    private final String message;
    private final int status;
    private final String requestId;
    private final String timestamp;
    private final String path;

    public ErrorResponse(String error, String message, int status, String requestId, String path) {
        this.error = error;
        this.message = message;
        this.status = status;
        this.requestId = requestId;
        this.timestamp = java.time.Instant.now().toString();
        this.path = path;
    }

    public String getError() {
        return error;
    }

    public String getMessage() {
        return message;
    }

    public int getStatus() {
        return status;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public String getPath() {
        return path;
    }
}
