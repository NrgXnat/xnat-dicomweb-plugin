/*
 * XNAT DICOMweb Proxy Plugin
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */

package org.nrg.xnat.dicomweb.rest;

import com.google.common.collect.Sets;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.nrg.action.ClientException;
import org.nrg.action.ServerException;
import org.nrg.framework.annotations.XapiRestController;
import org.nrg.xapi.rest.AbstractXapiRestController;
import org.nrg.xapi.rest.XapiRequestMapping;
import org.nrg.xdat.XDAT;
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.archive.GradualDicomImporter;
import org.nrg.xnat.archive.Operation;
import org.nrg.xnat.dicomweb.helpers.InputStreamFileWriterWrapper;
import org.nrg.xnat.dicomweb.parser.Mime4jHybridParser;
import org.nrg.xnat.dicomweb.parser.Mime4jHybridParser.MultipartPart;
import org.nrg.xnat.dicomweb.utils.DicomWebUtils;
import org.nrg.xnat.helpers.prearchive.PrearcDatabase;
import org.nrg.xnat.helpers.prearchive.PrearcSession;
import org.nrg.xnat.helpers.prearchive.PrearcUtils;
import org.nrg.xnat.helpers.prearchive.SessionData;
import org.nrg.xnat.helpers.prearchive.handlers.PrearchiveOperationHandlerResolver;
import org.nrg.xnat.helpers.prearchive.handlers.PrearchiveRebuildHandler;
import org.nrg.xnat.helpers.prearchive.handlers.PrearchiveSeparatePetMrHandler;
import org.nrg.xnat.helpers.uri.URIManager;
import org.nrg.xnat.restlet.util.FileWriterWrapperI;
import org.nrg.xnat.restlet.util.RequestUtil;
import org.nrg.xnat.services.messaging.prearchive.PrearchiveOperationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMethod;

import javax.servlet.http.HttpServletRequest;
import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * STOW-RS (STore Over the Web by RESTful Services)
 * Implements DICOMweb storage endpoints
 */
@XapiRestController
@Api("DICOMweb STOW-RS API")
public class StowRsApi extends AbstractXapiRestController {

    private static final Logger logger = LoggerFactory.getLogger(StowRsApi.class);
    private static final String SLASH = "/";
    private static final String ACTION = "action";
    private static final String COMMIT = "commit";

    private final Mime4jHybridParser multipartParser;

    @Autowired
    public StowRsApi(final UserManagementServiceI userManagementService,
                     final RoleHolder roleHolder) {
        super(userManagementService, roleHolder);
        this.multipartParser = new Mime4jHybridParser();
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
            HttpServletRequest request) throws Exception {

        List<MultipartPart> parts = null;
        Set<String> prearchiveUris = Sets.newLinkedHashSet();

        try {
            UserI user = getSessionUser();
            logger.info("STOW-RS request from user {} to project {}", user.getLogin(), projectId);
            logger.debug("Content-Type: {}, Content-Length: {}",
                request.getContentType(), request.getContentLength());

            // Read request body to byte array
            // Note: XNAT core's WebConfig skips multipart/related from being consumed by Spring
            // We read to byte[] because InputStream.available() may return 0 for some implementations
            byte[] requestBody;
            try (java.io.InputStream inputStream = request.getInputStream();
                 java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream()) {
                byte[] chunk = new byte[8192];
                int bytesRead;
                while ((bytesRead = inputStream.read(chunk)) != -1) {
                    buffer.write(chunk, 0, bytesRead);
                }
                requestBody = buffer.toByteArray();
            }
            logger.debug("Read request body: {} bytes", requestBody.length);

            // Parse multipart/related request
            parts = multipartParser.parse(request.getContentType(), requestBody);

            if (parts.isEmpty()) {
                logger.warn("No parts found in STOW-RS request");
                return ResponseEntity.badRequest()
                    .body(createErrorResponse("No DICOM instances in request"));
            }

            logger.info("Parsed {} parts from multipart request ({} in memory, {} on disk)",
                parts.size(),
                parts.stream().filter(MultipartPart::isInMemory).count(),
                parts.stream().filter(p -> !p.isInMemory()).count());

            // Prepare parameters for import
            Map<String, Object> params = prepareImportParams(projectId, request);

            // Import each DICOM instance to prearchive
            logger.info("Importing {} DICOM instances to prearchive", parts.size());
            for (int i = 0; i < parts.size(); i++) {
                MultipartPart part = parts.get(i);
                try {
                    importInstance(user, part, i, params, prearchiveUris);
                } catch (ClientException | ServerException e) {
                    logger.error("Failed to import instance {}: {}", i, e.getMessage(), e);
                    // Continue with other instances
                }
            }

            if (prearchiveUris.isEmpty()) {
                logger.warn("No instances were successfully imported");
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createErrorResponse("Failed to import any DICOM instances"));
            }

            logger.info("Successfully imported {} sessions to prearchive", prearchiveUris.size());

            // Build sessions
            logger.info("Building XML for {} DICOM sessions", prearchiveUris.size());
            Set<String> archiveUrls = buildSessions(user, prearchiveUris, params);

            // Build STOW-RS response
            String jsonResponse = buildStowRsResponse(prearchiveUris, archiveUrls, projectId, request);

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
        } finally {
            // Clean up resources (critical for disk-based parts)
            if (parts != null) {
                multipartParser.cleanup(parts);
            }
        }
    }

    /**
     * Prepare parameters for DICOM import
     * Similar to DicomZipImporter.determiningDestination()
     */
    private Map<String, Object> prepareImportParams(String projectId, HttpServletRequest request) {
        Map<String, Object> params = new HashMap<>();

        // Set project
        params.put(URIManager.PROJECT_ID, projectId);

        // Set action to commit (build sessions automatically)
        params.put(ACTION, COMMIT);

        // Auto-archive is not enabled by default for STOW-RS
        // Can be enabled via request parameter if needed
        params.put(RequestUtil.AA, "false");
        params.put(RequestUtil.AUTO_ARCHIVE, "false");

        return params;
    }

    /**
     * Import a single DICOM instance using GradualDicomImporter
     * Similar to DicomZipImporter.importEntry()
     */
    private void importInstance(UserI user, MultipartPart part, int index,
                                Map<String, Object> params, Set<String> uris)
            throws ServerException, ClientException {

        // Create FileWriterWrapper for this part
        FileWriterWrapperI fw = new InputStreamFileWriterWrapper(part, index);
        logger.debug("Importing DICOM instance: {} ({} bytes)", fw.getName(), part.getSize());

        // Create GradualDicomImporter
        final GradualDicomImporter importer = new GradualDicomImporter(
            getClass().getName(),
            user,
            fw,
            params
        );

        // Set the DICOM object identifier (required for import to work)
        // Using reflection to avoid compile-time dependency on DicomObjectIdentifier class
        try {
            @SuppressWarnings("rawtypes")
            Object identifier = XDAT.getContextService().getBean("dicomObjectIdentifier");
            // Find setIdentifier method by name
            java.lang.reflect.Method setIdentifierMethod = null;
            for (java.lang.reflect.Method method : importer.getClass().getMethods()) {
                if ("setIdentifier".equals(method.getName()) && method.getParameterCount() == 1) {
                    setIdentifierMethod = method;
                    break;
                }
            }
            if (setIdentifierMethod != null) {
                setIdentifierMethod.invoke(importer, identifier);
            } else {
                logger.warn("Could not find setIdentifier method on GradualDicomImporter");
            }
        } catch (Exception e) {
            logger.error("Failed to set DicomObjectIdentifier bean", e);
            throw new ServerException("DICOM import configuration error: " + e.getMessage(), e);
        }

        // Import and collect URIs
        try {
            List<String> importedUris = importer.call();
            if (importedUris != null) {
                uris.addAll(importedUris);
                logger.debug("Successfully imported to: {}", importedUris);
            } else {
                logger.warn("Import returned null URIs");
            }
        } catch (Exception e) {
            logger.error("Failed to import DICOM instance: {}", e.getMessage());
            throw new ServerException("DICOM import failed: " + e.getMessage(), e);
        }
    }

    /**
     * Build sessions (rebuild prearchive sessions)
     * Similar to DicomZipImporter.xmlBuild()
     */
    private Set<String> buildSessions(UserI user, Set<String> prearchiveUris,
                                      Map<String, Object> params) throws ClientException {
        Set<String> archiveUrls = new HashSet<>();
        final boolean override = getBooleanParameter(params, PrearchiveOperationRequest.PARAM_OVERRIDE_EXCEPTIONS);
        final boolean appendMerge = getBooleanParameter(params, PrearchiveOperationRequest.PARAM_ALLOW_SESSION_MERGE);

        PrearchiveOperationHandlerResolver resolver =
            XDAT.getContextService().getBean(PrearchiveOperationHandlerResolver.class);

        for (String sessionUri : prearchiveUris) {
            String[] elements = sessionUri.split(SLASH);
            if (elements.length < 6) {
                logger.warn("Invalid prearchive URI format: {}", sessionUri);
                continue;
            }

            try {
                // Get session data from prearchive
                final SessionData sessionData = PrearcDatabase.getSession(
                    elements[5],  // session name
                    elements[4],  // timestamp
                    elements[3]   // project
                );

                // Create rebuild request
                PrearchiveOperationRequest request = new PrearchiveOperationRequest(
                    user,
                    Operation.Rebuild,
                    sessionData,
                    new File(sessionData.getUrl()),
                    populateAdditionalValues(params)
                );

                // Get rebuild handler and rebuild
                PrearchiveRebuildHandler handler =
                    (PrearchiveRebuildHandler) resolver.getHandler(request);
                boolean buildSuccessful = handler.rebuild();

                if (buildSuccessful) {
                    try {
                        // Check if PET/MR separation is needed
                        final boolean isSeparatePetMr = handler.needToHandleSeparablePetMrSession();
                        if (isSeparatePetMr) {
                            PrearchiveOperationRequest separateRequest = new PrearchiveOperationRequest(
                                user,
                                Operation.Separate,
                                sessionData,
                                new File(sessionData.getUrl()),
                                populateAdditionalValues(params)
                            );
                            PrearchiveSeparatePetMrHandler separatePetMrHandler =
                                (PrearchiveSeparatePetMrHandler) resolver.getHandler(separateRequest);
                            List<PrearchiveOperationRequest> requestList = separatePetMrHandler.separate();

                            if (requestList != null) {
                                for (PrearchiveOperationRequest r : requestList) {
                                        archiveSession(archiveUrls, override, appendMerge, r, user);
                                }
                            }
                        } else {
                            handler.postBuild();
                                archiveSession(archiveUrls, override, appendMerge, request, user);
                        }
                    } catch (Exception e) {
                        logger.error("Error handling session after build: {}", sessionUri, e);
                    }
                }
            } catch (Exception e) {
                logger.error("Unable to build/archive prearchive session: {}", sessionUri, e);
                throw new ClientException("Unable to build prearchive session", e);
            }
        }

        return archiveUrls;
    }

    /**
     * Archive a session
     */
    private void archiveSession(Set<String> archiveUrls, boolean override, boolean appendMerge,
                               PrearchiveOperationRequest request, UserI user) throws Exception {
        PrearcSession prearcSession = new PrearcSession(request, user);
        if (PrearcDatabase.setStatus(prearcSession.getFolderName(),
                                     prearcSession.getTimestamp(),
                                     prearcSession.getProject(),
                                     PrearcUtils.PrearcStatus.ARCHIVING)) {
            final boolean append = appendMerge ||
                (prearcSession.getSessionData() != null &&
                 prearcSession.getSessionData().getAutoArchive() != null &&
                 prearcSession.getSessionData().getAutoArchive() != org.nrg.framework.constants.PrearchiveCode.Manual);

            String url = PrearcDatabase.archive(
                getClass().getName(),  // listenerControl
                prearcSession,
                override,
                append,
                prearcSession.isOverwriteFiles(),
                user,
                null
            );
            archiveUrls.add(url);
        } else {
            throw new ServerException("Unable to lock session for archiving.");
        }
    }

    /**
     * Get boolean parameter from map
     */
    private boolean getBooleanParameter(Map<String, Object> params, String key) {
        final Object value = params.get(key);
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(value.toString());
    }

    /**
     * Populate additional values for prearchive operations
     */
    private Map<String, Object> populateAdditionalValues(Map<String, Object> parameters) {
        Map<String, Object> additionalValues = new HashMap<>();
        if (parameters.containsKey(RequestUtil.OVERWRITE_FILES)) {
            additionalValues.put(RequestUtil.OVERWRITE_FILES, parameters.get(RequestUtil.OVERWRITE_FILES));
        }
        return additionalValues;
    }

    /**
     * Build DICOM JSON response per STOW-RS specification
     */
    private String buildStowRsResponse(Set<String> prearchiveUris, Set<String> archiveUrls,
                                      String projectId, HttpServletRequest request) {
        try {
            Attributes attrs = new Attributes();

            // Build base URL for retrieval
            String baseUrl = request.getRequestURL().toString().replace("/studies", "");

            // For now, return simple response indicating success
            // In future, could extract SOP UIDs from prearchive/archive sessions
            int successCount = prearchiveUris.size();

            // Add retrieve URL if we have sessions
            if (successCount > 0) {
                // Use first prearchive or archive URL
                String firstUri = archiveUrls.isEmpty() ?
                    prearchiveUris.iterator().next() :
                    archiveUrls.iterator().next();
                attrs.setString(Tag.RetrieveURL, VR.UR, firstUri);
            }

            // Add empty sequences (no failures for now)
            attrs.newSequence(Tag.ReferencedSOPSequence, successCount);
            attrs.newSequence(Tag.FailedSOPSequence, 0);

            return DicomWebUtils.toJson(attrs);

        } catch (Exception e) {
            logger.error("Error building STOW-RS response", e);
            return "{\"error\": \"Failed to build response\"}";
        }
    }

    /**
     * Create error response in JSON format
     */
    private String createErrorResponse(String message) {
        return String.format("{\"error\": \"%s\"}", message.replace("\"", "\\\""));
    }
}
