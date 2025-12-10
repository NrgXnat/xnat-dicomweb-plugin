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
import org.nrg.xnat.dicomweb.config.DicomWebPreferenceBean;
import org.nrg.xnat.dicomweb.config.DicomWebProperties;
import org.nrg.xnat.dicomweb.parser.Mime4jHybridParser;
import org.nrg.xnat.dicomweb.parser.Mime4jHybridParser.MultipartPart;
import org.nrg.xnat.dicomweb.service.FailedInstance;
import org.nrg.xnat.dicomweb.service.StowRsException;
import org.nrg.xnat.dicomweb.service.StowRsResult;
import org.nrg.xnat.dicomweb.service.StowRsService;
import org.nrg.xnat.dicomweb.service.SuccessfulInstance;
import org.nrg.xnat.dicomweb.service.impl.strategy.DicomImportStrategy;
import org.nrg.xnat.dicomweb.service.impl.strategy.DirectArchiveStrategy;
import org.nrg.xnat.dicomweb.service.impl.strategy.GradualDicomImporterStrategy;
import org.nrg.xnat.dicomweb.utils.DicomWebUtils;
import org.nrg.xnat.helpers.prearchive.PrearcDatabase;
import org.nrg.xnat.helpers.prearchive.PrearcSession;
import org.nrg.xnat.helpers.prearchive.PrearcUtils;
import org.nrg.xnat.helpers.prearchive.SessionData;
import org.nrg.xnat.helpers.prearchive.handlers.PrearchiveOperationHandlerResolver;
import org.nrg.xnat.helpers.prearchive.handlers.PrearchiveRebuildHandler;
import org.nrg.xnat.helpers.prearchive.handlers.PrearchiveSeparatePetMrHandler;
import org.nrg.xnat.helpers.uri.URIManager;
import org.nrg.xnat.restlet.util.RequestUtil;
import org.nrg.xnat.services.messaging.prearchive.PrearchiveOperationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Implementation of STOW-RS service.
 * Handles the business logic for storing DICOM instances.
 * Uses strategy pattern to support different import methods.
 */
@Service
public class StowRsServiceImpl implements StowRsService {

    private static final Logger logger = LoggerFactory.getLogger(StowRsServiceImpl.class);

    private static final String SLASH = "/";
    private static final long BUILD_DELAY_MS = 500;  // 500ms delay before building

    private final Mime4jHybridParser multipartParser;
    private final DirectArchiveStrategy directArchiveStrategy;
    private final GradualDicomImporterStrategy gradualDicomImporterStrategy;
    private final DicomWebPreferenceBean preferenceBean;

    // Concurrent build management - using last activity time approach
    private final ConcurrentHashMap<String, AtomicLong> lastActivityTime = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<Set<String>>> buildFutures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> sessionUrisByKey = new ConcurrentHashMap<>();

    // Import tracking - ensures build waits for all imports to complete
    private final ConcurrentHashMap<String, Set<CompletableFuture<Void>>> importFutures = new ConcurrentHashMap<>();

    // Thread-local storage for import futures (before session key is known)
    private final ThreadLocal<CompletableFuture<Void>> threadImportFuture = new ThreadLocal<>();

    private final ScheduledExecutorService buildScheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "stowrs-build-scheduler");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    public StowRsServiceImpl(DirectArchiveStrategy directArchiveStrategy,
                             GradualDicomImporterStrategy gradualDicomImporterStrategy,
                             DicomWebProperties properties,
                             DicomWebPreferenceBean preferenceBean) {
        // Create parser with configured memory threshold
        File tempDir = createTempDirectory();
        long memoryThreshold = properties.getMultipart().getMemoryThreshold();
        this.multipartParser = new Mime4jHybridParser(tempDir, memoryThreshold);
        this.directArchiveStrategy = directArchiveStrategy;
        this.gradualDicomImporterStrategy = gradualDicomImporterStrategy;
        this.preferenceBean = preferenceBean;
        logger.info("StowRsServiceImpl initialized with {} strategies and multipart memory threshold: {} bytes",
                2, memoryThreshold);
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

    /**
     * Select import strategy based on params.
     * Supports query parameter: ?strategy=GradualDicomImporter or ?strategy=DirectArchive
     * Default: Configured via Admin UI (dicomweb.defaultStrategy preference)
     */
    private DicomImportStrategy selectStrategy(Map<String, Object> params) {
        String strategyName = (String) params.get("strategy");

        if (strategyName == null) {
            // Use configured default from preferences
            strategyName = preferenceBean.getDefaultStrategy();
            if (strategyName == null || strategyName.isEmpty()) {
                strategyName = "GradualDicomImporter";  // Fallback
            }
            logger.debug("Using configured default strategy: {}", strategyName);
        }

        switch (strategyName) {
            case "DirectArchive":
                logger.info("Using DirectArchive strategy");
                return directArchiveStrategy;
            case "GradualDicomImporter":
            default:
                logger.info("Using GradualDicomImporter strategy");
                return gradualDicomImporterStrategy;
        }
    }

    @Override
    public StowRsResult storeInstances(UserI user, Map<String, Object> params, HttpServletRequest request)
            throws StowRsException {

        // Add default params
        Map<String, Object> mergedParams = new HashMap<>(getDefaultParams());
        mergedParams.putAll(params);

        // Select import strategy based on params
        DicomImportStrategy strategy = selectStrategy(mergedParams);

        logger.info("STOW-RS request from user {} with params {}, strategy: {}",
            user.getLogin(), params, strategy.getName());
        logger.debug("Content-Type: {}, Content-Length: {}",
                request.getContentType(), request.getContentLength());

        List<MultipartPart> parts = null;
        Set<String> sessionUris = Sets.newLinkedHashSet();
        List<SuccessfulInstance> successfulInstances = new ArrayList<>();
        List<FailedInstance> failedInstances = new ArrayList<>();

        // Extract session key for concurrent build management
        String sessionKey = null;
        AtomicInteger activeCount = null;
        CompletableFuture<Set<String>> buildFuture = null;

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

            // For GradualDicomImporter: Register import future BEFORE import starts
            // (session key is not known yet, will be moved to correct key after import)
            if (strategy instanceof GradualDicomImporterStrategy) {
                CompletableFuture<Void> myImportFuture = new CompletableFuture<>();
                threadImportFuture.set(myImportFuture);
                logger.debug("Registered import future for thread: {}", Thread.currentThread().getName());
            }

            // Import DICOM instances using selected strategy
            // THIS IS THE LONG-RUNNING OPERATION we need to wait for
            strategy.importInstances(user, parts, mergedParams, sessionUris,
                    successfulInstances, failedInstances);

            if (sessionUris.isEmpty()) {
                logger.warn("No instances were successfully imported");
                throw StowRsException.serverError("Failed to import any DICOM instances");
            }

            logger.info("Successfully imported {} sessions via {}, {} failures",
                sessionUris.size(), strategy.getName(), failedInstances.size());

            // Handle post-import based on strategy
            Set<String> archiveUrls;
            Map<String, String> prearchiveToArchiveMap = new HashMap<>();

            if (strategy instanceof DirectArchiveStrategy) {
                // DirectArchive automatically builds and archives sessions
                // URIs are already the final archive locations
                logger.info("DirectArchive sessions created. URIs are final locations: {} sessions",
                        sessionUris.size());
                archiveUrls = sessionUris;
            } else if (strategy instanceof GradualDicomImporterStrategy) {
                // GradualDicomImporter: per-session concurrent build management
                archiveUrls = buildSessionsWithPerSessionConcurrency(user, sessionUris, mergedParams,
                                                                     prearchiveToArchiveMap);
            } else {
                // Unknown strategy - return sessionUris as-is
                logger.warn("Unknown strategy type: {}, returning session URIs as-is", strategy.getClass().getName());
                archiveUrls = sessionUris;
            }

            // Build STOW-RS response with successful and failed instances
            // Pass prearchive→archive mapping for correct per-instance URLs
            String jsonResponse = buildStowRsResponse(successfulInstances, archiveUrls, failedInstances,
                                                     request, prearchiveToArchiveMap, mergedParams);

            return new StowRsResult(
                sessionUris,
                archiveUrls,
                jsonResponse,
                sessionUris.size(),
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
            // Clean up thread-local import future (in case of exception)
            threadImportFuture.remove();
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
    private Set<String> buildSessions(UserI user, Set<String> sessionUris,
                                      Map<String, Object> params) throws ClientException {
        Set<String> archiveUrls = new HashSet<>();
        final boolean override = getBooleanParameter(params, PrearchiveOperationRequest.PARAM_OVERRIDE_EXCEPTIONS);
        final boolean appendMerge = getBooleanParameter(params, PrearchiveOperationRequest.PARAM_ALLOW_SESSION_MERGE);

        PrearchiveOperationHandlerResolver resolver =
            XDAT.getContextService().getBean(PrearchiveOperationHandlerResolver.class);

        for (String sessionUri : sessionUris) {
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
     * Build sessions with per-session concurrent management using last activity time.
     * Each session tracks when the last upload completed to handle concurrent uploads properly.
     *
     * @param user User performing the operation
     * @param sessionUris Prearchive session URIs to build
     * @param params Build parameters
     * @param prearchiveToArchiveMap Output map for prearchive URI → archive URI mapping
     * @return Set of all archive URLs
     */
    private Set<String> buildSessionsWithPerSessionConcurrency(UserI user,
                                                                Set<String> sessionUris,
                                                                Map<String, Object> params,
                                                                Map<String, String> prearchiveToArchiveMap)
            throws ClientException {

        Set<String> allArchiveUrls = new HashSet<>();
        Map<String, Set<String>> localSessionUrisByKey = new HashMap<>();

        // Group sessionUris by sessionKey (multiple timestamps may exist for same patient/study)
        for (String prearchiveUri : sessionUris) {
            String sessionKey = extractSessionKey(prearchiveUri);
            localSessionUrisByKey.computeIfAbsent(sessionKey, k -> new HashSet<>()).add(prearchiveUri);
        }

        // Process each unique session
        for (Map.Entry<String, Set<String>> entry : localSessionUrisByKey.entrySet()) {
            String sessionKey = entry.getKey();
            Set<String> urisForSession = entry.getValue();

            logger.debug("Processing session {} with {} URIs", sessionKey, urisForSession.size());

            // 1. Move this thread's import future to the correct session key
            //    (import has completed, now we know the session key)
            CompletableFuture<Void> myImportFuture = threadImportFuture.get();
            if (myImportFuture != null) {
                Set<CompletableFuture<Void>> futures = importFutures.computeIfAbsent(
                    sessionKey, k -> ConcurrentHashMap.newKeySet());
                futures.add(myImportFuture);
                logger.debug("Moved import future to session key: {} (total: {})",
                            sessionKey, futures.size());

                // Mark import as complete for this thread
                myImportFuture.complete(null);
                logger.debug("Import completed for session key: {}", sessionKey);

                // Clean up thread-local
                threadImportFuture.remove();
            }

            // 2. Add URIs to shared collection (thread-safe)
            Set<String> allUrisForKey = sessionUrisByKey.computeIfAbsent(sessionKey,
                k -> ConcurrentHashMap.newKeySet());
            allUrisForKey.addAll(urisForSession);
            logger.debug("Session {} now has {} total URIs", sessionKey, allUrisForKey.size());

            // 3. Update last activity time for this session
            long currentTime = System.currentTimeMillis();
            AtomicLong lastActivity = lastActivityTime.computeIfAbsent(sessionKey,
                k -> new AtomicLong(currentTime));
            long previousActivity = lastActivity.getAndSet(currentTime);

            // 4. Get or create future for this session
            CompletableFuture<Set<String>> buildFuture = buildFutures.computeIfAbsent(sessionKey,
                k -> {
                    // First time seeing this session, schedule build check
                    logger.info("First upload for session {}, scheduling build check", sessionKey);
                    scheduleBuildCheck(sessionKey, user, params);
                    return new CompletableFuture<>();
                });

            // 5. Wait for this session's build to complete
            try {
                logger.debug("Waiting for build to complete for session: {}", sessionKey);
                Set<String> archiveUrlsForSession = buildFuture.get(30, TimeUnit.SECONDS);
                logger.info("Got archive URLs for session {}: {}", sessionKey, archiveUrlsForSession);

                // 4. Build mapping for all URIs in this session
                if (!archiveUrlsForSession.isEmpty()) {
                    String archiveUrl = archiveUrlsForSession.iterator().next();
                    for (String prearchiveUri : urisForSession) {
                        prearchiveToArchiveMap.put(prearchiveUri, archiveUrl);
                        logger.info("Built mapping: {} → {}", prearchiveUri, archiveUrl);
                    }
                    allArchiveUrls.addAll(archiveUrlsForSession);
                } else {
                    logger.warn("No archive URLs returned for session: {}", sessionKey);
                }

            } catch (TimeoutException e) {
                logger.error("Timeout waiting for build of session: {}", sessionKey);
                throw new ClientException("Build timeout: session may still be processing", e);
            } catch (ExecutionException e) {
                logger.error("Build failed for session: {}", sessionKey, e.getCause());
                throw new ClientException("Build failed: " + e.getCause().getMessage(), e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("Build interrupted for session: {}", sessionKey);
                throw new ClientException("Build interrupted", e);
            }
        }

        return allArchiveUrls;
    }

    /**
     * Schedule periodic build check for a session using last activity time approach.
     * If no new uploads arrive within BUILD_DELAY_MS, the session will be built.
     * Otherwise, reschedule the check.
     */
    private void scheduleBuildCheck(String sessionKey, UserI user, Map<String, Object> params) {
        buildScheduler.schedule(() -> {
            AtomicLong lastActivity = lastActivityTime.get(sessionKey);
            if (lastActivity == null) {
                logger.warn("No last activity time found for session {}, skipping build", sessionKey);
                return;
            }

            long timeSinceLastActivity = System.currentTimeMillis() - lastActivity.get();

            // Check 1: Time condition - has enough time passed since last activity?
            boolean timeConditionMet = timeSinceLastActivity >= BUILD_DELAY_MS;

            // Check 2: Import condition - are all imports complete?
            Set<CompletableFuture<Void>> futures = importFutures.get(sessionKey);
            boolean allImportsComplete = futures == null ||
                futures.stream().allMatch(CompletableFuture::isDone);

            if (timeConditionMet && allImportsComplete) {
                // Both conditions met: time passed AND all imports complete → start build
                logger.info("Build conditions met for session {} (time: {}ms, imports: complete), starting build",
                           sessionKey, timeSinceLastActivity);

                CompletableFuture<Set<String>> buildFuture = buildFutures.get(sessionKey);
                if (buildFuture == null) {
                    logger.warn("No build future found for session {}", sessionKey);
                    return;
                }

                try {
                    // Get all URIs collected for this sessionKey
                    Set<String> urisForSession = sessionUrisByKey.get(sessionKey);

                    if (urisForSession == null || urisForSession.isEmpty()) {
                        logger.warn("No session URIs found for key: {}", sessionKey);
                        buildFuture.completeExceptionally(new ClientException("No sessions found to build"));
                        return;
                    }

                    logger.info("Building {} prearchive sessions for {}: {}",
                              urisForSession.size(), sessionKey, urisForSession);

                    Set<String> archiveUrls = buildSessions(user, urisForSession, params);
                    buildFuture.complete(archiveUrls);
                    logger.info("Build completed successfully for session {}: {}", sessionKey, archiveUrls);
                } catch (Exception e) {
                    logger.error("Failed to build session: {}", sessionKey, e);
                    buildFuture.completeExceptionally(e);
                } finally {
                    // Clean up maps
                    buildFutures.remove(sessionKey);
                    lastActivityTime.remove(sessionKey);
                    sessionUrisByKey.remove(sessionKey);
                    importFutures.remove(sessionKey);  // Clean up import futures
                }
            } else {
                // Conditions not met: either time not ready OR imports still running → reschedule
                String reason = !timeConditionMet ?
                    String.format("time not ready (only %dms passed)", timeSinceLastActivity) :
                    String.format("imports still running (%d pending)",
                        futures.stream().filter(f -> !f.isDone()).count());

                logger.debug("Session {} not ready to build ({}), rescheduling check",
                           sessionKey, reason);
                scheduleBuildCheck(sessionKey, user, params);
            }
        }, BUILD_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Extract session key from prearchive URI for concurrent build management.
     * NOTE: Excludes timestamp because GradualDicomImporter creates different timestamps
     * for concurrent uploads of the same patient/study. We want to group them together.
     */
    private String extractSessionKey(String sessionUri) {
        // /prearchive/projects/TestProject/20251208_085152/STS_045
        // → TestProject/STS_045 (without timestamp)
        String[] elements = sessionUri.split(SLASH);
        if (elements.length >= 6) {
            return elements[3] + "/" + elements[5];  // project + sessionName
        }
        return sessionUri;
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
     * Returns an array of response objects, one per study, each containing:
     * - 00081190 (RetrieveURL): Study-level URL
     * - 00081198 (FailedSOPSequence): Failed instances for this study
     * - 00081199 (ReferencedSOPSequence): Successful instances for this study
     */
    private String buildStowRsResponse(List<SuccessfulInstance> successfulInstances, Set<String> archiveUrls,
                                       List<FailedInstance> failedInstances,
                                       HttpServletRequest request,
                                       Map<String, String> prearchiveToArchiveMap,
                                       Map<String, Object> params) {
        try {
            // Build base DICOMweb URL from request
            String projectId = (String) params.get(URIManager.PROJECT_ID);
            String baseUrl = buildBaseUrl(request, projectId);
            logger.debug("Base DICOMweb URL: {}", baseUrl);

            // Group successful instances by StudyInstanceUID
            Map<String, List<SuccessfulInstance>> successByStudy = new HashMap<>();
            for (SuccessfulInstance instance : successfulInstances) {
                String studyUid = instance.getStudyInstanceUid();
                if (studyUid != null) {
                    successByStudy.computeIfAbsent(studyUid, k -> new ArrayList<>()).add(instance);
                }
            }

            // Group failed instances by StudyInstanceUID (if available)
            Map<String, List<FailedInstance>> failedByStudy = new HashMap<>();
            List<FailedInstance> failedWithoutStudy = new ArrayList<>();
            for (FailedInstance failure : failedInstances) {
                String studyUid = failure.getStudyInstanceUid();
                if (studyUid != null) {
                    failedByStudy.computeIfAbsent(studyUid, k -> new ArrayList<>()).add(failure);
                } else {
                    failedWithoutStudy.add(failure);
                }
            }

            // Build JSON array of study responses
            StringBuilder jsonArray = new StringBuilder("[");
            boolean first = true;

            // Process each study
            for (Map.Entry<String, List<SuccessfulInstance>> entry : successByStudy.entrySet()) {
                String studyUid = entry.getKey();
                List<SuccessfulInstance> studyInstances = entry.getValue();
                List<FailedInstance> studyFailures = failedByStudy.getOrDefault(studyUid, Collections.emptyList());

                if (!first) {
                    jsonArray.append(",");
                }
                first = false;

                // Build Attributes for this study
                Attributes studyAttrs = new Attributes();

                // RetrieveURL (0008,1190) - Study-level URL
                String studyUrl = baseUrl + "/studies/" + studyUid;
                studyAttrs.setString(Tag.RetrieveURL, VR.UR, studyUrl);

                // FailedSOPSequence (0008,1198)
                Sequence failedSeq = studyAttrs.newSequence(Tag.FailedSOPSequence, studyFailures.size());
                for (FailedInstance failure : studyFailures) {
                    Attributes failedItem = new Attributes();
                    failedItem.setInt(Tag.FailureReason, VR.US, failure.getFailureReason());
                    if (failure.hasSopUids()) {
                        failedItem.setString(Tag.ReferencedSOPClassUID, VR.UI, failure.getSopClassUid());
                        failedItem.setString(Tag.ReferencedSOPInstanceUID, VR.UI, failure.getSopInstanceUid());
                    }
                    failedSeq.add(failedItem);
                }

                // ReferencedSOPSequence (0008,1199)
                Sequence referencedSeq = studyAttrs.newSequence(Tag.ReferencedSOPSequence, studyInstances.size());
                for (SuccessfulInstance success : studyInstances) {
                    Attributes refItem = new Attributes();
                    if (success.getSopClassUid() != null) {
                        refItem.setString(Tag.ReferencedSOPClassUID, VR.UI, success.getSopClassUid());
                    }
                    refItem.setString(Tag.ReferencedSOPInstanceUID, VR.UI, success.getSopInstanceUid());
                    refItem.setString(Tag.RetrieveURL, VR.UR, buildInstanceUrl(baseUrl, success));
                    referencedSeq.add(refItem);
                }

                jsonArray.append(DicomWebUtils.toJson(studyAttrs));
                logger.debug("Added study {} to response: {} successful, {} failed",
                    studyUid, studyInstances.size(), studyFailures.size());
            }

            // Add any failed instances without study UID as a separate entry
            if (!failedWithoutStudy.isEmpty()) {
                if (!first) {
                    jsonArray.append(",");
                }
                Attributes unknownStudyAttrs = new Attributes();
                unknownStudyAttrs.newSequence(Tag.ReferencedSOPSequence, 0);
                Sequence failedSeq = unknownStudyAttrs.newSequence(Tag.FailedSOPSequence, failedWithoutStudy.size());
                for (FailedInstance failure : failedWithoutStudy) {
                    Attributes failedItem = new Attributes();
                    failedItem.setInt(Tag.FailureReason, VR.US, failure.getFailureReason());
                    if (failure.hasSopUids()) {
                        failedItem.setString(Tag.ReferencedSOPClassUID, VR.UI, failure.getSopClassUid());
                        failedItem.setString(Tag.ReferencedSOPInstanceUID, VR.UI, failure.getSopInstanceUid());
                    }
                    failedSeq.add(failedItem);
                }
                jsonArray.append(DicomWebUtils.toJson(unknownStudyAttrs));
            }

            jsonArray.append("]");

            logger.info("Built STOW-RS response: {} studies, {} successful instances, {} failed instances",
                successByStudy.size(), successfulInstances.size(), failedInstances.size());
            return jsonArray.toString();

        } catch (Exception e) {
            logger.error("Error building STOW-RS response", e);
            return "[{\"error\": \"Failed to build response\"}]";
        }
    }

    /**
     * Build base DICOMweb URL from HTTP request
     * Example: http://localhost:8080/xapi/dicomweb/projects/ProjectID
     */
    private String buildBaseUrl(HttpServletRequest request, String projectId) {
        String scheme = request.getScheme();
        String serverName = request.getServerName();
        int serverPort = request.getServerPort();

        StringBuilder baseUrl = new StringBuilder();
        baseUrl.append(scheme).append("://").append(serverName);

        // Only add port if it's not the default for the scheme
        if ((scheme.equals("http") && serverPort != 80) ||
            (scheme.equals("https") && serverPort != 443)) {
            baseUrl.append(":").append(serverPort);
        }

        baseUrl.append("/xapi/dicomweb/projects/").append(projectId);
        return baseUrl.toString();
    }

    /**
     * Build DICOMweb WADO-RS instance URL per PS3.18 specification
     * Format: {base}/studies/{studyUID}/series/{seriesUID}/instances/{sopUID}
     */
    private String buildInstanceUrl(String baseUrl, SuccessfulInstance instance) {
        String studyUid = instance.getStudyInstanceUid();
        String seriesUid = instance.getSeriesInstanceUid();
        String sopUid = instance.getSopInstanceUid();

        // Build full WADO-RS instance URL
        if (studyUid != null && seriesUid != null && sopUid != null) {
            return baseUrl + "/studies/" + studyUid +
                   "/series/" + seriesUid +
                   "/instances/" + sopUid;
        }

        // Fallback to study-level URL if series/instance UIDs missing
        if (studyUid != null) {
            logger.warn("Missing series/instance UID for {}, using study-level URL", sopUid);
            return baseUrl + "/studies/" + studyUid;
        }

        // Final fallback - just base URL (shouldn't happen)
        logger.error("Missing study UID for instance {}, using base URL", sopUid);
        return baseUrl;
    }
}
