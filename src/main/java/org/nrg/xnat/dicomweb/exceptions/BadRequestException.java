/*
 * XNAT DICOMweb Proxy Plugin
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 */

package org.nrg.xnat.dicomweb.exceptions;

/**
 * Exception thrown for malformed requests or invalid parameters (400)
 */
public class BadRequestException extends DicomWebException {

    public BadRequestException(String message) {
        super(message, 400, "BadRequest");
    }

    public BadRequestException(String parameterName, String reason) {
        super(String.format("Invalid parameter '%s': %s", parameterName, reason),
              400, "InvalidParameter");
    }
}
