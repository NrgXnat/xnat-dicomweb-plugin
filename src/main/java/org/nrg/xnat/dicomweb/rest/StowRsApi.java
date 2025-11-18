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
import org.springframework.http.HttpEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMethod;

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
        produces = "application/dicom+json",
        consumes = "*/*"
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
            HttpEntity<byte[]> requestEntity,
            HttpServletRequest request) throws Exception {

        try {
            UserI user = getSessionUser();
            logger.info("STOW-RS request from user {} to project {}", user.getLogin(), projectId);

            // Get request body from HttpEntity - Spring handles the reading
            byte[] requestBody = requestEntity.getBody();

            if (requestBody == null) {
                requestBody = new byte[0];
            }

            System.out.println("=== STOW-RS: Read " + requestBody.length + " bytes from HttpEntity");

            // Parse multipart request from raw bytes
            List<InputStream> instances = parseMultipartRequest(requestBody, request.getContentType());

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
     * Parse multipart/related request containing DICOM instances from raw bytes
     * This receives the raw request body before Spring's multipart resolver consumes it
     */
    private List<InputStream> parseMultipartRequest(byte[] requestBody, String contentType) throws Exception {
        List<InputStream> streams = new ArrayList<>();

        if (contentType == null || !contentType.toLowerCase().startsWith("multipart/")) {
            throw new IllegalArgumentException("Content-Type must be multipart/related or multipart/*");
        }

        System.out.println("=== STOW-RS DEBUG: Content-Type = " + contentType);
        System.out.println("=== STOW-RS DEBUG: Request body size = " + requestBody.length + " bytes");
        logger.info("STOW-RS: Parsing multipart request ({} bytes)", requestBody.length);

        // Extract boundary from Content-Type header
        String boundary = extractBoundary(contentType);
        if (boundary == null) {
            throw new IllegalArgumentException("No boundary found in Content-Type header");
        }

        System.out.println("=== STOW-RS DEBUG: Boundary = " + boundary);
        logger.info("STOW-RS: Using boundary: {}", boundary);

        // Split multipart data by boundary
        List<byte[]> parts = splitMultipart(requestBody, ("--" + boundary).getBytes(java.nio.charset.StandardCharsets.US_ASCII));

        System.out.println("=== STOW-RS DEBUG: Found " + parts.size() + " parts");
        logger.info("STOW-RS: Split into {} parts", parts.size());

        // Process each part
        int dicomCount = 0;
        for (int i = 0; i < parts.size(); i++) {
            byte[] part = parts.get(i);

            // Find end of headers (double CRLF)
            int headerEnd = findHeaderEnd(part);
            if (headerEnd == -1) {
                System.out.println("=== STOW-RS DEBUG: Part " + (i + 1) + " - No header end found, skipping");
                continue;
            }

            // Extract headers
            String headers = new String(part, 0, headerEnd, java.nio.charset.StandardCharsets.US_ASCII);
            System.out.println("=== STOW-RS DEBUG: Part " + (i + 1) + " - Headers: " + headers.replace("\r\n", " | "));

            // Check if this part contains DICOM data
            if (headers.toLowerCase().contains("application/dicom")) {
                // Extract body (skip headers + double CRLF)
                int bodyStart = headerEnd + 4; // Skip \r\n\r\n
                int bodyLength = part.length - bodyStart;

                // Remove trailing CRLF if present
                while (bodyLength > 0 && (part[bodyStart + bodyLength - 1] == '\n' || part[bodyStart + bodyLength - 1] == '\r')) {
                    bodyLength--;
                }

                if (bodyLength > 0) {
                    byte[] dicomData = new byte[bodyLength];
                    System.arraycopy(part, bodyStart, dicomData, 0, bodyLength);

                    System.out.println("=== STOW-RS DEBUG: Part " + (i + 1) + " - Extracted " + dicomData.length + " bytes of DICOM data");

                    // Verify it's actually DICOM data (starts with 128-byte preamble + "DICM")
                    if (dicomData.length > 132) {
                        boolean hasDICM = dicomData[128] == 'D' &&
                                         dicomData[129] == 'I' &&
                                         dicomData[130] == 'C' &&
                                         dicomData[131] == 'M';
                        System.out.println("=== STOW-RS DEBUG: Part " + (i + 1) + " - DICM marker check: " + hasDICM);
                    }

                    streams.add(new ByteArrayInputStream(dicomData));
                    dicomCount++;
                    logger.info("STOW-RS: Extracted DICOM part {} with {} bytes", dicomCount, dicomData.length);
                }
            } else {
                System.out.println("=== STOW-RS DEBUG: Part " + (i + 1) + " - Skipping non-DICOM part");
            }
        }

        System.out.println("=== STOW-RS DEBUG: Total DICOM instances found: " + dicomCount);
        logger.info("STOW-RS: Found {} DICOM instances", dicomCount);
        return streams;
    }

    /**
     * Split multipart data by boundary
     */
    private List<byte[]> splitMultipart(byte[] data, byte[] boundary) {
        List<byte[]> parts = new ArrayList<>();
        int pos = 0;

        logger.info("splitMultipart: data length={}, boundary length={}, boundary='{}'",
            data.length, boundary.length, new String(boundary, java.nio.charset.StandardCharsets.US_ASCII));

        // Show first 200 bytes for debugging
        int previewLen = Math.min(200, data.length);
        String preview = new String(data, 0, previewLen, java.nio.charset.StandardCharsets.US_ASCII)
            .replace("\r", "\\r").replace("\n", "\\n");
        logger.info("Data preview (first {} bytes): {}", previewLen, preview);

        while (pos < data.length) {
            // Find next boundary
            int boundaryPos = indexOf(data, boundary, pos);
            logger.debug("Searching for boundary from pos {}, found at: {}", pos, boundaryPos);
            if (boundaryPos == -1) {
                logger.info("No more boundaries found");
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
                int partLen = nextBoundary - partStart;
                byte[] part = new byte[partLen];
                System.arraycopy(data, partStart, part, 0, part.length);
                parts.add(part);
                logger.info("Extracted part {} with {} bytes", parts.size(), partLen);

                // Show part preview
                int partPreviewLen = Math.min(100, partLen);
                String partPreview = new String(part, 0, partPreviewLen, java.nio.charset.StandardCharsets.US_ASCII)
                    .replace("\r", "\\r").replace("\n", "\\n");
                logger.debug("Part preview: {}", partPreview);
            }

            pos = nextBoundary;
        }

        logger.info("splitMultipart completed: found {} parts", parts.size());
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
