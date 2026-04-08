/*
 * XNAT DICOMweb Plugin
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 */

package org.nrg.xnat.dicomweb.service;

import org.springframework.http.HttpStatus;

/**
 * Exception for STOW-RS operations.
 * Contains HTTP status code for proper error response.
 */
public class StowRsException extends Exception {

    private final HttpStatus httpStatus;

    public StowRsException(String message, HttpStatus httpStatus) {
        super(message);
        this.httpStatus = httpStatus;
    }

    public StowRsException(String message, Throwable cause, HttpStatus httpStatus) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    /**
     * Create a bad request exception
     */
    public static StowRsException badRequest(String message) {
        return new StowRsException(message, HttpStatus.BAD_REQUEST);
    }

    /**
     * Create a forbidden exception
     */
    public static StowRsException forbidden(String message) {
        return new StowRsException(message, HttpStatus.FORBIDDEN);
    }

    /**
     * Create an internal server error exception
     */
    public static StowRsException serverError(String message) {
        return new StowRsException(message, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * Create an internal server error exception with cause
     */
    public static StowRsException serverError(String message, Throwable cause) {
        return new StowRsException(message, cause, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
