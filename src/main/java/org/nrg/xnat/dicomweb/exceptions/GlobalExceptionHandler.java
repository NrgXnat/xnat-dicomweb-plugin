/*
 * XNAT DICOMweb Plugin
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 */

package org.nrg.xnat.dicomweb.exceptions;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.nrg.xnat.dicomweb.service.StowRsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Global exception handler for DICOMweb API controllers
 * Converts exceptions to structured error responses
 */
@ControllerAdvice(basePackages = "org.nrg.xnat.dicomweb.rest")
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Handle custom DICOMweb exceptions
     */
    @ExceptionHandler(DicomWebException.class)
    public ResponseEntity<String> handleDicomWebException(DicomWebException ex, HttpServletRequest request) {
        String requestId = getRequestId();

        logger.debug("DICOMweb error [{}]: {} - {}", requestId, ex.getErrorCode(), ex.getMessage());

        ErrorResponse errorResponse = new ErrorResponse(
            ex.getErrorCode(),
            ex.getMessage(),
            ex.getHttpStatus(),
            requestId,
            request.getRequestURI()
        );

        return ResponseEntity
            .status(ex.getHttpStatus())
            .header("X-Request-ID", requestId)
            .header("Content-Type", "application/json")
            .body(toJson(errorResponse));
    }

    /**
     * Handle STOW-RS specific exceptions
     */
    @ExceptionHandler(StowRsException.class)
    public ResponseEntity<String> handleStowRsException(StowRsException ex, HttpServletRequest request) {
        String requestId = getRequestId();

        logger.debug("STOW-RS error [{}]: {}", requestId, ex.getMessage());

        ErrorResponse errorResponse = new ErrorResponse(
            "StowRsError",
            ex.getMessage(),
            ex.getHttpStatus().value(),
            requestId,
            request.getRequestURI()
        );

        return ResponseEntity
            .status(ex.getHttpStatus())
            .header("X-Request-ID", requestId)
            .header("Content-Type", "application/json")
            .body(toJson(errorResponse));
    }

    /**
     * Handle UnsupportedOperationException (e.g., missing native libraries for advanced DICOM compression)
     */
    @ExceptionHandler(UnsupportedOperationException.class)
    public ResponseEntity<String> handleUnsupportedOperationException(UnsupportedOperationException ex, HttpServletRequest request) {
        String requestId = getRequestId();

        logger.error("Unsupported operation [{}]: {}", requestId, ex.getMessage());

        ErrorResponse errorResponse = new ErrorResponse(
            "UnsupportedOperation",
            ex.getMessage(),
            HttpStatus.NOT_IMPLEMENTED.value(),
            requestId,
            request.getRequestURI()
        );

        return ResponseEntity
            .status(HttpStatus.NOT_IMPLEMENTED)
            .header("X-Request-ID", requestId)
            .header("Content-Type", "application/json")
            .body(toJson(errorResponse));
    }

    /**
     * Handle generic exceptions
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleGenericException(Exception ex, HttpServletRequest request) {
        String requestId = getRequestId();

        logger.warn("Unexpected error [{}]: {}", requestId, ex.getMessage(), ex);

        ErrorResponse errorResponse = new ErrorResponse(
            "InternalError",
            "An internal error occurred",
            500,
            requestId,
            request.getRequestURI()
        );

        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .header("X-Request-ID", requestId)
            .header("Content-Type", "application/json")
            .body(toJson(errorResponse));
    }

    /**
     * Get or generate request correlation ID
     */
    private String getRequestId() {
        String requestId = MDC.get("requestId");
        if (requestId == null) {
            requestId = java.util.UUID.randomUUID().toString();
        }
        return requestId;
    }

    /**
     * Convert error response to JSON
     */
    private String toJson(ErrorResponse errorResponse) {
        try {
            return objectMapper.writeValueAsString(errorResponse);
        } catch (Exception e) {
            logger.error("Failed to serialize error response", e);
            return String.format("{\"error\":\"InternalError\",\"message\":\"Failed to serialize error\",\"status\":500,\"requestId\":\"%s\"}",
                errorResponse.getRequestId());
        }
    }
}
