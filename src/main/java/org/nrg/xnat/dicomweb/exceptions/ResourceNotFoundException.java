/*
 * XNAT DICOMweb Plugin
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 */

package org.nrg.xnat.dicomweb.exceptions;

/**
 * Exception thrown when a requested DICOM resource is not found (404)
 */
public class ResourceNotFoundException extends DicomWebException {

    public ResourceNotFoundException(String resourceType, String identifier) {
        super(String.format("%s with identifier '%s' not found", resourceType, identifier),
              404, resourceType + "NotFound");
    }

    public ResourceNotFoundException(String message) {
        super(message, 404, "ResourceNotFound");
    }
}
