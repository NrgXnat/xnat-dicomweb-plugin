/*
 * XNAT DICOMweb Proxy Plugin
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 */

package org.nrg.xnat.dicomweb.service.impl;

import com.google.common.collect.Sets;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Sequence;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.nrg.action.ClientException;
import org.nrg.action.ServerException;
import org.nrg.xdat.XDAT;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.archive.GradualDicomImporter;
import org.nrg.xnat.archive.Operation;
import org.nrg.xnat.dicomweb.helpers.InputStreamFileWriterWrapper;
import org.nrg.xnat.dicomweb.parser.Mime4jHybridParser;
import org.nrg.xnat.dicomweb.parser.Mime4jHybridParser.MultipartPart;
import org.nrg.xnat.dicomweb.service.FailedInstance;
import org.nrg.xnat.dicomweb.service.StowRsException;
import org.nrg.xnat.dicomweb.service.StowRsResult;
import org.nrg.xnat.dicomweb.service.StowRsService;
import org.nrg.xnat.dicomweb.utils.DicomWebUtils;
import org.nrg.xnat.helpers.prearchive.PrearcDatabase;
import org.nrg.xnat.helpers.prearchive.PrearcSession;
import org.nrg.xnat.helpers.prearchive.PrearcUtils;
import org.nrg.xnat.helpers.prearchive.SessionData;
import org.nrg.xnat.helpers.prearchive.handlers.PrearchiveOperationHandlerResolver;
import org.nrg.xnat.helpers.prearchive.handlers.PrearchiveRebuildHandler;
import org.nrg.xnat.helpers.prearchive.handlers.PrearchiveSeparatePetMrHandler;
import org.nrg.xnat.restlet.util.FileWriterWrapperI;
import org.nrg.xnat.restlet.util.RequestUtil;
import org.nrg.xnat.services.messaging.prearchive.PrearchiveOperationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Implementation of STOW-RS service.
 * Handles the business logic for storing DICOM instances.
 */
@Service
public class StowRsServiceImpl implements StowRsService {

    private static final Logger logger = LoggerFactory.getLogger(StowRsServiceImpl.class);

    private static final String SLASH = "/";

    // DICOM file prefix offset and magic bytes
    private static final int DICOM_PREFIX_OFFSET = 128;
    private static final byte[] DICOM_MAGIC_BYTES = "DICM".getBytes(StandardCharsets.US_ASCII);

    // Valid DICOM content types
    private static final Set<String> VALID_DICOM_CONTENT_TYPES = new HashSet<>(Arrays.asList(
        "application/dicom",
        "application/octet-stream"  // Some clients use this
    ));

    private Map<String, Object> defaultParams = getDefaultParams();
    private final Mime4jHybridParser multipartParser;

    public StowRsServiceImpl() {
        this.multipartParser = new Mime4jHybridParser();
    }

    @Override
    public StowRsResult storeInstances(UserI user, Map<String, Object> params, HttpServletRequest request)
            throws StowRsException {

        logger.info("STOW-RS request from user {} with params {}", user.getLogin(), params);
        logger.debug("Content-Type: {}, Content-Length: {}",
                request.getContentType(), request.getContentLength());

        List<MultipartPart> parts = null;
        Set<String> prearchiveUris = Sets.newLinkedHashSet();
        List<FailedInstance> failedInstances = new ArrayList<>();
        params.putAll(defaultParams);

        try {

            // Parse multipart/related request directly from InputStream (memory efficient)
            parts = multipartParser.parse(request.getContentType(), request.getInputStream());

            if (parts.isEmpty()) {
                logger.warn("No parts found in STOW-RS request");
                throw StowRsException.badRequest("No DICOM instances in request");
            }

            logger.info("Parsed {} parts from multipart request ({} in memory, {} on disk)",
                parts.size(),
                parts.stream().filter(MultipartPart::isInMemory).count(),
                parts.stream().filter(p -> !p.isInMemory()).count());

            // Import each DICOM instance to prearchive
            logger.info("Importing {} parts to prearchive", parts.size());
            for (int i = 0; i < parts.size(); i++) {
                MultipartPart part = parts.get(i);

                // Pre-validation: Check if this looks like a DICOM file
                FailedInstance validationFailure = validateDicomPart(part, i);
                if (validationFailure != null) {
                    logger.warn("Part {} failed pre-validation: {}", i, validationFailure.getErrorMessage());
                    failedInstances.add(validationFailure);
                    continue;  // Skip this part, don't send to GradualDicomImporter
                }

                try {
                    importInstance(user, part, i, params, prearchiveUris);
                } catch (ClientException | ServerException e) {
                    logger.error("Failed to import instance {}: {}", i, e.getMessage(), e);
                    // Collect failure information
                    FailedInstance failure = FailedInstance.processingFailure(i, e.getMessage());
                    failedInstances.add(failure);
                    // Continue with other instances
                }
            }

            if (prearchiveUris.isEmpty()) {
                logger.warn("No instances were successfully imported");
                throw StowRsException.serverError("Failed to import any DICOM instances");
            }

            logger.info("Successfully imported {} sessions to prearchive, {} failures",
                prearchiveUris.size(), failedInstances.size());

            // Build sessions
            logger.info("Building XML for {} DICOM sessions", prearchiveUris.size());
            Set<String> archiveUrls = buildSessions(user, prearchiveUris, params);

            // Build STOW-RS response with failure information
            String jsonResponse = buildStowRsResponse(prearchiveUris, archiveUrls, failedInstances, request);

            return new StowRsResult(
                prearchiveUris,
                archiveUrls,
                jsonResponse,
                prearchiveUris.size(),
                failedInstances
            );

        } catch (StowRsException e) {
            throw e;
        } catch (SecurityException e) {
            logger.error("Security exception during STOW-RS", e);
            throw StowRsException.forbidden(e.getMessage());
        } catch (IllegalArgumentException e) {
            logger.error("Invalid request during STOW-RS", e);
            throw StowRsException.badRequest(e.getMessage());
        } catch (Exception e) {
            logger.error("STOW-RS error", e);
            throw StowRsException.serverError("Internal server error: " + e.getMessage(), e);
        } finally {
            // Clean up resources (critical for disk-based parts)
            if (parts != null) {
                multipartParser.cleanup(parts);
            }
        }
    }

    private static Map<String, Object> getDefaultParams() {
        Map<String, Object> defaultParams = new HashMap<>();
        defaultParams.put("action", "commit");
        defaultParams.put(RequestUtil.AA, "false");
        defaultParams.put(RequestUtil.AUTO_ARCHIVE, "false");
        defaultParams.put("overwrite", "append");
        defaultParams.put("overwrite_files", "true");
        return defaultParams;
    }

    /**
     * Validate that a part is likely a DICOM file before sending to GradualDicomImporter.
     * Returns a FailedInstance if validation fails, null if validation passes.
     *
     * Checks:
     * 1. Content-Type (if specified) should be application/dicom or application/octet-stream
     * 2. DICOM magic bytes "DICM" at offset 128 (for files with Part 10 header)
     */
    private FailedInstance validateDicomPart(MultipartPart part, int index) {
        String contentType = part.getContentType();

        // Check 1: Content-Type validation (if specified and not generic)
        if (contentType != null && !contentType.isEmpty()) {
            String normalizedType = contentType.toLowerCase().split(";")[0].trim();
            if (!VALID_DICOM_CONTENT_TYPES.contains(normalizedType) &&
                !normalizedType.startsWith("application/dicom")) {
                logger.info("Part {} has non-DICOM Content-Type: {}", index, contentType);
                return FailedInstance.cannotUnderstand(index,
                    "Invalid Content-Type: " + contentType + ". Expected application/dicom");
            }
        }

        // Check 2: DICOM magic bytes validation
        // For files >= 132 bytes, check for "DICM" at offset 128
        if (part.getSize() >= DICOM_PREFIX_OFFSET + DICOM_MAGIC_BYTES.length) {
            try (InputStream is = part.getInputStream()) {
                byte[] header = new byte[DICOM_PREFIX_OFFSET + DICOM_MAGIC_BYTES.length];
                int bytesRead = 0;
                while (bytesRead < header.length) {
                    int read = is.read(header, bytesRead, header.length - bytesRead);
                    if (read == -1) break;
                    bytesRead += read;
                }

                if (bytesRead >= DICOM_PREFIX_OFFSET + DICOM_MAGIC_BYTES.length) {
                    byte[] magicBytes = new byte[DICOM_MAGIC_BYTES.length];
                    System.arraycopy(header, DICOM_PREFIX_OFFSET, magicBytes, 0, DICOM_MAGIC_BYTES.length);

                    if (!Arrays.equals(magicBytes, DICOM_MAGIC_BYTES)) {
                        String foundMagic = new String(magicBytes, StandardCharsets.US_ASCII);
                        logger.info("Part {} does not have DICOM magic bytes. Found: '{}' at offset {}",
                            index, foundMagic, DICOM_PREFIX_OFFSET);
                        return FailedInstance.cannotUnderstand(index,
                            "Not a valid DICOM file: missing DICM header");
                    }
                }
            } catch (IOException e) {
                logger.warn("Failed to read part {} for validation: {}", index, e.getMessage());
                // Don't fail validation on read errors - let GradualDicomImporter handle it
            }
        } else if (part.getSize() > 0 && part.getSize() < DICOM_PREFIX_OFFSET) {
            // File too small to be a valid DICOM Part 10 file
            logger.info("Part {} is too small to be a valid DICOM file: {} bytes", index, part.getSize());
            return FailedInstance.cannotUnderstand(index,
                "File too small to be a valid DICOM file: " + part.getSize() + " bytes");
        }

        // Validation passed
        return null;
    }

    /**
     * Import a single DICOM instance using GradualDicomImporter
     */
    private void importInstance(UserI user, MultipartPart part, int index,
                                Map<String, Object> params, Set<String> uris)
            throws ServerException, ClientException {

        FileWriterWrapperI fw = new InputStreamFileWriterWrapper(part, index);
        logger.debug("Importing DICOM instance: {} ({} bytes)", fw.getName(), part.getSize());

        final GradualDicomImporter importer = new GradualDicomImporter(
            getClass().getName(),
            user,
            fw,
            params
        );

        // Set the DICOM object identifier
        setDicomObjectIdentifier(importer);

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
     * Set DICOM object identifier using reflection
     */
    private void setDicomObjectIdentifier(GradualDicomImporter importer) throws ServerException {
        try {
            Object identifier = XDAT.getContextService().getBean("dicomObjectIdentifier");
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
    }

    /**
     * Build sessions (rebuild prearchive sessions)
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
                final SessionData sessionData = PrearcDatabase.getSession(
                    elements[5],  // session name
                    elements[4],  // timestamp
                    elements[3]   // project
                );

                PrearchiveOperationRequest request = new PrearchiveOperationRequest(
                    user,
                    Operation.Rebuild,
                    sessionData,
                    new File(sessionData.getUrl()),
                    populateAdditionalValues(params)
                );

                PrearchiveRebuildHandler handler =
                    (PrearchiveRebuildHandler) resolver.getHandler(request);
                boolean buildSuccessful = handler.rebuild();

                if (buildSuccessful) {
                    handlePostBuild(user, archiveUrls, override, appendMerge,
                        request, handler, sessionData, params, resolver);
                }
            } catch (Exception e) {
                logger.error("Unable to build/archive prearchive session: {}", sessionUri, e);
                throw new ClientException("Unable to build prearchive session", e);
            }
        }

        return archiveUrls;
    }

    /**
     * Handle post-build operations including PET/MR separation and archiving
     */
    private void handlePostBuild(UserI user, Set<String> archiveUrls, boolean override,
                                 boolean appendMerge, PrearchiveOperationRequest request,
                                 PrearchiveRebuildHandler handler, SessionData sessionData,
                                 Map<String, Object> params,
                                 PrearchiveOperationHandlerResolver resolver) throws Exception {
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
                getClass().getName(),
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
     * Build DICOM JSON response per STOW-RS specification (PS3.18)
     * Includes both ReferencedSOPSequence for successes and FailedSOPSequence for failures.
     */
    private String buildStowRsResponse(Set<String> prearchiveUris, Set<String> archiveUrls,
                                       List<FailedInstance> failedInstances,
                                       HttpServletRequest request) {
        try {
            Attributes attrs = new Attributes();
            int successCount = prearchiveUris.size();

            // Set RetrieveURL (0008,1190) - URL to retrieve the stored instances
            if (successCount > 0) {
                String firstUri = archiveUrls.isEmpty() ?
                    prearchiveUris.iterator().next() :
                    archiveUrls.iterator().next();
                attrs.setString(Tag.RetrieveURL, VR.UR, firstUri);
            }

            // Create ReferencedSOPSequence (0008,1199) for successful instances
            Sequence referencedSeq = attrs.newSequence(Tag.ReferencedSOPSequence, successCount);
            // Note: In a full implementation, we would add items with:
            // - ReferencedSOPClassUID (0008,1150)
            // - ReferencedSOPInstanceUID (0008,1155)
            // - RetrieveURL (0008,1190)

            // Create FailedSOPSequence (0008,1198) for failed instances
            Sequence failedSeq = attrs.newSequence(Tag.FailedSOPSequence, failedInstances.size());
            for (FailedInstance failure : failedInstances) {
                Attributes failedItem = new Attributes();

                // Set FailureReason (0008,1197) - required
                failedItem.setInt(Tag.FailureReason, VR.US, failure.getFailureReason());

                // Set ReferencedSOPClassUID and ReferencedSOPInstanceUID if available
                if (failure.hasSopUids()) {
                    failedItem.setString(Tag.ReferencedSOPClassUID, VR.UI, failure.getSopClassUid());
                    failedItem.setString(Tag.ReferencedSOPInstanceUID, VR.UI, failure.getSopInstanceUid());
                }

                failedSeq.add(failedItem);
                logger.debug("Added failed instance to response: {}", failure);
            }

            return DicomWebUtils.toJson(attrs);

        } catch (Exception e) {
            logger.error("Error building STOW-RS response", e);
            return "{\"error\": \"Failed to build response\"}";
        }
    }
}
