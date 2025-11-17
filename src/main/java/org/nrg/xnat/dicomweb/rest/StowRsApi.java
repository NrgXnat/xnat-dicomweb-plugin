/*
 * XNAT DICOMweb Proxy Plugin
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */

package org.nrg.xnat.dicomweb.rest;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.dcm4che3.data.Sequence;
import org.nrg.framework.annotations.XapiRestController;
import org.nrg.xapi.rest.AbstractXapiRestController;
import org.nrg.xapi.rest.XapiRequestMapping;
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.dicomweb.service.XnatDicomService;
import org.nrg.xnat.dicomweb.service.XnatDicomService.StowRsResponse;
import org.nrg.xnat.dicomweb.service.XnatDicomService.InstanceStatus;
import org.nrg.xnat.dicomweb.utils.DicomWebUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMethod;

import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * STOW-RS (STore Over the Web by RESTful Services)
 * Implements DICOMweb storage endpoints
 */
@XapiRestController
@Api("DICOMweb STOW-RS API")
public class StowRsApi extends AbstractXapiRestController {

    private static final Logger logger = LoggerFactory.getLogger(StowRsApi.class);
    private static final int MAX_BUFFER_SIZE = 8192;

    private final XnatDicomService dicomService;

    @Autowired
    public StowRsApi(final XnatDicomService dicomService,
                     final UserManagementServiceI userManagementService,
                     final RoleHolder roleHolder) {
        super(userManagementService, roleHolder);
        this.dicomService = dicomService;
    }

    /**
     * Store DICOM instances (STOW-RS)
     * POST /dicomweb/projects/{projectId}/studies
     */
    @XapiRequestMapping(
        value = "/dicomweb/projects/{projectId}/studies",
        method = RequestMethod.POST,
        consumes = "multipart/related",
        produces = "application/dicom+json"
    )
    @ApiOperation(value = "Store DICOM instances (STOW-RS)", response = String.class)
    @ApiResponses({
        @ApiResponse(code = 200, message = "Instances stored successfully"),
        @ApiResponse(code = 400, message = "Invalid request format"),
        @ApiResponse(code = 401, message = "Authentication required"),
        @ApiResponse(code = 403, message = "Insufficient permissions"),
        @ApiResponse(code = 500, message = "Internal server error")
    })
    public ResponseEntity<String> storeInstances(
            @PathVariable String projectId,
            HttpServletRequest request) {

        try {
            UserI user = getSessionUser();
            logger.info("STOW-RS request from user {} to project {}", user.getLogin(), projectId);

            // Parse multipart request
            List<InputStream> instances = parseMultipartRequest(request);

            if (instances.isEmpty()) {
                logger.warn("No DICOM instances found in STOW-RS request");
                return ResponseEntity.badRequest()
                    .body(createErrorResponse("No DICOM instances in request"));
            }

            logger.info("Parsed {} DICOM instances from multipart request", instances.size());

            // Store instances
            StowRsResponse response = dicomService.storeInstances(user, projectId, instances);

            logger.info("STOW-RS completed: {} successful, {} failed",
                response.getSuccessCount(), response.getFailureCount());

            // Build STOW-RS response
            String jsonResponse = buildStowRsResponse(response, projectId, request);

            return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(DicomWebUtils.getDicomJsonContentType()))
                .body(jsonResponse);

        } catch (SecurityException e) {
            logger.error("Security exception during STOW-RS", e);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(createErrorResponse(e.getMessage()));
        } catch (IllegalArgumentException e) {
            logger.error("Invalid request during STOW-RS", e);
            return ResponseEntity.badRequest()
                .body(createErrorResponse(e.getMessage()));
        } catch (Exception e) {
            logger.error("STOW-RS error", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(createErrorResponse("Internal server error: " + e.getMessage()));
        }
    }

    /**
     * Parse multipart/related request containing DICOM instances
     */
    private List<InputStream> parseMultipartRequest(HttpServletRequest request) throws Exception {
        List<InputStream> streams = new ArrayList<>();
        String contentType = request.getContentType();

        if (contentType == null || !contentType.toLowerCase().startsWith("multipart/related")) {
            throw new IllegalArgumentException("Content-Type must be multipart/related");
        }

        // Extract boundary from Content-Type header
        String boundary = extractBoundary(contentType);
        if (boundary == null) {
            throw new IllegalArgumentException("No boundary found in Content-Type header");
        }

        logger.debug("Parsing multipart request with boundary: {}", boundary);

        // Read and parse multipart body
        ServletInputStream input = request.getInputStream();
        byte[] boundaryBytes = ("--" + boundary).getBytes("US-ASCII");
        byte[] endBoundaryBytes = ("--" + boundary + "--").getBytes("US-ASCII");

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        boolean inPart = false;
        boolean inHeaders = false;
        boolean inBody = false;
        int lineStart = 0;

        // Simple multipart parser
        byte[] data = new byte[MAX_BUFFER_SIZE];
        int bytesRead;

        while ((bytesRead = input.read(data)) != -1) {
            buffer.write(data, 0, bytesRead);
        }

        // Parse the complete buffer
        byte[] fullData = buffer.toByteArray();
        List<byte[]> parts = splitMultipart(fullData, boundaryBytes);

        for (byte[] part : parts) {
            // Skip empty parts
            if (part.length == 0) {
                continue;
            }

            // Find end of headers (double CRLF)
            int headerEnd = findHeaderEnd(part);
            if (headerEnd == -1) {
                continue;
            }

            // Extract headers and body
            String headers = new String(part, 0, headerEnd, "US-ASCII");

            // Check if this part contains DICOM data
            if (headers.toLowerCase().contains("application/dicom")) {
                // Extract body (skip CRLF after headers)
                int bodyStart = headerEnd + 4; // Skip \r\n\r\n
                if (bodyStart < part.length) {
                    // Remove trailing CRLF if present
                    int bodyEnd = part.length;
                    if (bodyEnd >= 2 && part[bodyEnd-2] == '\r' && part[bodyEnd-1] == '\n') {
                        bodyEnd -= 2;
                    }

                    byte[] body = new byte[bodyEnd - bodyStart];
                    System.arraycopy(part, bodyStart, body, 0, body.length);
                    streams.add(new ByteArrayInputStream(body));
                    logger.debug("Extracted DICOM part with {} bytes", body.length);
                }
            }
        }

        return streams;
    }

    /**
     * Split multipart data by boundary
     */
    private List<byte[]> splitMultipart(byte[] data, byte[] boundary) {
        List<byte[]> parts = new ArrayList<>();
        int pos = 0;

        while (pos < data.length) {
            // Find next boundary
            int boundaryPos = indexOf(data, boundary, pos);
            if (boundaryPos == -1) {
                break;
            }

            // Skip the boundary
            int partStart = boundaryPos + boundary.length;

            // Skip CRLF after boundary
            if (partStart + 1 < data.length && data[partStart] == '\r' && data[partStart + 1] == '\n') {
                partStart += 2;
            }

            // Find next boundary
            int nextBoundary = indexOf(data, boundary, partStart);
            if (nextBoundary == -1) {
                // Last part - find end boundary
                byte[] endBoundary = (new String(boundary, java.nio.charset.StandardCharsets.US_ASCII) + "--").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                nextBoundary = indexOf(data, endBoundary, partStart);
                if (nextBoundary == -1) {
                    nextBoundary = data.length;
                }
            }

            // Extract part
            if (nextBoundary > partStart) {
                byte[] part = new byte[nextBoundary - partStart];
                System.arraycopy(data, partStart, part, 0, part.length);
                parts.add(part);
            }

            pos = nextBoundary;
        }

        return parts;
    }

    /**
     * Find byte pattern in byte array
     */
    private int indexOf(byte[] data, byte[] pattern, int start) {
        for (int i = start; i <= data.length - pattern.length; i++) {
            boolean found = true;
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    found = false;
                    break;
                }
            }
            if (found) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Find end of headers (double CRLF)
     */
    private int findHeaderEnd(byte[] data) {
        for (int i = 0; i < data.length - 3; i++) {
            if (data[i] == '\r' && data[i+1] == '\n' &&
                data[i+2] == '\r' && data[i+3] == '\n') {
                return i;
            }
        }
        return -1;
    }

    /**
     * Extract boundary from Content-Type header
     */
    private String extractBoundary(String contentType) {
        String[] parts = contentType.split(";");
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.startsWith("boundary=")) {
                String boundary = trimmed.substring(9);
                // Remove quotes if present
                if (boundary.startsWith("\"") && boundary.endsWith("\"")) {
                    boundary = boundary.substring(1, boundary.length() - 1);
                }
                return boundary;
            }
        }
        return null;
    }

    /**
     * Build DICOM JSON response per STOW-RS specification
     */
    private String buildStowRsResponse(StowRsResponse response, String projectId, HttpServletRequest request) {
        try {
            Attributes attrs = new Attributes();

            // Build base URL for retrieval
            String baseUrl = request.getRequestURL().toString().replace("/studies", "");

            // Add retrieve URL (00081190) - only if there were successes
            if (response.getSuccessCount() > 0) {
                String studyUID = getFirstStudyUID(response);
                if (studyUID != null) {
                    attrs.setString(Tag.RetrieveURL, VR.UR, baseUrl + "/studies/" + studyUID);
                }
            }

            // Add Referenced SOP Sequence (00081199) - successful instances
            Sequence refSeq = attrs.newSequence(Tag.ReferencedSOPSequence, response.getSuccessCount());
            for (InstanceStatus status : response.getInstanceStatuses()) {
                if (status.isSuccess()) {
                    Attributes item = new Attributes();
                    item.setString(Tag.ReferencedSOPClassUID, VR.UI, status.getSopClassUID());
                    item.setString(Tag.ReferencedSOPInstanceUID, VR.UI, status.getSopInstanceUID());
                    item.setString(Tag.RetrieveURL, VR.UR,
                        baseUrl + "/studies/{studyUID}/series/{seriesUID}/instances/" + status.getSopInstanceUID());
                    refSeq.add(item);
                }
            }

            // Add Failed SOP Sequence (00081198) - failed instances
            Sequence failedSeq = attrs.newSequence(Tag.FailedSOPSequence, response.getFailureCount());
            for (InstanceStatus status : response.getInstanceStatuses()) {
                if (!status.isSuccess()) {
                    Attributes item = new Attributes();
                    if (status.getSopClassUID() != null) {
                        item.setString(Tag.ReferencedSOPClassUID, VR.UI, status.getSopClassUID());
                    }
                    if (status.getSopInstanceUID() != null) {
                        item.setString(Tag.ReferencedSOPInstanceUID, VR.UI, status.getSopInstanceUID());
                    }
                    item.setInt(Tag.FailureReason, VR.US, status.getWarningCode());
                    failedSeq.add(item);
                }
            }

            return DicomWebUtils.toJson(attrs);

        } catch (Exception e) {
            logger.error("Error building STOW-RS response", e);
            return "{\"error\": \"Failed to build response\"}";
        }
    }

    /**
     * Get Study Instance UID from first successful instance
     */
    private String getFirstStudyUID(StowRsResponse response) {
        for (InstanceStatus status : response.getInstanceStatuses()) {
            if (status.isSuccess() && status.getSopInstanceUID() != null) {
                // In a real implementation, we would track the StudyInstanceUID
                // For now, return null and let the client determine it
                return null;
            }
        }
        return null;
    }

    /**
     * Create error response in JSON format
     */
    private String createErrorResponse(String message) {
        return String.format("{\"error\": \"%s\"}", message.replace("\"", "\\\""));
    }
}
