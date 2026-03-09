/*
 * XNAT DICOMweb Proxy Plugin
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 */

package org.nrg.xnat.dicomweb.exceptions;

/**
 * Exception thrown when content negotiation fails — no acceptable media type
 * can be provided for the requested resource (406 Not Acceptable).
 */
public class NotAcceptableException extends DicomWebException {
    public NotAcceptableException(String message) {
        super(message, 406, "NotAcceptable");
    }
}
