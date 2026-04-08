/*
 * XNAT DICOMweb Plugin
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 */

package org.nrg.xnat.dicomweb.exceptions;

/**
 * Exception thrown when user lacks authorization for a resource (403)
 */
public class ForbiddenException extends DicomWebException {

    public ForbiddenException(String message) {
        super(message, 403, "Forbidden");
    }

    public ForbiddenException(String resourceType, String identifier) {
        super(String.format("Access denied to %s '%s'", resourceType, identifier),
              403, "AccessDenied");
    }
}
