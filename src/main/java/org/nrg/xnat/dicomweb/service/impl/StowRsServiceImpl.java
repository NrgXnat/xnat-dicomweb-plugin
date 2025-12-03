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
import org.nrg.xdat.XDAT;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.archive.Operation;
import org.nrg.xnat.dicomweb.config.DicomWebProperties;
import org.nrg.xnat.dicomweb.parser.Mime4jHybridParser;
import org.nrg.xnat.dicomweb.parser.Mime4jHybridParser.MultipartPart;
import org.nrg.xnat.dicomweb.service.FailedInstance;
import org.nrg.xnat.dicomweb.service.StowRsException;
import org.nrg.xnat.dicomweb.service.StowRsResult;
import org.nrg.xnat.dicomweb.service.StowRsService;
import org.nrg.xnat.dicomweb.service.SuccessfulInstance;
import org.nrg.xnat.dicomweb.service.impl.strategy.DirectWriteImporterStrategy;
import org.nrg.xnat.dicomweb.utils.DicomWebUtils;
import org.nrg.xnat.helpers.prearchive.PrearcDatabase;
import org.nrg.xnat.helpers.prearchive.PrearcSession;
import org.nrg.xnat.helpers.prearchive.PrearcUtils;
import org.nrg.xnat.helpers.prearchive.SessionData;
import org.nrg.xnat.helpers.prearchive.handlers.PrearchiveOperationHandlerResolver;
import org.nrg.xnat.helpers.prearchive.handlers.PrearchiveRebuildHandler;
import org.nrg.xnat.helpers.prearchive.handlers.PrearchiveSeparatePetMrHandler;
import org.nrg.xnat.restlet.util.RequestUtil;
import org.nrg.xnat.services.messaging.prearchive.PrearchiveOperationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Implementation of STOW-RS service.
 * Handles the business logic for storing DICOM instances.
 * Uses strategy pattern to support different import methods.
 */
@Service
public class StowRsServiceImpl implements StowRsService {

    private static final Logger logger = LoggerFactory.getLogger(StowRsServiceImpl.class);

    private static final String SLASH = "/";

    private final Mime4jHybridParser multipartParser;
    private final DirectWriteImporterStrategy directWriteImporter;

    @Autowired
    public StowRsServiceImpl(DirectWriteImporterStrategy directWriteImporter,
                             DicomWebProperties properties) {
        // Create parser with configured memory threshold
        File tempDir = createTempDirectory();
        long memoryThreshold = properties.getMultipart().getMemoryThreshold();
        this.multipartParser = new Mime4jHybridParser(tempDir, memoryThreshold);
        this.directWriteImporter = directWriteImporter;
        logger.info("StowRsServiceImpl initialized with multipart memory threshold: {} bytes", memoryThreshold);
    }

    /**
     * Create temporary directory for multipart parsing
     */
    private static File createTempDirectory() {
        String baseTempDir = XDAT.getSiteConfigPreferences().getCachePath();
        File dir = new File(baseTempDir, "stow-rs-" + System.currentTimeMillis());
        dir.mkdirs();
        return dir;
    }

    @Override
    public StowRsResult storeInstances(UserI user, Map<String, Object> params, HttpServletRequest request)
            throws StowRsException {

        logger.info("STOW-RS request from user {} with params {}, strategy: DirectWrite",
            user.getLogin(), params);
        logger.debug("Content-Type: {}, Content-Length: {}",
                request.getContentType(), request.getContentLength());

        List<MultipartPart> parts = null;
        Set<String> prearchiveUris = Sets.newLinkedHashSet();
        List<SuccessfulInstance> successfulInstances = new ArrayList<>();
        List<FailedInstance> failedInstances = new ArrayList<>();

        // Add default params
        Map<String, Object> mergedParams = new HashMap<>(getDefaultParams());
        mergedParams.putAll(params);

        try {
            // Parse multipart/related request directly from InputStream
            parts = multipartParser.parse(request.getContentType(), request.getInputStream());

            if (parts.isEmpty()) {
                logger.warn("No parts found in STOW-RS request");
                throw StowRsException.badRequest("No DICOM instances in request");
            }

            logger.info("Parsed {} parts from multipart request ({} in memory, {} on disk)",
                parts.size(),
                parts.stream().filter(MultipartPart::isInMemory).count(),
                parts.stream().filter(p -> !p.isInMemory()).count());

            // Import DICOM instances using DirectWrite strategy
            directWriteImporter.importInstances(user, parts, mergedParams, prearchiveUris, successfulInstances, failedInstances);

            if (prearchiveUris.isEmpty()) {
                logger.warn("No instances were successfully imported");
                throw StowRsException.serverError("Failed to import any DICOM instances");
            }

            logger.info("Successfully imported {} sessions to prearchive, {} failures",
                prearchiveUris.size(), failedInstances.size());

            // Build sessions
            logger.info("Building XML for {} DICOM sessions", prearchiveUris.size());
            Set<String> archiveUrls = buildSessions(user, prearchiveUris, mergedParams);

            // Build STOW-RS response with successful and failed instances
            String jsonResponse = buildStowRsResponse(successfulInstances, archiveUrls, failedInstances, request);

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
            // Clean up resources
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
                SessionData sessionData = PrearcDatabase.getSession(
                    elements[5],  // session name
                    elements[4],  // timestamp
                    elements[3]   // project
                );

                if (sessionData != null) {
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
            throw new org.nrg.action.ServerException("Unable to lock session for archiving.");
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
     */
    private String buildStowRsResponse(List<SuccessfulInstance> successfulInstances, Set<String> archiveUrls,
                                       List<FailedInstance> failedInstances,
                                       HttpServletRequest request) {
        try {
            Attributes attrs = new Attributes();
            int successCount = successfulInstances.size();

            // Set top-level RetrieveURL (0008,1190) - use first archive URL if available
            if (successCount > 0 && !archiveUrls.isEmpty()) {
                String firstArchiveUrl = archiveUrls.iterator().next();
                attrs.setString(Tag.RetrieveURL, VR.UR, firstArchiveUrl);
            }

            // Create ReferencedSOPSequence (0008,1199) - POPULATE with successful instances
            Sequence referencedSeq = attrs.newSequence(Tag.ReferencedSOPSequence, successCount);
            for (SuccessfulInstance success : successfulInstances) {
                Attributes refItem = new Attributes();

                // ReferencedSOPClassUID (0008,1150)
                if (success.getSopClassUid() != null) {
                    refItem.setString(Tag.ReferencedSOPClassUID, VR.UI, success.getSopClassUid());
                }

                // ReferencedSOPInstanceUID (0008,1155)
                refItem.setString(Tag.ReferencedSOPInstanceUID, VR.UI, success.getSopInstanceUid());

                // RetrieveURL (0008,1190) - Use archive URL if available, otherwise prearchive URL
                String retrieveUrl = success.getRetrieveUrl();
                if (!archiveUrls.isEmpty()) {
                    // If we have archive URLs, prefer those over prearchive URLs
                    retrieveUrl = archiveUrls.iterator().next();
                }
                refItem.setString(Tag.RetrieveURL, VR.UR, retrieveUrl);

                referencedSeq.add(refItem);
                logger.debug("Added successful instance to response: SOP={}, Class={}",
                    success.getSopInstanceUid(), success.getSopClassUid());
            }

            // Create FailedSOPSequence (0008,1198)
            Sequence failedSeq = attrs.newSequence(Tag.FailedSOPSequence, failedInstances.size());
            for (FailedInstance failure : failedInstances) {
                Attributes failedItem = new Attributes();
                failedItem.setInt(Tag.FailureReason, VR.US, failure.getFailureReason());

                if (failure.hasSopUids()) {
                    failedItem.setString(Tag.ReferencedSOPClassUID, VR.UI, failure.getSopClassUid());
                    failedItem.setString(Tag.ReferencedSOPInstanceUID, VR.UI, failure.getSopInstanceUid());
                }

                failedSeq.add(failedItem);
                logger.debug("Added failed instance to response: {}", failure);
            }

            logger.info("Built STOW-RS response: {} successful, {} failed", successCount, failedInstances.size());
            return DicomWebUtils.toJson(attrs);

        } catch (Exception e) {
            logger.error("Error building STOW-RS response", e);
            return "{\"error\": \"Failed to build response\"}";
        }
    }
}
