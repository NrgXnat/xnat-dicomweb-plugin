/*
 * XNAT DICOMweb Plugin
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 */

package org.nrg.xnat.dicomweb.service.impl.strategy;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.io.DicomOutputStream;
import org.nrg.xdat.om.XnatProjectdata;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.archive.ArchivingException;
import org.nrg.xnat.archive.services.DirectArchiveSessionService;
import org.nrg.xnat.archive.services.DirectArchiveSessionHibernateService;
import org.nrg.xnat.dicomweb.parser.Mime4jHybridParser.MultipartPart;
import org.nrg.xnat.dicomweb.service.FailedInstance;
import org.nrg.xnat.dicomweb.service.StowRsImportResult;
import org.nrg.xnat.dicomweb.service.SuccessfulInstance;
import org.nrg.xnat.dicomweb.config.DicomWebPreferenceBean;
import org.nrg.xnat.dicomweb.util.DicomValidationUtils;
import org.nrg.xnat.dicomweb.util.DicomWebUtils;
import org.nrg.xnat.helpers.prearchive.PrearcUtils;
import org.nrg.xnat.helpers.prearchive.SessionData;
import org.nrg.xnat.helpers.uri.URIManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import javax.sql.DataSource;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DICOM import strategy using XNAT's Direct Archive mechanism.
 *
 * <p>This strategy provides immediate, synchronous archiving of DICOM instances:
 * <ul>
 *   <li>Writes files directly to archive directory (bypasses prearchive)</li>
 *   <li>Immediately builds session XML and archives to database</li>
 *   <li>Returns final experiment URLs in STOW-RS response</li>
 *   <li>No waiting for scheduled tasks or JMS queue</li>
 * </ul>
 *
 * <p>Processing flow:
 * <ol>
 *   <li>Parse and validate DICOM instances</li>
 *   <li>Group instances by StudyInstanceUID</li>
 *   <li>For each study:
 *     <ul>
 *       <li>Create/get DirectArchiveSession</li>
 *       <li>Write DICOM files to archive directory</li>
 *       <li>Build session XML</li>
 *       <li>Archive to XNAT database</li>
 *       <li>Return experiment URL</li>
 *     </ul>
 *   </li>
 * </ol>
 */
public class DirectArchiveStrategy implements DicomImportStrategy {

    private static final Logger logger = LoggerFactory.getLogger(DirectArchiveStrategy.class);

    // Constants
    private static final String STRATEGY_NAME = "DirectArchive";
    private static final String SCANS_DIRECTORY = "SCANS";
    private static final String DCM_EXTENSION = ".dcm";
    private static final String TIMESTAMP_FORMAT = "yyyyMMdd_HHmmss";
    private static final String DATE_FORMAT = "yyyyMMdd";
    private static final String EXPERIMENT_URL_FORMAT = "/data/experiments/%s";
    private static final String DIRECT_ARCHIVE_URL_FORMAT = "/xapi/direct-archive/%s/%s/%s";
    private static final String QUERY_EXPERIMENT_BY_UID =
        "SELECT e.id FROM xnat_imagesessiondata e " +
        "JOIN xnat_experimentdata x ON e.id = x.id " +
        "WHERE e.uid = ? AND x.project = ?";

    // Services
    private final DirectArchiveSessionService directArchiveSessionService;
    private final DirectArchiveSessionHibernateService directArchiveSessionHibernateService;

    /**
     * Whether the XNAT runtime supports the 3-arg getOrCreate (with overwriteMode).
     * Detected at construction time. When true, DirectArchive fully supports append/overwrite
     * and is suitable as the default import strategy.
     */
    private final boolean supportsOverwriteMode;

    // ========================================================================
    // Concurrent Build Protection
    // ========================================================================

    /**
     * Per-study build lock management.
     *
     * <p>Key format: {projectId}/{studyInstanceUID}
     * <p>Value: CompletableFuture that completes with the final experiment URL
     *
     * <p>This ensures that only one thread builds a given study at a time.
     * Other threads wait for the first thread to complete and reuse the result.
     */
    private final ConcurrentHashMap<String, CompletableFuture<String>> buildFutures = new ConcurrentHashMap<>();

    // ========================================================================
    // Deferred Build Support (when buildDelayMs > 0)
    // ========================================================================

    private final DicomWebPreferenceBean preferenceBean;

    /** Per-study last activity timestamp for deferred build scheduling. */
    private final ConcurrentHashMap<String, AtomicLong> lastActivityTime = new ConcurrentHashMap<>();

    /** Cached session data for deferred builds (keyed by buildKey). */
    private final ConcurrentHashMap<String, SessionData> deferredSessions = new ConcurrentHashMap<>();

    /** Cached user for deferred builds (keyed by buildKey). */
    private final ConcurrentHashMap<String, UserI> deferredUsers = new ConcurrentHashMap<>();

    /** Cached params for deferred builds (keyed by buildKey). */
    private final ConcurrentHashMap<String, Map<String, Object>> deferredParams = new ConcurrentHashMap<>();

    /** Cached sessionUris references for deferred builds (keyed by buildKey). */
    private final ConcurrentHashMap<String, Set<String>> deferredSessionUris = new ConcurrentHashMap<>();

    /** Scheduler for deferred build checks. */
    private final ScheduledExecutorService buildScheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "direct-archive-build-scheduler");
        t.setDaemon(true);
        return t;
    });

    public DirectArchiveStrategy(DirectArchiveSessionService directArchiveSessionService,
                                  DirectArchiveSessionHibernateService directArchiveSessionHibernateService,
                                  DicomWebPreferenceBean preferenceBean) {
        this.directArchiveSessionService = directArchiveSessionService;
        this.directArchiveSessionHibernateService = directArchiveSessionHibernateService;
        this.preferenceBean = preferenceBean;
        this.supportsOverwriteMode = detectOverwriteSupport();
    }

    private boolean detectOverwriteSupport() {
        try {
            directArchiveSessionService.getClass()
                    .getMethod("getOrCreate", SessionData.class, AtomicBoolean.class, String.class);
            logger.info("XNAT supports DirectArchive overwrite mode (3-arg getOrCreate)");
            return true;
        } catch (NoSuchMethodException e) {
            logger.info("XNAT does not support DirectArchive overwrite mode (2-arg getOrCreate only)");
            return false;
        }
    }

    /**
     * Whether this XNAT version fully supports DirectArchive with append/overwrite.
     * Used by StowRsServiceImpl to determine the appropriate default strategy.
     */
    public boolean supportsOverwriteMode() {
        return supportsOverwriteMode;
    }

    // ========================================================================
    // Public API Methods
    // ========================================================================

    @Override
    public String getName() {
        return STRATEGY_NAME;
    }

    /**
     * Import DICOM instances and return complete results.
     *
     * <p>This implementation:
     * <ol>
     *   <li>Writes files directly to archive directory</li>
     *   <li>Builds session XML and archives to database immediately</li>
     *   <li>Returns final experiment URLs</li>
     * </ol>
     *
     * <p><b>Note</b>: Future enhancement will add per-study build locks
     * to handle concurrent uploads safely.
     *
     * @param user The authenticated user
     * @param parts The parsed multipart parts
     * @param params Import parameters
     * @param request HTTP request (currently unused, for future DICOMweb URL building)
     * @return Import result with final experiment URLs and instance status
     */
    @Override
    public StowRsImportResult importInstances(UserI user,
                                             List<MultipartPart> parts,
                                             Map<String, Object> params,
                                             HttpServletRequest request) {
        Set<String> sessionUris = new HashSet<>();
        List<SuccessfulInstance> successfulInstances = new ArrayList<>();
        List<FailedInstance> failedInstances = new ArrayList<>();

        // Call legacy method to do actual import
        importInstances(user, parts, params, sessionUris, successfulInstances, failedInstances);

        // Return final experiment URLs (already built and archived)
        return new StowRsImportResult(sessionUris, successfulInstances, failedInstances);
    }

    @Override
    public void importInstances(UserI user, List<MultipartPart> parts,
                                 Map<String, Object> params,
                                 Set<String> sessionUris,
                                 List<SuccessfulInstance> successfulInstances,
                                 List<FailedInstance> failedInstances) {
        logger.info("Importing {} parts to archive via DirectArchive", parts.size());

        // Validate project
        XnatProjectdata project = validateAndGetProject(user, params, failedInstances);
        if (project == null) {
            return; // Error already added to failedInstances
        }

        // Generate timestamp for this batch
        String timestamp = new SimpleDateFormat(TIMESTAMP_FORMAT).format(new Date());

        // Phase 1: Parse and group instances by study
        Map<String, List<DicomInstanceInfo>> studyGroups = parseAndGroupInstances(parts, failedInstances);
        logger.info("Grouped {} instances into {} studies", parts.size(), studyGroups.size());

        // Phase 2: Process each study
        processStudies(user, project, studyGroups, timestamp, params,
                      sessionUris, successfulInstances, failedInstances);
    }

    // ========================================================================
    // Study Processing Methods
    // ========================================================================

    /**
     * Parse DICOM parts and group by StudyInstanceUID
     */
    private Map<String, List<DicomInstanceInfo>> parseAndGroupInstances(
            List<MultipartPart> parts, List<FailedInstance> failedInstances) {

        Map<String, List<DicomInstanceInfo>> studyGroups = new LinkedHashMap<>();

        for (int i = 0; i < parts.size(); i++) {
            MultipartPart part = parts.get(i);

            // Validate part
            FailedInstance validationFailure = DicomValidationUtils.validateDicomPart(part, i);
            if (validationFailure != null) {
                logger.warn("Part {} failed validation: {}", i, validationFailure.getErrorMessage());
                failedInstances.add(validationFailure);
                continue;
            }

            // Parse DICOM
            try {
                DicomInstanceInfo info = parseDicomInstance(part, i);
                studyGroups.computeIfAbsent(info.getStudyUid(), k -> new ArrayList<>()).add(info);
                logger.debug("Parsed instance {} - Study: {}, Series: {}, SOP: {}",
                        i, info.getStudyUid(), info.seriesInstanceUid, info.sopInstanceUid);
            } catch (Exception e) {
                logger.error("Error parsing DICOM file at index {}", i, e);
                failedInstances.add(FailedInstance.processingFailure(i,
                        "Error parsing DICOM: " + e.getMessage()));
            }
        }

        return studyGroups;
    }

    /**
     * Parse a single DICOM instance
     */
    private DicomInstanceInfo parseDicomInstance(MultipartPart part, int index) throws IOException {
        Attributes attrs;
        try (InputStream is = part.getInputStream()) {
            attrs = DicomWebUtils.readDicom(is);
        }

        String studyUid = attrs.getString(Tag.StudyInstanceUID);
        if (studyUid == null) {
            throw new IOException("Missing StudyInstanceUID");
        }

        return new DicomInstanceInfo(
                part, attrs,
                attrs.getString(Tag.SOPInstanceUID),
                attrs.getString(Tag.SOPClassUID),
                studyUid,
                attrs.getString(Tag.SeriesInstanceUID),
                index
        );
    }

    /**
     * Process all study groups
     */
    private void processStudies(UserI user, XnatProjectdata project,
                                Map<String, List<DicomInstanceInfo>> studyGroups,
                                String timestamp, Map<String, Object> params,
                                Set<String> sessionUris,
                                List<SuccessfulInstance> successfulInstances,
                                List<FailedInstance> failedInstances) {

        for (Map.Entry<String, List<DicomInstanceInfo>> entry : studyGroups.entrySet()) {
            String studyUid = entry.getKey();
            List<DicomInstanceInfo> instances = entry.getValue();

            logger.info("Processing study {} with {} instances", studyUid, instances.size());

            try {
                processStudy(user, project, studyUid, instances, timestamp, params,
                           sessionUris, successfulInstances, failedInstances);
            } catch (Exception e) {
                logger.error("Error processing study {}", studyUid, e);
                markStudyAsFailed(instances, failedInstances, e.getMessage());
            }
        }
    }

    /**
     * Process a single study with per-study build locks.
     *
     * <p>Operates in two modes based on the {@code dicomweb.buildDelayMs} preference:
     *
     * <h3>Immediate mode (buildDelayMs == 0)</h3>
     * <ul>
     *   <li>First thread: Creates session, writes files, builds and archives</li>
     *   <li>Concurrent threads (same request): Write files and reuse build result</li>
     *   <li>Build future removed immediately after build completes</li>
     * </ul>
     *
     * <h3>Deferred mode (buildDelayMs &gt; 0)</h3>
     * <ul>
     *   <li>All threads: Write files to archive directory immediately</li>
     *   <li>First thread for a study: Schedules a deferred build check</li>
     *   <li>Build is deferred until no new uploads arrive within the delay window</li>
     *   <li>All threads block on a shared future until the build completes</li>
     *   <li>Allows multi-request uploads to be grouped into a single session build</li>
     * </ul>
     *
     * <p>Build lock key format: {projectId}/{studyInstanceUID}
     */
    private void processStudy(UserI user, XnatProjectdata project, String studyUid,
                             List<DicomInstanceInfo> instances, String timestamp,
                             Map<String, Object> params, Set<String> sessionUris,
                             List<SuccessfulInstance> successfulInstances,
                             List<FailedInstance> failedInstances) {

        long buildDelayMs = preferenceBean.getBuildDelayMs();

        if (buildDelayMs <= 0) {
            processStudyImmediate(user, project, studyUid, instances, timestamp, params,
                                 sessionUris, successfulInstances, failedInstances);
        } else {
            processStudyDeferred(user, project, studyUid, instances, timestamp, params,
                                sessionUris, successfulInstances, failedInstances, buildDelayMs);
        }
    }

    /**
     * Immediate mode: build and archive within the current request (original behavior).
     */
    private void processStudyImmediate(UserI user, XnatProjectdata project, String studyUid,
                                       List<DicomInstanceInfo> instances, String timestamp,
                                       Map<String, Object> params, Set<String> sessionUris,
                                       List<SuccessfulInstance> successfulInstances,
                                       List<FailedInstance> failedInstances) {

        String buildKey = project.getId() + "/" + studyUid;
        logger.debug("Processing study (immediate mode) with build key: {}", buildKey);

        CompletableFuture<String> buildFuture = buildFutures.computeIfAbsent(buildKey, k -> {
            logger.info("First thread for study {}, will perform build", buildKey);
            return new CompletableFuture<>();
        });

        try {
            SessionData session = createOrGetSession(user, project, studyUid,
                                                    instances.get(0), timestamp, params);
            logger.info("Using DirectArchiveSession: {}", session.getSessionDataTriple());

            List<DicomInstanceInfo> writtenInstances = writeInstancesToArchive(session, instances, failedInstances);
            logger.info("Wrote {} instances to archive for study {}", writtenInstances.size(), studyUid);

            if (buildFuture.isDone()) {
                logger.info("Study {} already built by another thread, reusing result", buildKey);
                String finalUri = buildFuture.get();
                addSuccessfulInstances(writtenInstances, finalUri, successfulInstances);
                return;
            }

            logger.info("Building and archiving session for study {}", buildKey);
            String finalUri = buildAndArchiveSession(user, session, params, sessionUris);

            buildFuture.complete(finalUri);
            logger.info("Completed build future for study {} with URI: {}", buildKey, finalUri);

            addSuccessfulInstances(writtenInstances, finalUri, successfulInstances);

        } catch (ArchivingException e) {
            logger.error("Failed to create DirectArchiveSession for study {}", studyUid, e);
            buildFuture.completeExceptionally(e);
            throw new RuntimeException("Failed to create DirectArchiveSession: " + e.getMessage(), e);
        } catch (Exception e) {
            logger.error("Error processing study {}", buildKey, e);
            buildFuture.completeExceptionally(e);
            throw new RuntimeException("Error processing study: " + e.getMessage(), e);
        } finally {
            buildFutures.remove(buildKey);
        }
    }

    /**
     * Deferred mode: write files now, return immediately with a pending URI,
     * and defer the build/archive until no new uploads arrive within the delay window.
     *
     * <p>The HTTP response returns as soon as files are written, allowing the caller
     * to send subsequent batches without waiting. The build fires asynchronously
     * in the background after the delay elapses.
     */
    private void processStudyDeferred(UserI user, XnatProjectdata project, String studyUid,
                                      List<DicomInstanceInfo> instances, String timestamp,
                                      Map<String, Object> params, Set<String> sessionUris,
                                      List<SuccessfulInstance> successfulInstances,
                                      List<FailedInstance> failedInstances, long buildDelayMs) {

        String buildKey = project.getId() + "/" + studyUid;
        logger.debug("Processing study (deferred mode, delay={}ms) with build key: {}", buildDelayMs, buildKey);

        // Get or create shared future. Track whether this thread is the first for this study.
        AtomicBoolean isFirstThread = new AtomicBoolean(false);
        buildFutures.computeIfAbsent(buildKey, k -> {
            isFirstThread.set(true);
            logger.info("First thread for deferred study {}, will schedule build check", buildKey);
            return new CompletableFuture<>();
        });

        try {
            // Create or get session and write files (every thread does this)
            SessionData session = createOrGetSession(user, project, studyUid,
                                                    instances.get(0), timestamp, params);
            logger.info("Using DirectArchiveSession (deferred): {}", session.getSessionDataTriple());

            List<DicomInstanceInfo> writtenInstances = writeInstancesToArchive(session, instances, failedInstances);
            logger.info("Wrote {} instances to archive for study {} (deferred build pending)",
                       writtenInstances.size(), studyUid);

            // Update last activity time (signals "files just arrived for this study")
            lastActivityTime.computeIfAbsent(buildKey, k -> new AtomicLong())
                            .set(System.currentTimeMillis());

            // Cache context needed by the deferred build (idempotent - first writer wins)
            deferredSessions.putIfAbsent(buildKey, session);
            deferredUsers.putIfAbsent(buildKey, user);
            deferredParams.putIfAbsent(buildKey, params);
            deferredSessionUris.putIfAbsent(buildKey, sessionUris);

            // If first thread for this study, schedule the deferred build check
            if (isFirstThread.get()) {
                scheduleDeferredBuildCheck(buildKey, buildDelayMs);
            }

            // Return immediately with a pending DirectArchive URI.
            // The build will happen asynchronously after the delay elapses.
            String pendingUri = String.format(DIRECT_ARCHIVE_URL_FORMAT,
                    session.getProject(), session.getTag(), session.getName());
            sessionUris.add(pendingUri);
            logger.info("Returning pending URI for study {} (build deferred): {}", buildKey, pendingUri);

            addSuccessfulInstances(writtenInstances, pendingUri, successfulInstances);

        } catch (ArchivingException e) {
            logger.error("Failed to create DirectArchiveSession for study {}", studyUid, e);
            throw new RuntimeException("Failed to create DirectArchiveSession: " + e.getMessage(), e);
        } catch (Exception e) {
            logger.error("Error processing study {}", buildKey, e);
            throw new RuntimeException("Error processing study: " + e.getMessage(), e);
        }
        // NOTE: No buildFutures.remove() here - cleanup happens in executeDeferredBuild()
    }

    // ========================================================================
    // Deferred Build Scheduling
    // ========================================================================

    /**
     * Schedule a deferred build check for a study.
     * After {@code scheduleDelayMs} elapses, checks whether at least {@code buildDelayMs}
     * has passed since the last upload activity. If so, triggers the build.
     * Otherwise, reschedules with the remaining time.
     *
     * <p>The two delay parameters serve different purposes:
     * <ul>
     *   <li>{@code buildDelayMs} — the full activity threshold (how long since last upload
     *       before considering the session complete). Always compared against the full value.</li>
     *   <li>{@code scheduleDelayMs} — how long to wait before the next check. On reschedule,
     *       this is shortened to the remaining time to avoid unnecessary waiting.</li>
     * </ul>
     */
    private void scheduleDeferredBuildCheck(String buildKey, long buildDelayMs, long scheduleDelayMs) {
        buildScheduler.schedule(() -> {
            AtomicLong lastActivity = lastActivityTime.get(buildKey);
            if (lastActivity == null) {
                logger.warn("No last activity time found for deferred study {}, skipping", buildKey);
                return;
            }

            long timeSinceLastActivity = System.currentTimeMillis() - lastActivity.get();

            if (timeSinceLastActivity >= buildDelayMs) {
                // Full delay window elapsed with no new uploads - build now
                logger.info("Build delay elapsed for study {} ({}ms since last activity, threshold {}ms), triggering build",
                           buildKey, timeSinceLastActivity, buildDelayMs);
                executeDeferredBuild(buildKey);
            } else {
                // Activity happened recently - reschedule for remaining time
                long remainingMs = buildDelayMs - timeSinceLastActivity;
                logger.debug("Study {} not ready ({}ms of {}ms since last activity), rescheduling in {}ms",
                           buildKey, timeSinceLastActivity, buildDelayMs, remainingMs);
                scheduleDeferredBuildCheck(buildKey, buildDelayMs, remainingMs);
            }
        }, scheduleDelayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Schedule a deferred build check with the initial delay equal to the full build delay.
     */
    private void scheduleDeferredBuildCheck(String buildKey, long buildDelayMs) {
        scheduleDeferredBuildCheck(buildKey, buildDelayMs, buildDelayMs);
    }

    /**
     * Execute the deferred build for a study. Called by the scheduler when the
     * delay window has elapsed with no new uploads.
     */
    private void executeDeferredBuild(String buildKey) {
        CompletableFuture<String> buildFuture = buildFutures.get(buildKey);
        if (buildFuture == null || buildFuture.isDone()) {
            logger.debug("Deferred build for {} already completed or missing, skipping", buildKey);
            return;
        }

        SessionData session = deferredSessions.get(buildKey);
        UserI user = deferredUsers.get(buildKey);
        Map<String, Object> params = deferredParams.get(buildKey);
        Set<String> sessionUris = deferredSessionUris.get(buildKey);

        if (session == null || user == null || params == null || sessionUris == null) {
            logger.error("Missing deferred build context for {}", buildKey);
            buildFuture.completeExceptionally(
                new RuntimeException("Missing deferred build context for " + buildKey));
            cleanupDeferredState(buildKey);
            return;
        }

        try {
            logger.info("Executing deferred build for study {}", buildKey);
            String finalUri = buildAndArchiveSession(user, session, params, sessionUris);
            buildFuture.complete(finalUri);
            logger.info("Deferred build completed for study {} with URI: {}", buildKey, finalUri);
        } catch (Exception e) {
            logger.error("Deferred build failed for study {}", buildKey, e);
            buildFuture.completeExceptionally(e);
        } finally {
            cleanupDeferredState(buildKey);
        }
    }

    /**
     * Clean up all deferred state maps for a given build key.
     */
    private void cleanupDeferredState(String buildKey) {
        buildFutures.remove(buildKey);
        lastActivityTime.remove(buildKey);
        deferredSessions.remove(buildKey);
        deferredUsers.remove(buildKey);
        deferredParams.remove(buildKey);
        deferredSessionUris.remove(buildKey);
    }

    /**
     * Write all instances for a study to archive
     */
    private List<DicomInstanceInfo> writeInstancesToArchive(SessionData session,
                                                            List<DicomInstanceInfo> instances,
                                                            List<FailedInstance> failedInstances) {
        List<DicomInstanceInfo> writtenInstances = new ArrayList<>();

        for (DicomInstanceInfo info : instances) {
            try {
                writeInstanceToArchive(session, info);
                writtenInstances.add(info);
                logger.debug("Successfully wrote instance {} to archive", info.sopInstanceUid);
            } catch (Exception e) {
                logger.error("Failed to write instance {} to archive", info.sopInstanceUid, e);
                failedInstances.add(FailedInstance.processingFailure(info.partIndex,
                        "Failed to write to archive: " + e.getMessage()));
            }
        }

        return writtenInstances;
    }

    // ========================================================================
    // Session Management Methods
    // ========================================================================

    /**
     * Create or retrieve existing DirectArchiveSession
     */
    private SessionData createOrGetSession(UserI user, XnatProjectdata project,
                                          String studyUid, DicomInstanceInfo firstInstance,
                                          String timestamp, Map<String, Object> params)
            throws ArchivingException {

        SessionData initialize = buildSessionData(project, studyUid, firstInstance, timestamp, params);

        AtomicBoolean isNew = new AtomicBoolean();
        SessionData session = invokeGetOrCreate(initialize, isNew, params);

        logger.info("{} DirectArchiveSession: {}",
                   isNew.get() ? "Created new" : "Using existing",
                   session.getSessionDataTriple());

        return session;
    }

    /**
     * Invoke DirectArchiveSessionService.getOrCreate, handling the signature difference between
     * XNAT versions (2-arg in 1.9.3-RC, 3-arg with overwriteMode in later versions).
     */
    private SessionData invokeGetOrCreate(SessionData initialize, AtomicBoolean isNew,
                                           Map<String, Object> params) throws ArchivingException {
        // Try 3-arg version first (newer XNAT with overwrite support)
        try {
            java.lang.reflect.Method method = directArchiveSessionService.getClass()
                    .getMethod("getOrCreate", SessionData.class, AtomicBoolean.class, String.class);
            String overwriteMode = (String) params.getOrDefault("overwrite", "append");
            return (SessionData) method.invoke(directArchiveSessionService, initialize, isNew, overwriteMode);
        } catch (NoSuchMethodException e) {
            // Fall back to 2-arg version (older XNAT)
            logger.debug("Using 2-arg getOrCreate (overwrite mode not supported by this XNAT version)");
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ArchivingException) {
                throw (ArchivingException) cause;
            }
            throw new ArchivingException(cause != null ? cause.getMessage() : e.getMessage());
        } catch (Exception e) {
            throw new ArchivingException("Failed to invoke getOrCreate: " + e.getMessage());
        }

        // 2-arg fallback (older XNAT). XNAT 1.11.0 exposes only the 3-arg getOrCreate, so invoke the
        // legacy 2-arg form reflectively — this keeps compiling against 1.11.0 while still working at
        // runtime on an older XNAT that has the 2-arg method.
        try {
            java.lang.reflect.Method method2 = directArchiveSessionService.getClass()
                    .getMethod("getOrCreate", SessionData.class, AtomicBoolean.class);
            return (SessionData) method2.invoke(directArchiveSessionService, initialize, isNew);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ArchivingException) {
                throw (ArchivingException) cause;
            }
            throw new ArchivingException(cause != null ? cause.getMessage() : e.getMessage());
        } catch (Exception e) {
            throw new ArchivingException("Failed to invoke getOrCreate: " + e.getMessage());
        }
    }

    /**
     * Build SessionData from DICOM attributes
     */
    private SessionData buildSessionData(XnatProjectdata project, String studyUid,
                                         DicomInstanceInfo firstInstance,
                                         String timestamp, Map<String, Object> params) {
        Attributes attrs = firstInstance.attributes;

        // Extract DICOM metadata
        String patientId = attrs.getString(Tag.PatientID, "UNKNOWN");
        String patientName = attrs.getString(Tag.PatientName, patientId);
        String studyDate = attrs.getString(Tag.StudyDate);
        String studyTime = attrs.getString(Tag.StudyTime);

        // Determine session label and folder
        String sessionLabel = sanitizeLabel(patientId);
        String folderName = sessionLabel;

        if (params.containsKey(URIManager.EXPT_LABEL)) {
            sessionLabel = (String) params.get(URIManager.EXPT_LABEL);
        }
        if (params.containsKey(PrearcUtils.PREARC_SESSION_FOLDER)) {
            folderName = (String) params.get(PrearcUtils.PREARC_SESSION_FOLDER);
        }

        // Build SessionData
        SessionData sessionData = new SessionData();
        sessionData.setProject(project.getId());
        sessionData.setName(sessionLabel);
        sessionData.setFolderName(folderName);
        sessionData.setSubject(sanitizeLabel(patientName));
        sessionData.setTag(studyUid);
        sessionData.setTimestamp(timestamp);
        sessionData.setScan_date(parseStudyDateTime(studyDate, studyTime));
        sessionData.setStatus(PrearcUtils.PrearcStatus.RECEIVING);
        sessionData.setLastBuiltDate(Calendar.getInstance().getTime());

        // Set archive directory path
        File archiveRoot = new File(project.getRootArchivePath(), project.getCurrentArc());
        File sessionDir = new File(archiveRoot, folderName);
        sessionData.setUrl(sessionDir.getAbsolutePath());

        // Set optional parameters
        sessionData.setSource(params.get(URIManager.SOURCE));
        if (params.containsKey(URIManager.PREVENT_ANON)) {
            sessionData.setPreventAnon(Boolean.valueOf((String) params.get(URIManager.PREVENT_ANON)));
        }

        return sessionData;
    }

    // ========================================================================
    // Archive Operations
    // ========================================================================

    /**
     * Build session XML and archive to database immediately
     */
    private String buildAndArchiveSession(UserI user, SessionData session,
                                         Map<String, Object> params,
                                         Set<String> sessionUris) {
        try {
            // Clear lock files
            clearSessionLocks(session);

            // Set status to QUEUED_BUILDING (bypasses JMS)
            directArchiveSessionHibernateService.setStatusToQueuedBuilding(session.getId());
            logger.debug("Set status to QUEUED_BUILDING for session: {}", session.getSessionDataTriple());

            // Build and archive synchronously
            String experimentId = archiveSessionSynchronously(user, session, params);
            logger.info("Successfully built and archived session: {}", session.getSessionDataTriple());

            // Build final URI
            String finalUri = String.format(EXPERIMENT_URL_FORMAT, experimentId);
            sessionUris.add(finalUri);
            return finalUri;

        } catch (Exception e) {
            logger.error("Failed to build/archive session: {}", session.getSessionDataTriple(), e);
            logger.warn("Falling back to DirectArchive URL. Scheduled task will process this session.");

            // Return temporary DirectArchive URI
            String fallbackUri = String.format(DIRECT_ARCHIVE_URL_FORMAT,
                    session.getProject(), session.getTag(), session.getName());
            sessionUris.add(fallbackUri);
            return fallbackUri;
        }
    }

    /**
     * Execute synchronous build and archive operations
     */
    private String archiveSessionSynchronously(UserI user, SessionData session,
                                               Map<String, Object> params) throws Exception {
        // Build session XML
        logger.debug("Building session XML for: {}", session.getSessionDataTriple());
        directArchiveSessionService.build(session.getId());
        logger.info("Successfully built session XML: {}", session.getSessionDataTriple());

        // Archive to database
        logger.debug("Archiving session to database: {}", session.getSessionDataTriple());
        directArchiveSessionService.archive(session.getId());
        logger.info("Successfully archived session to database: {}", session.getSessionDataTriple());

        // Query experiment ID
        String experimentId = queryExperimentId(session.getTag(), session.getProject(), user);
        if (experimentId == null) {
            throw new Exception("Failed to find experiment ID after archiving");
        }

        return experimentId;
    }

    /**
     * Query experiment ID by StudyInstanceUID
     */
    private String queryExperimentId(String studyUid, String projectId, UserI user) {
        // Try primary method
        try {
            org.nrg.xdat.om.XnatImagesessiondata experiment =
                org.nrg.xdat.om.XnatImagesessiondata.getXnatImagesessiondatasById(studyUid, user, false);
            if (experiment != null) {
                return experiment.getId();
            }
        } catch (Exception e) {
            logger.debug("Primary query failed, trying SQL: {}", e.getMessage());
        }

        // Try SQL query
        try {
            return queryExperimentIdBySQL(studyUid, projectId);
        } catch (Exception e) {
            logger.warn("Failed to query experiment ID for study UID {} in project {}: {}", studyUid, projectId, e.getMessage());
            return null;
        }
    }

    /**
     * Query experiment ID using direct SQL
     */
    private String queryExperimentIdBySQL(String studyUid, String projectId) throws Exception {
        DataSource dataSource = org.nrg.xdat.XDAT.getContextService().getBean(DataSource.class);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(QUERY_EXPERIMENT_BY_UID)) {

            stmt.setString(1, studyUid);
            stmt.setString(2, projectId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("id");
                }
            }
        }

        return null;
    }

    /**
     * Clear session lock files to mark upload as complete
     */
    private void clearSessionLocks(SessionData session) {
        try {
            File lockFolder = org.nrg.xnat.utils.FileUtils.buildCacheSubDir("prearc_locks",
                    session.getProject(),
                    session.getTimestamp() + session.getName());

            if (lockFolder.exists() && lockFolder.isDirectory()) {
                File[] lockFiles = lockFolder.listFiles();
                if (lockFiles != null) {
                    for (File lockFile : lockFiles) {
                        if (lockFile.delete()) {
                            logger.debug("Deleted lock file: {}", lockFile.getName());
                        }
                    }
                    logger.info("Cleared {} lock files for session: {}",
                            lockFiles.length, session.getSessionDataTriple());
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to clear lock files for session: {}", session.getSessionDataTriple(), e);
            // Not fatal - scheduled task will handle it
        }
    }

    // ========================================================================
    // File I/O Methods
    // ========================================================================

    /**
     * Write a single DICOM instance to archive directory
     */
    private void writeInstanceToArchive(SessionData session, DicomInstanceInfo info) throws IOException {
        Attributes attrs = info.attributes;
        String seriesNumber = attrs.getString(Tag.SeriesNumber, "0");

        // Sanitize series number
        if (seriesNumber.startsWith("+")) {
            seriesNumber = "_" + seriesNumber.substring(1);
        }

        // Build file path: {session}/SCANS/{seriesNumber}/{sopInstanceUID}.dcm
        File sessionDir = new File(session.getUrl());
        File scanDir = new File(sessionDir, SCANS_DIRECTORY + File.separator + seriesNumber);

        if (!scanDir.exists() && !scanDir.mkdirs()) {
            throw new IOException("Failed to create scan directory: " + scanDir);
        }

        File outputFile = new File(scanDir, info.sopInstanceUid + DCM_EXTENSION);
        logger.debug("Writing instance to: {}", outputFile.getAbsolutePath());

        // Write DICOM file with file meta information
        try (FileOutputStream fos = new FileOutputStream(outputFile);
             BufferedOutputStream bos = new BufferedOutputStream(fos);
             DicomOutputStream dos = new DicomOutputStream(bos, attrs.getString(Tag.TransferSyntaxUID))) {

            Attributes fmi = attrs.createFileMetaInformation(attrs.getString(Tag.TransferSyntaxUID));
            dos.writeDataset(fmi, attrs);
        }

        logger.debug("Successfully wrote {} bytes to {}", outputFile.length(), outputFile.getName());
    }

    // ========================================================================
    // Utility Methods
    // ========================================================================

    /**
     * Validate project and check user access
     */
    private XnatProjectdata validateAndGetProject(UserI user, Map<String, Object> params,
                                                  List<FailedInstance> failedInstances) {
        String projectId = (String) params.get(URIManager.PROJECT_ID);
        if (projectId == null) {
            logger.error("No projectId specified in params");
            failedInstances.add(FailedInstance.processingFailure(-1, "No projectId specified"));
            return null;
        }

        try {
            XnatProjectdata project = XnatProjectdata.getXnatProjectdatasById(projectId, user, false);
            if (project == null) {
                logger.error("Project '{}' not found or user '{}' does not have access",
                        projectId, user.getUsername());
                failedInstances.add(FailedInstance.processingFailure(-1,
                        "Project '" + projectId + "' not found or access denied"));
                return null;
            }
            return project;
        } catch (Exception e) {
            logger.error("Error validating project '{}'", projectId, e);
            failedInstances.add(FailedInstance.processingFailure(-1,
                    "Error validating project: " + e.getMessage()));
            return null;
        }
    }

    /**
     * Mark all instances in a study as failed
     */
    private void markStudyAsFailed(List<DicomInstanceInfo> instances,
                                  List<FailedInstance> failedInstances,
                                  String errorMessage) {
        for (DicomInstanceInfo info : instances) {
            failedInstances.add(FailedInstance.processingFailure(info.partIndex,
                    "Study processing failed: " + errorMessage));
        }
    }

    /**
     * Add successful instances to response
     */
    private void addSuccessfulInstances(List<DicomInstanceInfo> instances,
                                       String uri,
                                       List<SuccessfulInstance> successfulInstances) {
        for (DicomInstanceInfo info : instances) {
            successfulInstances.add(new SuccessfulInstance(
                    info.sopClassUid,        // SOP Class UID
                    info.sopInstanceUid,     // SOP Instance UID
                    info.studyInstanceUid,   // Study Instance UID
                    info.seriesInstanceUid,  // Series Instance UID
                    uri
            ));
        }
    }

    /**
     * Sanitize label for XNAT (alphanumeric + underscore only)
     */
    private String sanitizeLabel(String label) {
        if (label == null) {
            return "UNKNOWN";
        }
        return label.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    /**
     * Parse DICOM study date/time into Java Date
     */
    private Date parseStudyDateTime(String studyDate, String studyTime) {
        if (studyDate == null) {
            return null;
        }

        try {
            SimpleDateFormat dateFormat = new SimpleDateFormat(DATE_FORMAT);
            Date date = dateFormat.parse(studyDate);

            if (studyTime != null && date != null) {
                String timeStr = studyTime.replaceAll("[^0-9]", "");
                if (timeStr.length() >= 6) {
                    Calendar cal = Calendar.getInstance();
                    cal.setTime(date);
                    cal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(timeStr.substring(0, 2)));
                    cal.set(Calendar.MINUTE, Integer.parseInt(timeStr.substring(2, 4)));
                    cal.set(Calendar.SECOND, Integer.parseInt(timeStr.substring(4, 6)));
                    return cal.getTime();
                }
            }

            return date;
        } catch (Exception e) {
            logger.warn("Failed to parse study date/time: {}/{}", studyDate, studyTime, e);
            return null;
        }
    }

    // ========================================================================
    // Internal Data Classes
    // ========================================================================

    /**
     * Holds DICOM instance information during processing
     */
    private static class DicomInstanceInfo {
        final MultipartPart part;
        final Attributes attributes;
        final String sopInstanceUid;
        final String sopClassUid;
        final String studyInstanceUid;
        final String seriesInstanceUid;
        final int partIndex;

        DicomInstanceInfo(MultipartPart part, Attributes attributes,
                         String sopInstanceUid, String sopClassUid,
                         String studyInstanceUid, String seriesInstanceUid,
                         int partIndex) {
            this.part = part;
            this.attributes = attributes;
            this.sopInstanceUid = sopInstanceUid;
            this.sopClassUid = sopClassUid;
            this.studyInstanceUid = studyInstanceUid;
            this.seriesInstanceUid = seriesInstanceUid;
            this.partIndex = partIndex;
        }

        String getStudyUid() {
            return studyInstanceUid;
        }
    }
}
