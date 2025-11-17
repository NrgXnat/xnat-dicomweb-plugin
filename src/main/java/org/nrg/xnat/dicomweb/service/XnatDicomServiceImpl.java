package org.nrg.xnat.dicomweb.service;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomInputStream;
import org.dcm4che3.image.BufferedImageUtils;
import org.dcm4che3.imageio.plugins.dcm.DicomImageReadParam;
import org.nrg.xdat.om.XnatProjectdata;
import org.nrg.xdat.om.XnatImagesessiondata;
import org.nrg.action.ServerException;
import org.nrg.xdat.model.CatEntryI;
import org.nrg.xdat.om.XnatImagescandata;
import org.nrg.xdat.om.XnatAbstractresource;
import org.nrg.xdat.om.XnatResourcecatalog;
import org.nrg.xft.security.UserI;
import org.nrg.xft.XFTItem;
import org.nrg.xft.search.CriteriaCollection;
import org.nrg.xnat.utils.CatalogUtils;
import org.nrg.xnat.dicomweb.utils.DicomWebUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * XNAT 1.9.x implementation of DICOM service
 */
@Service
public class XnatDicomServiceImpl implements XnatDicomService {

    private static final Logger logger = LoggerFactory.getLogger(XnatDicomServiceImpl.class);

    @Override
    public List<Attributes> searchStudies(UserI user, String projectId, Attributes queryAttributes) {
        List<Attributes> results = new ArrayList<>();

        try {
            // Get project and check permissions
            XnatProjectdata project = XnatProjectdata.getXnatProjectdatasById(projectId, user, false);
            if (project == null) {
                logger.warn("Project not found or user does not have access: {}", projectId);
                return results;
            }

            // Search for image sessions in this project using CriteriaCollection
            CriteriaCollection cc = new CriteriaCollection("AND");
            cc.addClause("xnat:imageSessionData/project", projectId);

            ArrayList sessions = XnatImagesessiondata.getXnatImagesessiondatasByField(
                "xnat:imageSessionData/project", projectId, user, false);

            logger.debug("Found {} sessions in project {}",
                        sessions != null ? sessions.size() : 0, projectId);

            if (sessions != null) {
                for (Object sessionObj : sessions) {
                    try {
                        if (sessionObj instanceof XnatImagesessiondata) {
                            XnatImagesessiondata session = (XnatImagesessiondata) sessionObj;

                            // Only include sessions with StudyInstanceUID
                            String studyUID = session.getUid();
                            if (studyUID != null && !studyUID.isEmpty()) {
                                Attributes attrs = createStudyAttributes(session);
                                results.add(attrs);
                            }
                        }
                    } catch (Exception e) {
                        logger.error("Error processing session", e);
                    }
                }
            }

            // Apply query filters if provided
            if (queryAttributes != null && !queryAttributes.isEmpty()) {
                results = filterStudyResults(results, queryAttributes);
            }

            logger.info("Study search for project {} returned {} studies", projectId, results.size());

        } catch (Exception e) {
            logger.error("Error searching studies in project: " + projectId, e);
        }

        return results;
    }

    @Override
    public List<Attributes> searchSeries(UserI user, String projectId, String studyInstanceUID, Attributes queryAttributes) {
        List<Attributes> results = new ArrayList<>();

        try {
            XnatProjectdata project = XnatProjectdata.getXnatProjectdatasById(projectId, user, false);
            if (project == null) {
                return results;
            }

            // Find the session with matching StudyInstanceUID
            XnatImagesessiondata targetSession = findSessionByUID(user, projectId, studyInstanceUID);

            if (targetSession == null) {
                logger.warn("Study not found: {}", studyInstanceUID);
                return results;
            }

            // Get all scans (series) in the session
            List scans = targetSession.getScans_scan();

            logger.debug("Found {} scans in session {}", scans.size(), targetSession.getId());

            for (Object scanObj : scans) {
                XnatImagescandata scan = (XnatImagescandata) scanObj;
                Attributes attrs = createSeriesAttributes(scan, studyInstanceUID);
                results.add(attrs);
            }

            // Apply query filters if provided
            if (queryAttributes != null && !queryAttributes.isEmpty()) {
                results = filterSeriesResults(results, queryAttributes);
            }

            logger.info("Series search for study {} returned {} series", studyInstanceUID, results.size());

        } catch (Exception e) {
            logger.error("Error searching series in study: " + studyInstanceUID, e);
        }

        return results;
    }

    @Override
    public List<Attributes> searchInstances(UserI user, String projectId, String studyInstanceUID,
                                           String seriesInstanceUID, Attributes queryAttributes) {
        List<Attributes> results = new ArrayList<>();

        try {
            XnatProjectdata project = XnatProjectdata.getXnatProjectdatasById(projectId, user, false);
            if (project == null) {
                return results;
            }

            // Find the scan by SeriesInstanceUID
            XnatImagesessiondata session = findSessionByUID(user, projectId, studyInstanceUID);
            if (session == null) {
                logger.warn("Study not found: {}", studyInstanceUID);
                return results;
            }

            XnatImagescandata targetScan = null;
            List scans = session.getScans_scan();

            for (Object scanObj : scans) {
                XnatImagescandata scan = (XnatImagescandata) scanObj;
                if (seriesInstanceUID.equals(scan.getUid())) {
                    targetScan = scan;
                    break;
                }
            }

            if (targetScan == null) {
                logger.warn("Series not found: {}", seriesInstanceUID);
                return results;
            }

            // Get DICOM files for this scan
            results = readDicomFilesFromScan(targetScan);

            // Apply query filters if provided
            if (queryAttributes != null && !queryAttributes.isEmpty()) {
                results = filterInstanceResults(results, queryAttributes);
            }

            logger.info("Instance search for series {} returned {} instances", seriesInstanceUID, results.size());

        } catch (Exception e) {
            logger.error("Error searching instances in series: " + seriesInstanceUID, e);
        }

        return results;
    }

    @Override
    public InputStream retrieveInstance(UserI user, String projectId, String studyInstanceUID,
                                       String seriesInstanceUID, String sopInstanceUID) {
        try {
            XnatProjectdata project = XnatProjectdata.getXnatProjectdatasById(projectId, user, false);
            if (project == null) {
                return null;
            }

            // Find the session and scan
            XnatImagesessiondata session = findSessionByUID(user, projectId, studyInstanceUID);
            if (session == null) {
                return null;
            }

            XnatImagescandata targetScan = null;
            List scans = session.getScans_scan();

            for (Object scanObj : scans) {
                XnatImagescandata scan = (XnatImagescandata) scanObj;
                if (seriesInstanceUID.equals(scan.getUid())) {
                    targetScan = scan;
                    break;
                }
            }

            if (targetScan == null) {
                return null;
            }

            // Find the specific DICOM file
            File dicomFile = findDicomFileInScan(targetScan, sopInstanceUID);

            if (dicomFile != null) {
                logger.info("Retrieved instance: {}", sopInstanceUID);
                return new FileInputStream(dicomFile);
            }

        } catch (Exception e) {
            logger.error("Error retrieving instance: " + sopInstanceUID, e);
        }

        return null;
    }

    @Override
    public Attributes retrieveMetadata(UserI user, String projectId, String studyInstanceUID,
                                      String seriesInstanceUID, String sopInstanceUID) {
        List<Attributes> instances = searchInstances(user, projectId, studyInstanceUID, seriesInstanceUID, null);

        for (Attributes attrs : instances) {
            if (sopInstanceUID.equals(attrs.getString(Tag.SOPInstanceUID))) {
                return attrs;
            }
        }

        return null;
    }

    @Override
    public Attributes retrieveStudyMetadata(UserI user, String projectId, String studyInstanceUID) {
        try {
            XnatProjectdata project = XnatProjectdata.getXnatProjectdatasById(projectId, user, false);
            if (project == null) {
                logger.warn("Project not found or user does not have access: {}", projectId);
                return null;
            }

            // Find the session with matching StudyInstanceUID
            XnatImagesessiondata session = findSessionByUID(user, projectId, studyInstanceUID);
            if (session == null) {
                logger.warn("Study not found: {}", studyInstanceUID);
                return null;
            }

            // Create comprehensive study-level metadata
            Attributes attrs = createEnhancedStudyAttributes(session);

            logger.info("Retrieved study metadata for study: {}", studyInstanceUID);
            return attrs;

        } catch (Exception e) {
            logger.error("Error retrieving study metadata: " + studyInstanceUID, e);
            return null;
        }
    }

    @Override
    public List<Attributes> retrieveAllStudyInstanceMetadata(UserI user, String projectId, String studyInstanceUID) {
        List<Attributes> allInstances = new ArrayList<>();

        try {
            XnatProjectdata project = XnatProjectdata.getXnatProjectdatasById(projectId, user, false);
            if (project == null) {
                logger.warn("Project not found or user does not have access: {}", projectId);
                return allInstances;
            }

            // Find the session with matching StudyInstanceUID
            XnatImagesessiondata session = findSessionByUID(user, projectId, studyInstanceUID);
            if (session == null) {
                logger.warn("Study not found: {}", studyInstanceUID);
                return allInstances;
            }

            // Get all scans (series) in the session
            List scans = session.getScans_scan();
            logger.debug("Found {} scans in study {}", scans.size(), studyInstanceUID);

            // Collect instances from all series
            for (Object scanObj : scans) {
                XnatImagescandata scan = (XnatImagescandata) scanObj;
                List<Attributes> instances = readDicomFilesFromScan(scan);
                allInstances.addAll(instances);
            }

            logger.info("Retrieved metadata for {} instances in study {}", allInstances.size(), studyInstanceUID);

        } catch (Exception e) {
            logger.error("Error retrieving all instance metadata for study: " + studyInstanceUID, e);
        }

        return allInstances;
    }

    @Override
    public List<InputStream> retrieveStudy(UserI user, String projectId, String studyInstanceUID) {
        List<InputStream> streams = new ArrayList<>();

        try {
            List<Attributes> series = searchSeries(user, projectId, studyInstanceUID, null);

            for (Attributes seriesAttrs : series) {
                String seriesUID = seriesAttrs.getString(Tag.SeriesInstanceUID);
                streams.addAll(retrieveSeries(user, projectId, studyInstanceUID, seriesUID));
            }

        } catch (Exception e) {
            logger.error("Error retrieving study: " + studyInstanceUID, e);
        }

        return streams;
    }

    @Override
    public List<InputStream> retrieveSeries(UserI user, String projectId, String studyInstanceUID, String seriesInstanceUID) {
        List<InputStream> streams = new ArrayList<>();

        try {
            List<Attributes> instances = searchInstances(user, projectId, studyInstanceUID, seriesInstanceUID, null);

            for (Attributes attrs : instances) {
                String sopUID = attrs.getString(Tag.SOPInstanceUID);
                InputStream stream = retrieveInstance(user, projectId, studyInstanceUID, seriesInstanceUID, sopUID);
                if (stream != null) {
                    streams.add(stream);
                }
            }

        } catch (Exception e) {
            logger.error("Error retrieving series: " + seriesInstanceUID, e);
        }

        return streams;
    }

    @Override
    public byte[] retrieveRenderedInstance(UserI user, String projectId, String studyInstanceUID,
                                          String seriesInstanceUID, String sopInstanceUID) {
        try {
            XnatProjectdata project = XnatProjectdata.getXnatProjectdatasById(projectId, user, false);
            if (project == null) {
                return null;
            }

            // Find the session and scan
            XnatImagesessiondata session = findSessionByUID(user, projectId, studyInstanceUID);
            if (session == null) {
                return null;
            }

            XnatImagescandata targetScan = null;
            List scans = session.getScans_scan();

            for (Object scanObj : scans) {
                XnatImagescandata scan = (XnatImagescandata) scanObj;
                if (seriesInstanceUID.equals(scan.getUid())) {
                    targetScan = scan;
                    break;
                }
            }

            if (targetScan == null) {
                return null;
            }

            // Find the specific DICOM file
            File dicomFile = findDicomFileInScan(targetScan, sopInstanceUID);

            if (dicomFile != null) {
                logger.info("Rendering instance: {}", sopInstanceUID);
                return renderDicomToJpeg(dicomFile);
            }

        } catch (Exception e) {
            logger.error("Error rendering instance: " + sopInstanceUID, e);
        }

        return null;
    }

    // Helper methods

    /**
     * Find session by StudyInstanceUID
     */
    private XnatImagesessiondata findSessionByUID(UserI user, String projectId, String studyUID) {
        try {
            // Search by UID field
            ArrayList sessions = XnatImagesessiondata.getXnatImagesessiondatasByField(
                "xnat:imageSessionData/UID", studyUID, user, false);

            if (sessions != null && !sessions.isEmpty()) {
                for (Object sessionObj : sessions) {
                    if (sessionObj instanceof XnatImagesessiondata) {
                        XnatImagesessiondata session = (XnatImagesessiondata) sessionObj;
                        // Verify it's in the correct project
                        if (projectId.equals(session.getProject())) {
                            return session;
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error finding session by UID: " + studyUID, e);
        }

        return null;
    }

    /**
     * Create study-level DICOM attributes from session
     */
    private Attributes createStudyAttributes(XnatImagesessiondata session) {
        Attributes attrs = new Attributes();

        try {
            // Required return attributes for QIDO-RS Study query
            String studyUID = session.getUid();
            if (studyUID != null && !studyUID.isEmpty()) {
                attrs.setString(Tag.StudyInstanceUID, VR.UI, studyUID);
            }

            String subjectId = session.getSubjectId();
            attrs.setString(Tag.PatientName, VR.PN, subjectId != null ? subjectId : "UNKNOWN");
            attrs.setString(Tag.PatientID, VR.LO, subjectId != null ? subjectId : "UNKNOWN");

            // Format date
            Object sessionDateObj = session.getDate();
            if (sessionDateObj != null) {
                String dateStr = sessionDateObj.toString().replaceAll("-", "");
                attrs.setString(Tag.StudyDate, VR.DA, dateStr);
            } else {
                attrs.setString(Tag.StudyDate, VR.DA, "");
            }

            String label = session.getLabel();
            attrs.setString(Tag.StudyDescription, VR.LO, label != null ? label : "");
            attrs.setString(Tag.AccessionNumber, VR.SH, label != null ? label : "");

            String id = session.getId();
            attrs.setString(Tag.StudyID, VR.SH, id != null ? id : "");

            // Add modalities in study
            List scans = session.getScans_scan();
            if (scans != null && !scans.isEmpty()) {
                List<String> modalities = new ArrayList<>();
                for (Object scanObj : scans) {
                    XnatImagescandata scan = (XnatImagescandata) scanObj;
                    String modality = scan.getModality();
                    if (modality != null && !modality.isEmpty() && !modalities.contains(modality)) {
                        modalities.add(modality);
                    }
                }
                if (!modalities.isEmpty()) {
                    attrs.setString(Tag.ModalitiesInStudy, VR.CS, String.join("\\", modalities));
                }
            }

        } catch (Exception e) {
            logger.error("Error creating study attributes", e);
        }

        return attrs;
    }

    /**
     * Create enhanced study-level DICOM attributes with comprehensive metadata
     */
    private Attributes createEnhancedStudyAttributes(XnatImagesessiondata session) {
        Attributes attrs = new Attributes();

        try {
            // Study Instance UID (required)
            String studyUID = session.getUid();
            if (studyUID != null && !studyUID.isEmpty()) {
                attrs.setString(Tag.StudyInstanceUID, VR.UI, studyUID);
            }

            // Patient identification
            String subjectId = session.getSubjectId();
            attrs.setString(Tag.PatientName, VR.PN, subjectId != null ? subjectId : "UNKNOWN");
            attrs.setString(Tag.PatientID, VR.LO, subjectId != null ? subjectId : "UNKNOWN");

            // Study date and time
            Object sessionDateObj = session.getDate();
            if (sessionDateObj != null) {
                String dateStr = sessionDateObj.toString().replaceAll("-", "");
                attrs.setString(Tag.StudyDate, VR.DA, dateStr);
            } else {
                attrs.setString(Tag.StudyDate, VR.DA, "");
            }

            // Study time (use session time if available)
            Object sessionTimeObj = session.getTime();
            if (sessionTimeObj != null) {
                String timeStr = sessionTimeObj.toString().replaceAll(":", "");
                attrs.setString(Tag.StudyTime, VR.TM, timeStr);
            } else {
                attrs.setString(Tag.StudyTime, VR.TM, "");
            }

            // Study description and identifiers
            String label = session.getLabel();
            attrs.setString(Tag.StudyDescription, VR.LO, label != null ? label : "");
            attrs.setString(Tag.AccessionNumber, VR.SH, label != null ? label : "");

            String id = session.getId();
            attrs.setString(Tag.StudyID, VR.SH, id != null ? id : "");

            // Referring physician - would need to be populated from custom fields if available
            // attrs.setString(Tag.ReferringPhysicianName, VR.PN, "");

            // Count series and instances
            List scans = session.getScans_scan();
            int numberOfSeries = 0;
            int numberOfInstances = 0;
            List<String> modalities = new ArrayList<>();

            if (scans != null && !scans.isEmpty()) {
                numberOfSeries = scans.size();

                for (Object scanObj : scans) {
                    XnatImagescandata scan = (XnatImagescandata) scanObj;

                    // Collect modalities
                    String modality = scan.getModality();
                    if (modality != null && !modality.isEmpty() && !modalities.contains(modality)) {
                        modalities.add(modality);
                    }

                    // Count instances in this series
                    List<Attributes> instances = readDicomFilesFromScan(scan);
                    numberOfInstances += instances.size();
                }
            }

            // Set modalities in study
            if (!modalities.isEmpty()) {
                attrs.setString(Tag.ModalitiesInStudy, VR.CS, String.join("\\", modalities));
            }

            // Number of series and instances
            attrs.setInt(Tag.NumberOfStudyRelatedSeries, VR.IS, numberOfSeries);
            attrs.setInt(Tag.NumberOfStudyRelatedInstances, VR.IS, numberOfInstances);

            // Institution name (use project name or scanner)
            String project = session.getProject();
            if (project != null && !project.isEmpty()) {
                attrs.setString(Tag.InstitutionName, VR.LO, project);
            }

            // Patient information (if available from subject)
            // Note: Gender and DOB would need to be populated from DICOM files or custom fields
            // if available in your XNAT instance. These could be extracted from the first DICOM
            // file in the study if needed.
            // attrs.setString(Tag.PatientSex, VR.CS, "");
            // attrs.setString(Tag.PatientBirthDate, VR.DA, "");

        } catch (Exception e) {
            logger.error("Error creating enhanced study attributes", e);
        }

        return attrs;
    }

    /**
     * Create series-level DICOM attributes from scan
     */
    private Attributes createSeriesAttributes(XnatImagescandata scan, String studyUID) {
        Attributes attrs = new Attributes();

        try {
            // Series-level attributes
            String seriesUID = scan.getUid();
            if (seriesUID != null && !seriesUID.isEmpty()) {
                attrs.setString(Tag.SeriesInstanceUID, VR.UI, seriesUID);
            }

            String modality = scan.getModality();
            attrs.setString(Tag.Modality, VR.CS, modality != null ? modality : "OT");

            String scanId = scan.getId();
            attrs.setString(Tag.SeriesNumber, VR.IS, scanId != null ? scanId : "1");

            String description = scan.getSeriesDescription();
            attrs.setString(Tag.SeriesDescription, VR.LO, description != null ? description : "");

            // Add study-level attributes
            attrs.setString(Tag.StudyInstanceUID, VR.UI, studyUID);

        } catch (Exception e) {
            logger.error("Error creating series attributes", e);
        }

        return attrs;
    }

    /**
     * Read DICOM files from scan resources
     */
    private List<Attributes> readDicomFilesFromScan(XnatImagescandata scan) {
        List<Attributes> results = new ArrayList<>();

        try {
            // Get resources/files from the scan
            List resources = scan.getFile();

            if (resources != null) {
                for (Object resourceObj : resources) {
                    if (resourceObj instanceof XnatAbstractresource) {
                        XnatAbstractresource resource = (XnatAbstractresource) resourceObj;

                        if (!isDicomResource(resource)) {
                            continue;
                        }

                        for (File dicomFile : resolveDicomFiles(resource, scan)) {
                            try (DicomInputStream dis = new DicomInputStream(dicomFile)) {
                                Attributes attrs = dis.readDataset(-1, -1);
                                results.add(attrs);
                            } catch (Exception e) {
                                logger.debug("Error reading DICOM candidate {}", dicomFile.getAbsolutePath(), e);
                            }
                        }
                    }
                }
            }

        } catch (Exception e) {
            logger.error("Error reading DICOM files from scan", e);
        }

        return results;
    }

    private List<File> resolveDicomFiles(XnatAbstractresource resource, XnatImagescandata scan) {
        Set<File> files = new LinkedHashSet<>();

        XnatImagesessiondata session = (XnatImagesessiondata) scan.getImageSessionData();

        if (resource instanceof XnatResourcecatalog && session != null) {
            try {
                CatalogUtils.CatalogData catalogData = CatalogUtils.CatalogData.getOrCreate(session, (XnatResourcecatalog) resource);
                String projectId = session.getProject();

                for (CatEntryI entry : catalogData.catBean.getEntries_entry()) {
                    File file = CatalogUtils.getFile(entry, catalogData.catPath, projectId);
                    if (isReadableFile(file)) {
                        files.add(file);
                    }
                }
            } catch (ServerException e) {
                logger.warn("Unable to resolve catalog for resource {}", resource.getXnatAbstractresourceId(), e);
            } catch (Exception e) {
                logger.warn("Unexpected error resolving catalog for resource {}", resource.getXnatAbstractresourceId(), e);
            }
        } else {
            String basePath = getResourcePath(resource, scan);
            if (basePath != null) {
                collectFiles(new File(basePath), files);
            }
        }

        return new ArrayList<>(files);
    }

    private boolean isReadableFile(File file) {
        return file != null && file.exists() && file.isFile() && file.canRead();
    }

    private void collectFiles(File root, Set<File> sink) {
        if (root == null || !root.exists()) {
            return;
        }
        if (root.isFile()) {
            if (root.canRead()) {
                sink.add(root);
            }
            return;
        }

        File[] children = root.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            collectFiles(child, sink);
        }
    }

    private boolean isDicomResource(XnatAbstractresource resource) {
        return matchesDicomDescriptor(resource.getLabel())
                || matchesDicomDescriptor(resource.getFormat())
                || matchesDicomDescriptor(resource.getContent());
    }

    private boolean matchesDicomDescriptor(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase();
        if (normalized.isEmpty()) {
            return false;
        }
        return normalized.contains("dicom") || normalized.contains("secondary");
    }

    /**
     * Find specific DICOM file by SOPInstanceUID
     */
    private File findDicomFileInScan(XnatImagescandata scan, String sopInstanceUID) {
        try {
            List resources = scan.getFile();

            if (resources != null) {
                for (Object resourceObj : resources) {
                    if (resourceObj instanceof XnatAbstractresource) {
                        XnatAbstractresource resource = (XnatAbstractresource) resourceObj;

                        if (!isDicomResource(resource)) {
                            continue;
                        }

                        for (File dicomFile : resolveDicomFiles(resource, scan)) {
                            try (DicomInputStream dis = new DicomInputStream(dicomFile)) {
                                Attributes attrs = dis.readDataset(-1, -1);
                                String fileSOPUID = attrs.getString(Tag.SOPInstanceUID);

                                if (sopInstanceUID.equals(fileSOPUID)) {
                                    return dicomFile;
                                }
                            } catch (Exception e) {
                                logger.debug("Error reading DICOM candidate {}", dicomFile.getAbsolutePath(), e);
                            }
                        }
                    }
                }
            }

        } catch (Exception e) {
            logger.error("Error finding DICOM file in scan", e);
        }

        return null;
    }

    /**
     * Get file system path for a resource
     *
     * NOTE: This uses XNAT default path conventions. You may need to adjust
     * the base archive path based on your XNAT installation.
     */
    private String getResourcePath(XnatAbstractresource resource, XnatImagescandata scan) {
        try {
            XnatImagesessiondata session = (XnatImagesessiondata) scan.getImageSessionData();
            if (session != null) {
                String archivePath = null;
                try {
                    archivePath = session.getArchivePath();
                } catch (Exception e) {
                    logger.debug("Unable to resolve archive path from session", e);
                }

                if (archivePath == null || archivePath.isEmpty()) {
                    final String baseArchive = System.getProperty("xnat.archive", "/data/xnat/archive");
                    final String projectId = session.getProject();
                    final String sessionLabel = session.getLabel();
                    archivePath = buildFallbackArchivePath(baseArchive, projectId, sessionLabel);
                }

                if (archivePath != null && !archivePath.isEmpty()) {
                    return joinPaths(archivePath, "SCANS", scan.getId(), resource.getLabel());
                }
            }
        } catch (Exception e) {
            logger.debug("Error getting resource path", e);
        }

        return null;
    }

    private String buildFallbackArchivePath(String baseArchive, String projectId, String sessionLabel) {
        if (baseArchive == null || baseArchive.isEmpty() || projectId == null || sessionLabel == null) {
            return null;
        }
        String normalizedBase = baseArchive.endsWith(File.separator)
                ? baseArchive.substring(0, baseArchive.length() - 1)
                : baseArchive;
        // Fallback to legacy arc001 assumption if archive path cannot be determined
        return normalizedBase + File.separator + projectId + File.separator + "arc001" + File.separator + sessionLabel;
    }

    private String joinPaths(String first, String... others) {
        File path = new File(first);
        for (String part : others) {
            if (part != null && !part.isEmpty()) {
                path = new File(path, part);
            }
        }
        return path.getPath();
    }

    /**
     * Render a DICOM file to JPEG format
     */
    private byte[] renderDicomToJpeg(File dicomFile) {
        try {
            // Use ImageIO with DICOM plugin to read the image
            ImageInputStream iis = ImageIO.createImageInputStream(dicomFile);
            if (iis == null) {
                logger.error("Could not create ImageInputStream for DICOM file");
                return null;
            }

            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("DICOM");
            if (!readers.hasNext()) {
                logger.error("No DICOM ImageReader found");
                iis.close();
                return null;
            }

            ImageReader reader = readers.next();
            reader.setInput(iis, false);

            DicomImageReadParam param = (DicomImageReadParam) reader.getDefaultReadParam();

            // Read the first frame (middle frame would be better but requires more logic)
            BufferedImage bufferedImage = reader.read(0, param);

            reader.dispose();
            iis.close();

            if (bufferedImage == null) {
                logger.error("Could not read image from DICOM file");
                return null;
            }

            // Convert to JPEG
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(bufferedImage, "JPEG", baos);

            logger.debug("Successfully rendered DICOM to JPEG, size: {} bytes", baos.size());

            return baos.toByteArray();

        } catch (Exception e) {
            logger.error("Error rendering DICOM to JPEG", e);
            return null;
        }
    }

    @Override
    public List<byte[]> retrieveFrames(UserI user, String projectId, String studyInstanceUID,
                                      String seriesInstanceUID, String sopInstanceUID, String frameNumbers) {
        List<byte[]> frames = new ArrayList<>();

        try {
            XnatProjectdata project = XnatProjectdata.getXnatProjectdatasById(projectId, user, false);
            if (project == null) {
                return frames;
            }

            // Find the session and scan
            XnatImagesessiondata session = findSessionByUID(user, projectId, studyInstanceUID);
            if (session == null) {
                return frames;
            }

            XnatImagescandata targetScan = null;
            List scans = session.getScans_scan();

            for (Object scanObj : scans) {
                XnatImagescandata scan = (XnatImagescandata) scanObj;
                if (seriesInstanceUID.equals(scan.getUid())) {
                    targetScan = scan;
                    break;
                }
            }

            if (targetScan == null) {
                return frames;
            }

            // Find the specific DICOM file
            File dicomFile = findDicomFileInScan(targetScan, sopInstanceUID);

            if (dicomFile == null) {
                return frames;
            }

            // Parse frame numbers
            List<Integer> frameList = parseFrameNumbers(frameNumbers);
            if (frameList.isEmpty()) {
                return frames;
            }

            // Read DICOM file and extract frames
            try (DicomInputStream dis = new DicomInputStream(dicomFile)) {
                Attributes attrs = dis.readDataset(-1, -1);

                // Check if this is a multi-frame image
                int numberOfFrames = attrs.getInt(Tag.NumberOfFrames, 1);

                logger.info("Retrieving frames {} from instance {} (total frames: {})",
                        frameNumbers, sopInstanceUID, numberOfFrames);

                // Validate requested frames
                for (Integer frameNumber : frameList) {
                    if (frameNumber < 1 || frameNumber > numberOfFrames) {
                        logger.warn("Frame number {} out of range (1-{})", frameNumber, numberOfFrames);
                        continue;
                    }

                    // Extract pixel data for the frame
                    byte[] frameData = extractFramePixelData(dicomFile, frameNumber - 1); // Convert to 0-based
                    if (frameData != null) {
                        frames.add(frameData);
                    }
                }
            }

            logger.info("Retrieved {} frame(s) from instance: {}", frames.size(), sopInstanceUID);

        } catch (Exception e) {
            logger.error("Error retrieving frames from instance: " + sopInstanceUID, e);
        }

        return frames;
    }

    /**
     * Parse comma-separated frame numbers (1-based)
     */
    private List<Integer> parseFrameNumbers(String frameNumbers) {
        List<Integer> result = new ArrayList<>();

        if (frameNumbers == null || frameNumbers.trim().isEmpty()) {
            return result;
        }

        String[] parts = frameNumbers.split(",");
        for (String part : parts) {
            try {
                int frameNum = Integer.parseInt(part.trim());
                if (frameNum > 0) {
                    result.add(frameNum);
                }
            } catch (NumberFormatException e) {
                logger.warn("Invalid frame number: {}", part);
            }
        }

        return result;
    }

    /**
     * Extract pixel data for a specific frame (0-based index)
     * Returns raw uncompressed pixel data in native format
     */
    private byte[] extractFramePixelData(File dicomFile, int frameIndex) {
        try (DicomInputStream dis = new DicomInputStream(dicomFile)) {
            Attributes attrs = dis.readDataset(-1, -1);

            // Validate frame index
            int numberOfFrames = attrs.getInt(Tag.NumberOfFrames, 1);
            if (frameIndex < 0 || frameIndex >= numberOfFrames) {
                logger.error("Frame index {} out of range (0-{})", frameIndex, numberOfFrames - 1);
                return null;
            }

            // Get pixel data
            Object pixelData = attrs.getValue(Tag.PixelData);

            if (pixelData instanceof byte[]) {
                // Uncompressed pixel data - can extract directly
                byte[] allPixels = (byte[]) pixelData;

                // Calculate frame size
                int rows = attrs.getInt(Tag.Rows, 0);
                int cols = attrs.getInt(Tag.Columns, 0);
                int samplesPerPixel = attrs.getInt(Tag.SamplesPerPixel, 1);
                int bitsAllocated = attrs.getInt(Tag.BitsAllocated, 8);
                int bytesPerSample = bitsAllocated / 8;

                int frameSize = rows * cols * samplesPerPixel * bytesPerSample;

                if (frameSize > 0 && (frameIndex * frameSize + frameSize) <= allPixels.length) {
                    byte[] frameData = new byte[frameSize];
                    System.arraycopy(allPixels, frameIndex * frameSize, frameData, 0, frameSize);
                    logger.debug("Extracted uncompressed frame {} ({} bytes)", frameIndex, frameSize);
                    return frameData;
                }
            } else {
                // Compressed or encapsulated pixel data
                // Cannot safely extract without proper decompression due to:
                // 1. Fragment 0 is Basic Offset Table (not pixel data)
                // 2. Multiple fragments may compose a single frame
                // 3. Transfer syntax specific encoding
                // Therefore, use ImageIO to decompress properly
                logger.debug("Compressed pixel data detected, using ImageIO for frame {} extraction", frameIndex);
                return extractFrameViaImageIO(dicomFile, frameIndex);
            }

            logger.error("Could not extract frame {} - invalid pixel data format", frameIndex);
            return null;

        } catch (Exception e) {
            logger.error("Error extracting frame pixel data", e);
            return null;
        }
    }

    /**
     * Fallback method to extract frame using ImageIO (decompresses and re-encodes)
     * This should only be used when direct fragment access is not possible
     */
    private byte[] extractFrameViaImageIO(File dicomFile, int frameIndex) {
        try {
            ImageInputStream iis = ImageIO.createImageInputStream(dicomFile);
            if (iis == null) {
                logger.error("Could not create ImageInputStream");
                return null;
            }

            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("DICOM");
            if (!readers.hasNext()) {
                logger.error("No DICOM ImageReader found");
                iis.close();
                return null;
            }

            ImageReader reader = readers.next();
            reader.setInput(iis, false);

            int numImages = reader.getNumImages(true);
            if (frameIndex < 0 || frameIndex >= numImages) {
                logger.error("Frame index {} out of range (0-{})", frameIndex, numImages - 1);
                reader.dispose();
                iis.close();
                return null;
            }

            // Read and decompress the frame
            DicomImageReadParam param = (DicomImageReadParam) reader.getDefaultReadParam();
            BufferedImage image = reader.read(frameIndex, param);

            reader.dispose();
            iis.close();

            if (image == null) {
                logger.error("Could not read frame {} from DICOM file", frameIndex);
                return null;
            }

            // Convert to raw uncompressed pixel data
            // Extract pixels in their native format (grayscale or RGB)
            ByteArrayOutputStream baos = new ByteArrayOutputStream();

            // Get raw pixel data from BufferedImage
            java.awt.image.DataBuffer dataBuffer = image.getRaster().getDataBuffer();

            if (dataBuffer instanceof java.awt.image.DataBufferByte) {
                byte[] pixels = ((java.awt.image.DataBufferByte) dataBuffer).getData();
                baos.write(pixels);
            } else if (dataBuffer instanceof java.awt.image.DataBufferUShort) {
                short[] pixels = ((java.awt.image.DataBufferUShort) dataBuffer).getData();
                for (short pixel : pixels) {
                    baos.write(pixel & 0xFF);
                    baos.write((pixel >> 8) & 0xFF);
                }
            } else if (dataBuffer instanceof java.awt.image.DataBufferShort) {
                short[] pixels = ((java.awt.image.DataBufferShort) dataBuffer).getData();
                for (short pixel : pixels) {
                    baos.write(pixel & 0xFF);
                    baos.write((pixel >> 8) & 0xFF);
                }
            } else {
                logger.error("Unsupported pixel data buffer type: {}", dataBuffer.getClass().getName());
                return null;
            }

            logger.debug("Extracted and decompressed frame {} via ImageIO ({} bytes)",
                        frameIndex, baos.size());
            return baos.toByteArray();

        } catch (Exception e) {
            logger.error("Error extracting frame via ImageIO", e);
            return null;
        }
    }

    /**
     * Filter study results based on query attributes
     * Implements DICOM matching rules for study-level attributes
     */
    private List<Attributes> filterStudyResults(List<Attributes> results, Attributes queryAttributes) {
        List<Attributes> filtered = new ArrayList<>();

        for (Attributes attrs : results) {
            if (matchesStudyQuery(attrs, queryAttributes)) {
                filtered.add(attrs);
            }
        }

        logger.debug("Filtered {} studies down to {} matches", results.size(), filtered.size());
        return filtered;
    }

    /**
     * Filter series results based on query attributes
     * Implements DICOM matching rules for series-level attributes
     */
    private List<Attributes> filterSeriesResults(List<Attributes> results, Attributes queryAttributes) {
        List<Attributes> filtered = new ArrayList<>();

        for (Attributes attrs : results) {
            if (matchesSeriesQuery(attrs, queryAttributes)) {
                filtered.add(attrs);
            }
        }

        logger.debug("Filtered {} series down to {} matches", results.size(), filtered.size());
        return filtered;
    }

    /**
     * Filter instance results based on query attributes
     * Implements DICOM matching rules for instance-level attributes
     */
    private List<Attributes> filterInstanceResults(List<Attributes> results, Attributes queryAttributes) {
        List<Attributes> filtered = new ArrayList<>();

        for (Attributes attrs : results) {
            if (matchesInstanceQuery(attrs, queryAttributes)) {
                filtered.add(attrs);
            }
        }

        logger.debug("Filtered {} instances down to {} matches", results.size(), filtered.size());
        return filtered;
    }

    /**
     * Check if study attributes match query criteria
     */
    private boolean matchesStudyQuery(Attributes attrs, Attributes query) {
        // PatientName matching (wildcard support)
        if (query.contains(Tag.PatientName)) {
            String queryValue = query.getString(Tag.PatientName);
            String attrValue = attrs.getString(Tag.PatientName);
            if (!matchesDicomValue(attrValue, queryValue)) {
                return false;
            }
        }

        // PatientID matching (exact or wildcard)
        if (query.contains(Tag.PatientID)) {
            String queryValue = query.getString(Tag.PatientID);
            String attrValue = attrs.getString(Tag.PatientID);
            if (!matchesDicomValue(attrValue, queryValue)) {
                return false;
            }
        }

        // StudyDate matching (range support)
        if (query.contains(Tag.StudyDate)) {
            String queryValue = query.getString(Tag.StudyDate);
            String attrValue = attrs.getString(Tag.StudyDate);
            if (!matchesDicomDate(attrValue, queryValue)) {
                return false;
            }
        }

        // StudyInstanceUID matching (exact)
        if (query.contains(Tag.StudyInstanceUID)) {
            String queryValue = query.getString(Tag.StudyInstanceUID);
            String attrValue = attrs.getString(Tag.StudyInstanceUID);
            if (!matchesDicomValue(attrValue, queryValue)) {
                return false;
            }
        }

        // AccessionNumber matching (exact or wildcard)
        if (query.contains(Tag.AccessionNumber)) {
            String queryValue = query.getString(Tag.AccessionNumber);
            String attrValue = attrs.getString(Tag.AccessionNumber);
            if (!matchesDicomValue(attrValue, queryValue)) {
                return false;
            }
        }

        // Modality matching (check ModalitiesInStudy)
        if (query.contains(Tag.Modality)) {
            String queryValue = query.getString(Tag.Modality);
            String modalitiesInStudy = attrs.getString(Tag.ModalitiesInStudy);
            if (modalitiesInStudy == null || !modalitiesInStudy.contains(queryValue)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Check if series attributes match query criteria
     */
    private boolean matchesSeriesQuery(Attributes attrs, Attributes query) {
        // Modality matching
        if (query.contains(Tag.Modality)) {
            String queryValue = query.getString(Tag.Modality);
            String attrValue = attrs.getString(Tag.Modality);
            if (!matchesDicomValue(attrValue, queryValue)) {
                return false;
            }
        }

        // SeriesDescription matching (wildcard support)
        if (query.contains(Tag.SeriesDescription)) {
            String queryValue = query.getString(Tag.SeriesDescription);
            String attrValue = attrs.getString(Tag.SeriesDescription);
            if (!matchesDicomValue(attrValue, queryValue)) {
                return false;
            }
        }

        // SeriesInstanceUID matching (exact)
        if (query.contains(Tag.SeriesInstanceUID)) {
            String queryValue = query.getString(Tag.SeriesInstanceUID);
            String attrValue = attrs.getString(Tag.SeriesInstanceUID);
            if (!matchesDicomValue(attrValue, queryValue)) {
                return false;
            }
        }

        // SeriesNumber matching (exact)
        if (query.contains(Tag.SeriesNumber)) {
            String queryValue = query.getString(Tag.SeriesNumber);
            String attrValue = attrs.getString(Tag.SeriesNumber);
            if (!matchesDicomValue(attrValue, queryValue)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Check if instance attributes match query criteria
     */
    private boolean matchesInstanceQuery(Attributes attrs, Attributes query) {
        // SOPInstanceUID matching (exact)
        if (query.contains(Tag.SOPInstanceUID)) {
            String queryValue = query.getString(Tag.SOPInstanceUID);
            String attrValue = attrs.getString(Tag.SOPInstanceUID);
            if (!matchesDicomValue(attrValue, queryValue)) {
                return false;
            }
        }

        // SOPClassUID matching (exact)
        if (query.contains(Tag.SOPClassUID)) {
            String queryValue = query.getString(Tag.SOPClassUID);
            String attrValue = attrs.getString(Tag.SOPClassUID);
            if (!matchesDicomValue(attrValue, queryValue)) {
                return false;
            }
        }

        // InstanceNumber matching (exact)
        if (query.contains(Tag.InstanceNumber)) {
            String queryValue = query.getString(Tag.InstanceNumber);
            String attrValue = attrs.getString(Tag.InstanceNumber);
            if (!matchesDicomValue(attrValue, queryValue)) {
                return false;
            }
        }

        return true;
    }

    /**
     * DICOM value matching with wildcard support (* and ?)
     * Implements DICOMweb single value matching
     */
    private boolean matchesDicomValue(String attrValue, String queryValue) {
        if (queryValue == null || queryValue.isEmpty()) {
            return true; // Empty query matches anything
        }

        if (attrValue == null) {
            return false; // No value to match against
        }

        // Handle wildcards: * (matches any sequence) and ? (matches single char)
        if (queryValue.contains("*") || queryValue.contains("?")) {
            String regex = queryValue
                    .replace(".", "\\.")
                    .replace("*", ".*")
                    .replace("?", ".");
            return attrValue.matches("(?i)" + regex); // Case-insensitive
        }

        // Exact match (case-insensitive for most DICOM attributes)
        return attrValue.equalsIgnoreCase(queryValue);
    }

    /**
     * DICOM date matching with range support
     * Supports formats: YYYYMMDD, YYYYMMDD-, -YYYYMMDD, YYYYMMDD-YYYYMMDD
     */
    private boolean matchesDicomDate(String attrValue, String queryValue) {
        if (queryValue == null || queryValue.isEmpty()) {
            return true;
        }

        if (attrValue == null || attrValue.isEmpty()) {
            return false;
        }

        // Single date match
        if (!queryValue.contains("-")) {
            return attrValue.equals(queryValue);
        }

        // Range match: startDate-endDate
        String[] parts = queryValue.split("-", -1);

        if (parts.length == 2) {
            String startDate = parts[0];
            String endDate = parts[1];

            // startDate- (from date onwards)
            if (endDate.isEmpty()) {
                return attrValue.compareTo(startDate) >= 0;
            }

            // -endDate (up to date)
            if (startDate.isEmpty()) {
                return attrValue.compareTo(endDate) <= 0;
            }

            // startDate-endDate (between dates)
            return attrValue.compareTo(startDate) >= 0 && attrValue.compareTo(endDate) <= 0;
        }

        return false;
    }

    @Override
    public StowRsResponse storeInstances(UserI user, String projectId, List<InputStream> dicomInstances) {
        List<InstanceStatus> statuses = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;
        File tempDir = null;

        try {
            // Verify project access
            XnatProjectdata project = XnatProjectdata.getXnatProjectdatasById(projectId, user, false);
            if (project == null) {
                logger.error("User {} does not have access to project: {}", user.getLogin(), projectId);
                throw new SecurityException("No access to project: " + projectId);
            }

            // Create temporary directory for upload
            tempDir = java.nio.file.Files.createTempDirectory("stow-rs-").toFile();
            logger.info("Created temp directory for STOW-RS: {}", tempDir.getAbsolutePath());

            // Process each instance
            for (InputStream stream : dicomInstances) {
                try {
                    // Read DICOM attributes
                    Attributes attrs = DicomWebUtils.readDicom(stream);
                    String sopInstanceUID = attrs.getString(Tag.SOPInstanceUID);
                    String sopClassUID = attrs.getString(Tag.SOPClassUID);
                    String studyInstanceUID = attrs.getString(Tag.StudyInstanceUID);

                    if (sopInstanceUID == null || sopClassUID == null) {
                        logger.warn("DICOM instance missing required UIDs");
                        statuses.add(new InstanceStatus(
                            sopInstanceUID, sopClassUID, false,
                            "Missing required UIDs (SOPInstanceUID or SOPClassUID)", 0xA900));
                        failureCount++;
                        continue;
                    }

                    if (studyInstanceUID == null) {
                        logger.warn("DICOM instance missing StudyInstanceUID");
                        statuses.add(new InstanceStatus(
                            sopInstanceUID, sopClassUID, false,
                            "Missing StudyInstanceUID", 0xA900));
                        failureCount++;
                        continue;
                    }

                    // Write to temp file
                    File outFile = new File(tempDir, sopInstanceUID + ".dcm");
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile);
                         org.dcm4che3.io.DicomOutputStream dos = new org.dcm4che3.io.DicomOutputStream(fos, org.dcm4che3.data.UID.ExplicitVRLittleEndian)) {
                        dos.writeDataset(null, attrs);
                    }

                    logger.info("Wrote DICOM instance {} to temp file: {}", sopInstanceUID, outFile.getName());

                    statuses.add(new InstanceStatus(sopInstanceUID, sopClassUID, true, null, 0));
                    successCount++;

                } catch (Exception e) {
                    logger.error("Error processing DICOM instance", e);
                    statuses.add(new InstanceStatus(
                        null, null, false, e.getMessage(), 0xC000));
                    failureCount++;
                }
            }

            // Trigger XNAT import if any files succeeded
            if (successCount > 0 && tempDir != null) {
                logger.info("Triggering XNAT import for {} instances in project {}", successCount, projectId);
                // Note: The actual import would be triggered here using XNAT's import services
                // For now, we'll leave the files in the temp directory for manual processing
                // In production, integrate with DicomInboxImportRequestService or GradualDicomImporter
                logger.warn("STOW-RS: Files written to {} - manual import or integration with XNAT import service required", tempDir.getAbsolutePath());
            } else if (tempDir != null) {
                // Clean up if all failed
                deleteDirectory(tempDir);
            }

        } catch (SecurityException e) {
            logger.error("Security exception during STOW-RS", e);
            throw e;
        } catch (Exception e) {
            logger.error("STOW-RS storage failed", e);
            if (tempDir != null) {
                try {
                    deleteDirectory(tempDir);
                } catch (Exception cleanupEx) {
                    logger.error("Failed to clean up temp directory: {}", tempDir.getAbsolutePath(), cleanupEx);
                }
            }
            throw new RuntimeException("Storage failed: " + e.getMessage(), e);
        }

        return new StowRsResponse(successCount, failureCount, statuses);
    }

    /**
     * Recursively delete directory and contents
     */
    private void deleteDirectory(File directory) {
        if (directory == null || !directory.exists()) {
            return;
        }

        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    if (!file.delete()) {
                        logger.warn("Failed to delete file: {}", file.getAbsolutePath());
                    }
                }
            }
        }

        if (!directory.delete()) {
            logger.warn("Failed to delete directory: {}", directory.getAbsolutePath());
        }
    }
}
