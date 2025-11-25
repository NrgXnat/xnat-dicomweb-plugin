/*
 * XNAT DICOMweb Proxy Plugin
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 */

package org.nrg.xnat.dicomweb.service.impl.strategy;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.io.DicomOutputStream;
import org.nrg.xdat.XDAT;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.dicomweb.parser.Mime4jHybridParser.MultipartPart;
import org.nrg.xnat.dicomweb.service.FailedInstance;
import org.nrg.xnat.dicomweb.utils.DicomValidationUtils;
import org.nrg.xnat.dicomweb.utils.DicomWebUtils;
import org.nrg.xnat.helpers.uri.URIManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DICOM import strategy using direct file write to prearchive.
 * This approach:
 * - Reads DICOM metadata from each part
 * - Groups instances by StudyInstanceUID
 * - Creates prearchive session directories
 * - Writes DICOM files directly using DicomOutputStream
 */
@Component
public class DirectWriteImporterStrategy implements DicomImportStrategy {

    private static final Logger logger = LoggerFactory.getLogger(DirectWriteImporterStrategy.class);

    @Override
    public String getName() {
        return "DirectWrite";
    }

    @Override
    public void importInstances(UserI user, List<MultipartPart> parts,
                                 Map<String, Object> params,
                                 Set<String> prearchiveUris,
                                 List<FailedInstance> failedInstances) {
        logger.info("Importing {} parts to prearchive via DirectWrite", parts.size());

        String projectId = (String) params.get(URIManager.PROJECT_ID);
        if (projectId == null) {
            logger.error("No projectId specified in params");
            failedInstances.add(FailedInstance.processingFailure(-1, "No projectId specified"));
            return;
        }

        // Generate timestamp for this import batch
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());

        // Group instances by StudyInstanceUID
        Map<String, List<DicomInstanceInfo>> studyGroups = new LinkedHashMap<>();

        // Phase 1: Parse all DICOM files and group by study
        for (int i = 0; i < parts.size(); i++) {
            MultipartPart part = parts.get(i);

            // Pre-validation
            FailedInstance validationFailure = DicomValidationUtils.validateDicomPart(part, i);
            if (validationFailure != null) {
                logger.warn("Part {} failed pre-validation: {}", i, validationFailure.getErrorMessage());
                failedInstances.add(validationFailure);
                continue;
            }

            try {
                // Read DICOM metadata
                Attributes attrs;
                try (InputStream is = part.getInputStream()) {
                    attrs = DicomWebUtils.readDicom(is);
                }

                String studyUid = attrs.getString(Tag.StudyInstanceUID);
                String seriesUid = attrs.getString(Tag.SeriesInstanceUID);
                String sopInstanceUid = attrs.getString(Tag.SOPInstanceUID);
                String sopClassUid = attrs.getString(Tag.SOPClassUID);
                String seriesNumber = attrs.getString(Tag.SeriesNumber, "1");

                if (studyUid == null || studyUid.isEmpty()) {
                    logger.warn("Part {} missing StudyInstanceUID", i);
                    failedInstances.add(FailedInstance.cannotUnderstand(i, "Missing StudyInstanceUID"));
                    continue;
                }

                if (sopInstanceUid == null || sopInstanceUid.isEmpty()) {
                    logger.warn("Part {} missing SOPInstanceUID", i);
                    failedInstances.add(FailedInstance.cannotUnderstand(i, "Missing SOPInstanceUID"));
                    continue;
                }

                // Add to study group
                studyGroups.computeIfAbsent(studyUid, k -> new ArrayList<>())
                    .add(new DicomInstanceInfo(i, attrs, part, sopClassUid, sopInstanceUid, seriesUid, seriesNumber));

                logger.debug("Part {} parsed: Study={}, Series={}, SOP={}",
                    i, studyUid, seriesUid, sopInstanceUid);

            } catch (IOException e) {
                logger.error("Failed to read DICOM metadata from part {}: {}", i, e.getMessage());
                failedInstances.add(FailedInstance.processingFailure(i, "Failed to read DICOM: " + e.getMessage()));
            }
        }

        logger.info("Grouped {} instances into {} studies",
            parts.size() - failedInstances.size(), studyGroups.size());

        // Phase 2: Create prearchive sessions and write files
        for (Map.Entry<String, List<DicomInstanceInfo>> entry : studyGroups.entrySet()) {
            String studyUid = entry.getKey();
            List<DicomInstanceInfo> instances = entry.getValue();

            try {
                // Create prearchive session directory
                String sessionUri = createPrearchiveSession(user, projectId, studyUid, timestamp, instances);

                if (sessionUri != null) {
                    // Write DICOM files to session directory
                    writeInstancesToSession(projectId, studyUid, timestamp, instances, failedInstances);

                    // Add to prearchiveUris so buildSessions() can register and build the session
                    prearchiveUris.add(sessionUri);

                    logger.info("DirectWrite: Files written to {}. Will be registered via buildSession().",
                        sessionUri);
                }
            } catch (Exception e) {
                logger.error("Failed to process study {}: {}", studyUid, e.getMessage(), e);
                for (DicomInstanceInfo info : instances) {
                    failedInstances.add(FailedInstance.processingFailure(info.partIndex,
                        "Study processing failed: " + e.getMessage()));
                }
            }
        }

        logger.info("DirectWrite completed: {} sessions, {} failures",
            prearchiveUris.size(), failedInstances.size());
    }

    /**
     * Create a prearchive session directory for the given study.
     * Calls PrearcUtils.buildSession() to register it in the prearchive database.
     */
    private String createPrearchiveSession(UserI user, String projectId, String studyUid,
                                          String timestamp, List<DicomInstanceInfo> instances)
            throws Exception {
        File prearchiveRoot = new File(XDAT.getSiteConfigPreferences().getPrearchivePath());

        String sessionName = studyUid;
        File sessionDir = new File(prearchiveRoot, projectId + "/" + timestamp + "/" + sessionName);

        if (!sessionDir.exists() && !sessionDir.mkdirs()) {
            throw new IOException("Failed to create session directory: " + sessionDir);
        }

        logger.info("Created prearchive session directory: {}", sessionDir);

        // First write the files (will be done by caller)
        // Then build the session to register it in PrearcDatabase
        // Note: We return the URI now, and the caller will write files,
        // then buildSessions() will be called separately

        return "/prearchive/projects/" + projectId + "/" + timestamp + "/" + sessionName;
    }

    /**
     * Write DICOM instances to the prearchive session directory.
     */
    private void writeInstancesToSession(String projectId, String studyUid, String timestamp,
                                          List<DicomInstanceInfo> instances,
                                          List<FailedInstance> failedInstances) {
        File prearchiveRoot = new File(XDAT.getSiteConfigPreferences().getPrearchivePath());
        String sessionName = studyUid;
        File sessionDir = new File(prearchiveRoot, projectId + "/" + timestamp + "/" + sessionName);

        // Create SCANS directory (XNAT standard structure)
        File scansDir = new File(sessionDir, "SCANS");

        for (DicomInstanceInfo info : instances) {
            try {
                // Create series subdirectory using series number (e39978f structure)
                String seriesNumber = info.seriesNumber != null ? info.seriesNumber : "1";
                File seriesDir = new File(scansDir, seriesNumber);
                if (!seriesDir.exists() && !seriesDir.mkdirs()) {
                    throw new IOException("Failed to create series directory: " + seriesDir);
                }

                // Write DICOM file
                String filename = info.sopInstanceUid + ".dcm";
                File dicomFile = new File(seriesDir, filename);

                try (FileOutputStream fos = new FileOutputStream(dicomFile);
                     DicomOutputStream dos = new DicomOutputStream(fos, UID.ExplicitVRLittleEndian)) {

                    Attributes fmi = info.attrs.createFileMetaInformation(
                        info.attrs.getString(Tag.TransferSyntaxUID, UID.ExplicitVRLittleEndian));
                    dos.writeDataset(fmi, info.attrs);
                }

                logger.debug("Written DICOM file: {}", dicomFile);

            } catch (Exception e) {
                logger.error("Failed to write instance {} to prearchive: {}",
                    info.partIndex, e.getMessage());
                failedInstances.add(FailedInstance.processingFailure(info.partIndex,
                    "Failed to write file: " + e.getMessage()));
            }
        }
    }

    /**
     * Helper class to hold DICOM instance information during processing.
     */
    private static class DicomInstanceInfo {
        final int partIndex;
        final Attributes attrs;
        final MultipartPart part;
        final String sopClassUid;
        final String sopInstanceUid;
        final String seriesUid;
        final String seriesNumber;

        DicomInstanceInfo(int partIndex, Attributes attrs, MultipartPart part,
                          String sopClassUid, String sopInstanceUid, String seriesUid, String seriesNumber) {
            this.partIndex = partIndex;
            this.attrs = attrs;
            this.part = part;
            this.sopClassUid = sopClassUid;
            this.sopInstanceUid = sopInstanceUid;
            this.seriesUid = seriesUid;
            this.seriesNumber = seriesNumber;
        }
    }
}
