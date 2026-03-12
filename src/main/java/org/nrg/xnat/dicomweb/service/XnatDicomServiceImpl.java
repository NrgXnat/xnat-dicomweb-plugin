package org.nrg.xnat.dicomweb.service;

import com.madgag.gif.fmsware.AnimatedGifEncoder;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.dcm4che3.imageio.plugins.dcm.DicomImageReadParam;
import org.dcm4che3.io.DicomInputStream;
import org.nrg.action.ServerException;
import org.nrg.xdat.XDAT;
import org.nrg.xdat.bean.CatDcmentryBean;
import org.nrg.xdat.model.XnatAbstractresourceI;
import org.nrg.xdat.model.XnatExperimentdataShareI;
import org.nrg.xdat.model.XnatImagescandataI;
import org.nrg.xdat.model.XnatImagescandataShareI;
import org.nrg.xdat.model.XnatProjectparticipantI;
import org.nrg.xdat.om.XnatAbstractresource;
import org.nrg.xdat.om.XnatImagescandata;
import org.nrg.xdat.om.XnatImagesessiondata;
import org.nrg.xdat.om.XnatProjectdata;
import org.nrg.xdat.om.XnatResourcecatalog;
import org.nrg.xdat.om.XnatSubjectdata;
import org.nrg.xdat.security.helpers.Permissions;
import org.nrg.xft.XFTTable;
import org.nrg.xft.search.CriteriaCollection;
import org.nrg.xft.search.QueryOrganizer;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.dicomweb.config.DicomWebPreferenceBean;
import org.nrg.xnat.dicomweb.exceptions.BadRequestException;
import org.nrg.xnat.dicomweb.exceptions.DicomWebException;
import org.nrg.xnat.dicomweb.exceptions.ResourceNotFoundException;
import org.nrg.xnat.dicomweb.utils.BulkDataHandler;
import org.nrg.xnat.dicomweb.utils.DicomWebUtils;
import org.nrg.xnat.utils.CatalogUtils;
import org.nrg.xnatx.dicomweb.core.entity.DwInstance;
import org.nrg.xnatx.dicomweb.core.entity.DwSeries;
import org.nrg.xnatx.dicomweb.core.service.query.DicomwebDataService;
import org.nrg.xnatx.dicomweb.core.service.query.DwInstanceDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * XNAT 1.9.x implementation of DICOM service.
 *
 * <p>This service provides DICOMweb QIDO-RS (Query) and WADO-RS (Retrieve) functionality
 * for XNAT image sessions. It uses database-cached metadata for fast queries when available,
 * with automatic fallback to direct DICOM file reading.
 */
@Service
public class XnatDicomServiceImpl implements XnatDicomService {

  private static final Logger logger = LoggerFactory.getLogger(XnatDicomServiceImpl.class);

  private final DicomwebDataService dicomwebDataService;
  private final DwInstanceDataService dwInstanceDataService;
  private final DicomWebPreferenceBean preferences;

  // Allowed values for (0008,0056) Instance Availability; see PS 3.3 C.4.23.1
  public enum InstanceAvailability {
      // The Instances are immediately available from the Retrieve AE Title (0008,0054), and if a C-MOVE were to be
      // requested, it would succeed in a reasonably short time.
      ONLINE,

      // The Instances need to be retrieved from relatively slow media such as optical disk or tape, and if a C-MOVE
      // were to be requested from the Retrieve AE Title (0008,0054), it would succeed, but may take a considerable time.
      NEARLINE,

      // A manual intervention is needed before the Instances may be retrieved, and if a C-MOVE were to be requested
      // from the Retrieve AE Title (0008,0054), it would fail (e.g., by timeout) without such manual intervention.
      OFFLINE,

      // The Instances cannot be retrieved from the Retrieve AE Title (0008,0054), and if a C-MOVE were to be
      // requested, it would fail.
      UNAVAILABLE
  }

  /**
   * Constructs XnatDicomServiceImpl with injected services.
   *
   * @param dicomwebDataService service for querying DICOMweb data from database
   * @param dwInstanceDataService service for querying DICOM instance data
   */
  @Inject
  public XnatDicomServiceImpl(
      DicomwebDataService dicomwebDataService,
      DwInstanceDataService dwInstanceDataService,
      DicomWebPreferenceBean preferences
  ) {
    this.dicomwebDataService = dicomwebDataService;
    this.dwInstanceDataService = dwInstanceDataService;
    this.preferences = preferences;
    logger.info("XnatDicomServiceImpl initialized with database-backed metadata queries");
  }

    @Override
    public List<Attributes> searchStudies(UserI user, String projectId, Attributes queryAttributes) {
        List<Attributes> results = new ArrayList<>();
        try {
            XnatProjectdata project = XnatProjectdata.getXnatProjectdatasById(projectId, user, false);
            if (project == null) {
                logger.warn("Project not found or user does not have access: {}", projectId);
                return results;
            }

            // Check if user has unrestricted access to all data in the project
            // If user is owner, member, or collaborator, they have access to all modalities
            boolean hasUnrestrictedAccess = isUserOwnerMemberOrCollaborator(user, projectId);

            if (!hasUnrestrictedAccess) {
                logger.debug("User {} has restricted access to project {} - will filter by element permissions",
                    user.getUsername(), projectId);
            }

            // Use QueryOrganizer to efficiently query image sessions
            String rootElementName = "xnat:imageSessionData";
            QueryOrganizer qo = QueryOrganizer.buildXFTQueryOrganizerWithClause(rootElementName, user);

            // Add fields needed for DICOM study attributes
            qo.addField("xnat:imageSessionData/ID");
            qo.addField("xnat:imageSessionData/UID");
            qo.addField("xnat:imageSessionData/project");
            qo.addField("xnat:imageSessionData/label");
            qo.addField("xnat:imageSessionData/date");
            qo.addField("xnat:imageSessionData/time");
            qo.addField("xnat:imageSessionData/subject_ID");
            qo.addField("xnat:subjectData/label");
            qo.addField("xnat:imageSessionData/extension_item/element_name");

            // Add project filter
            CriteriaCollection cc = new CriteriaCollection("OR");
            cc.addClause("xnat:imageSessionData/project", projectId);
            cc.addClause("xnat:imageSessionData/sharing/share/project", projectId);
            qo.addWhere(cc);

            // Execute query
            String query = qo.buildFullQuery();
            XFTTable table = XFTTable.Execute(query, user.getDBName(), user.getUsername());

            logger.debug("Found {} sessions in project {}", table.size(), projectId);

            // Map table rows to DICOM Attributes
            if (table.size() > 0) {
                // Get column indices
                Integer idCol = table.getColumnIndex(qo.getFieldAlias("xnat:imageSessionData/ID").toLowerCase());
                Integer uidCol = table.getColumnIndex(qo.getFieldAlias("xnat:imageSessionData/UID").toLowerCase());
                Integer projectCol = table.getColumnIndex(qo.getFieldAlias("xnat:imageSessionData/project").toLowerCase());
                Integer sessionLabelCol = table.getColumnIndex(qo.getFieldAlias("xnat:imageSessionData/label").toLowerCase());
                Integer dateCol = table.getColumnIndex(qo.getFieldAlias("xnat:imageSessionData/date").toLowerCase());
                Integer timeCol = table.getColumnIndex(qo.getFieldAlias("xnat:imageSessionData/time").toLowerCase());
                Integer subjectIdCol = table.getColumnIndex(qo.getFieldAlias("xnat:imageSessionData/subject_ID").toLowerCase());
                Integer subjectLabelCol = table.getColumnIndex(qo.getFieldAlias("xnat:subjectData/label").toLowerCase());
                Integer elementNameCol = table.getColumnIndex(qo.getFieldAlias("xnat:imageSessionData/extension_item/element_name").toLowerCase());

                // Iterate through rows and create study attributes
                for (Object[] row : table.rows()) {
                    try {
                        String studyUID = (String) row[uidCol];

                        // Only include sessions with StudyInstanceUID
                        if (studyUID != null && !studyUID.isEmpty()) {
                            // Check permissions for custom groups (not owner/member/collaborator)
                            if (!hasUnrestrictedAccess) {
                                // Get element name for permission check
                                String elementName = elementNameCol != null ? (String) row[elementNameCol] : null;

                                if (elementName == null || elementName.isEmpty()) {
                                    logger.debug("Skipping session {} - no element name for permission check", studyUID);
                                    continue;
                                }

                                // Check if user can read this specific element type in the project
                                if (!Permissions.canRead(user,elementName + "/project",projectId)){
                                    logger.debug("User {} does not have permission to read {} in project {}",
                                        user.getUsername(), elementName, projectId);
                                    continue;
                                }
                            }

                            Attributes attrs = createStudyAttributesFromRow(row,
                                idCol, uidCol, projectCol, sessionLabelCol, dateCol, timeCol,
                                subjectIdCol, subjectLabelCol, elementNameCol);

                            if (attrs != null) {
                                // Augment with fields requiring session/subject data
                                String sessionId = (String) row[idCol];
                                XnatImagesessiondata session = XnatImagesessiondata
                                    .getXnatImagesessiondatasById(sessionId, user, false);
                                if (session != null) {
                                    augmentStudyAttributes(attrs, session, projectId, studyUID);
                                }
                                results.add(attrs);
                            }
                        }
                    } catch (Exception e) {
                        logger.error("Error processing session row", e);
                    }
                }
            }

            // Apply query filters if provided
            if (queryAttributes != null && !queryAttributes.isEmpty()) {
                results = filterStudyResults(results, queryAttributes);
            }

            logger.debug("Study search for project {} returned {} studies", projectId, results.size());

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
                logger.warn("Project not found or user does not have access: {}", projectId);
                return results;
            }

            // Find all sessions with matching StudyInstanceUID (XNAT allows multiple sessions with same UID)
            List<XnatImagesessiondata> targetSessions = findSessionsByUID(user, projectId, studyInstanceUID);

            if (targetSessions.isEmpty()) {
                logger.warn("Study not found: {}", studyInstanceUID);
                return results;
            }

            // Aggregate all scans (series) from all matching sessions
            int totalScans = 0;
            for (XnatImagesessiondata session : targetSessions) {
                if(!Permissions.canRead(user, session)){
                    continue;
                }

                List<XnatImagescandataI> scans = session.getScans_scan();
                totalScans += scans.size();

                for (XnatImagescandataI scan : scans) {
                    Attributes attrs = createSeriesAttributes(scan, studyInstanceUID, projectId);
                    results.add(attrs);
                }
            }

            logger.debug("Found {} scans across {} sessions for study {}",
                totalScans, targetSessions.size(), studyInstanceUID);

            // Apply query filters if provided
            if (queryAttributes != null && !queryAttributes.isEmpty()) {
                results = filterSeriesResults(results, queryAttributes);
            }

            logger.debug("Series search for study {} returned {} series", studyInstanceUID, results.size());

        } catch (Exception e) {
            logger.error("Error searching series in study: " + studyInstanceUID, e);
        }

        return results;
    }

    @Override
    public Attributes retrieveMetadata(UserI user, String projectId, String studyInstanceUID,
                                            String seriesInstanceUID, String sopInstanceUID) {
        final File dicomFile = getInstance(user, projectId, studyInstanceUID, seriesInstanceUID, sopInstanceUID);
        try (DicomInputStream dis = new DicomInputStream(dicomFile)) {
            dis.setIncludeBulkData(DicomInputStream.IncludeBulkData.URI);
            Attributes fmi = dis.readFileMetaInformation();
            Attributes attrs = dis.readDataset();
            if (fmi != null) {
                attrs.addAll(fmi);
            }
            return attrs;
        } catch (IOException e) {
            logger.error("Error reading DICOM file {}", dicomFile.getAbsolutePath(), e);
            throw new DicomWebException("Error reading instance " + sopInstanceUID, e,
                    500, "ReadError");
        }
    }

    @Override
    public List<Attributes> searchInstances(UserI user, String projectId, String studyInstanceUID,
                                           String seriesInstanceUID, Attributes queryAttributes) {
        List<Attributes> results = new ArrayList<>();

        try {
            XnatProjectdata project = XnatProjectdata.getXnatProjectdatasById(projectId, user, false);
            if (project == null) {
                logger.warn("Project not found or user does not have access: {}", projectId);
                return results;
            }

            // Find all sessions with matching StudyInstanceUID (XNAT allows multiple sessions with same UID)
            List<XnatImagesessiondata> targetSessions = findSessionsByUID(user, projectId, studyInstanceUID);

            if (targetSessions.isEmpty()) {
                logger.warn("Study not found: {}", studyInstanceUID);
                return results;
            }

            for(XnatImagesessiondata session : targetSessions){
                if(!Permissions.canRead(user,session)){
                    continue;
                }

                // Find the scan directly by SeriesInstanceUID using efficient SQL query
                XnatImagescandataI targetScan = findScanBySeriesUIDDirect(session.getScans_scan(), seriesInstanceUID);

                if (targetScan == null) {
                    continue;
                }

                // Try database cache first
                results = queryInstancesFromDatabase(targetScan).collect(Collectors.toList());

                // Fallback to reading DICOM files if database cache is empty
                if (results.isEmpty()) {
                    logger.debug("Database cache empty for series {}, falling back to file reading",
                            seriesInstanceUID);
                    results = readInstanceSearchResponseFromScanFiles(targetScan).collect(Collectors.toList());
                }

                // Insert Retrieve URL and Instance Availability, using WADO URLs
                final String prefBaseUrl = preferences.getBaseUrl();
                final String baseUrl = (null == prefBaseUrl || prefBaseUrl.isEmpty())
                        ? XDAT.getSiteConfigPreferences().getSiteUrl() : prefBaseUrl;
                results.forEach(attrs -> {
                    final String url = String.format("%s/xapi/dicomweb/projects/%s/studies/%s/series/%s/instances/%s",
                            baseUrl, projectId, session.getUid(), seriesInstanceUID, attrs.getString(Tag.SOPInstanceUID));
                    attrs.setString(Tag.RetrieveURL, VR.UR, url);
                    attrs.setString(Tag.InstanceAvailability, VR.CS, InstanceAvailability.ONLINE.name());
                });

                // Apply query filters if provided
                if (queryAttributes != null && !queryAttributes.isEmpty()) {
                    results = filterInstanceResults(results, queryAttributes);
                }
            }
            logger.debug("Instance search for series {} returned {} instances", seriesInstanceUID, results.size());
        } catch (Exception e) {
            logger.error("Error searching instances in series: " + seriesInstanceUID, e);
        }

        return results;
    }

    /**
     * Locate the DICOM file for a specific instance.
     *
     * @throws ResourceNotFoundException if the project, study, series, or instance cannot be found
     */
    private File getInstance(UserI user, String projectId, String studyInstanceUID,
                                        String seriesInstanceUID, String sopInstanceUID) {
        XnatProjectdata project = XnatProjectdata.getXnatProjectdatasById(projectId, user, false);
        if (project == null) {
            throw new ResourceNotFoundException("Project", projectId);
        }

        List<XnatImagesessiondata> targetSessions = findSessionsByUID(user, projectId, studyInstanceUID);
        if (targetSessions.isEmpty()) {
            throw new ResourceNotFoundException("Study", studyInstanceUID);
        }

        for (XnatImagesessiondata session : targetSessions) {
            try {
                if (!Permissions.canRead(user, session)) {
                    continue;
                }
            } catch (Exception e) {
                logger.error("unable to check permissions for user {} on session {}, skipping",
                        user.getLogin(), session.getId());
                continue;
            }

            final XnatImagescandataI targetScan = findScanBySeriesUIDDirect(session.getScans_scan(), seriesInstanceUID);
            if (targetScan == null) {
                continue;
            }

            File dicomFile = findDicomFileInScan(targetScan, sopInstanceUID);
            if (dicomFile != null) {
                return dicomFile;
            }
        }

        throw new ResourceNotFoundException("Instance", sopInstanceUID);
    }

    @Override
    public InputStream retrieveInstance(UserI user, String projectId, String studyInstanceUID,
                                       String seriesInstanceUID, String sopInstanceUID) throws IOException {
        File dicomFile = getInstance(user, projectId, studyInstanceUID, seriesInstanceUID, sopInstanceUID);
        logger.trace("Retrieved instance: {}", sopInstanceUID);
        return new FileInputStream(dicomFile);
    }

    @Override
    public Attributes retrieveStudyMetadata(UserI user, String projectId, String studyInstanceUID) {
        try {
            XnatProjectdata project = XnatProjectdata.getXnatProjectdatasById(projectId, user, false);
            if (project == null) {
                logger.warn("Project not found or user does not have access: {}", projectId);
                return null;
            }

            // Find all sessions with matching StudyInstanceUID (XNAT allows multiple sessions with same UID)
            List<XnatImagesessiondata> sessions = findSessionsByUID(user, projectId, studyInstanceUID);
            if (sessions.isEmpty()) {
                logger.warn("Study not found: {}", studyInstanceUID);
                return null;
            }

            // Create comprehensive study-level metadata from the first session
            // (all sessions with same UID should have same study-level metadata)
            Attributes attrs = createEnhancedStudyAttributes(projectId, sessions.get(0));

            // If multiple sessions, aggregate series and instance counts
            if (sessions.size() > 1) {
                int totalSeries = 0;
                int totalInstances = 0;
                Set<String> allModalities = new LinkedHashSet<>();

                for (XnatImagesessiondata session : sessions) {
                    List<XnatImagescandataI> scans = session.getScans_scan();
                    if (scans != null) {
                        totalSeries += scans.size();
                        for (XnatImagescandataI scan : scans) {
                            String modality = scan.getModality();
                            if (modality != null && !modality.isEmpty()) {
                                allModalities.add(modality);
                            }

                            // Try database cache first, otherwise walk the scan files.
                            final long nCached = queryInstancesFromDatabase(scan).count();
                            totalInstances += nCached > 0 ? nCached: readInstanceSearchResponseFromScanFiles(scan).count();
                        }
                    }
                }

                // Update aggregated counts
                attrs.setInt(Tag.NumberOfStudyRelatedSeries, VR.IS, totalSeries);
                attrs.setInt(Tag.NumberOfStudyRelatedInstances, VR.IS, totalInstances);
                if (!allModalities.isEmpty()) {
                    attrs.setString(Tag.ModalitiesInStudy, VR.CS, String.join("\\", allModalities));
                }
            }

            logger.debug("Retrieved study metadata for study: {} ({} sessions)", studyInstanceUID, sessions.size());
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

            // Find all sessions with matching StudyInstanceUID (XNAT allows multiple sessions with same UID)
            List<XnatImagesessiondata> sessions = findSessionsByUID(user, projectId, studyInstanceUID);
            if (sessions.isEmpty()) {
                logger.warn("Study not found: {}", studyInstanceUID);
                return allInstances;
            }

            // Collect instances from all series across all sessions
            int totalScans = 0;
            for (XnatImagesessiondata session : sessions) {
                List<XnatImagescandataI> scans = session.getScans_scan();
                totalScans += scans.size();
                scans.forEach(scan -> allInstances.addAll(readInstanceMetadataFromScanFiles(scan)));
            }

            logger.debug("Found {} scans across {} sessions for study {}", totalScans, sessions.size(), studyInstanceUID);
            logger.debug("Retrieved metadata for {} instances in study {}", allInstances.size(), studyInstanceUID);
        } catch (Exception e) {
            logger.error("Error retrieving all instance metadata for study {}", studyInstanceUID, e);
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
    public RenderedInstanceResult retrieveRenderedInstance(UserI user, String projectId, String studyInstanceUID,
                                          String seriesInstanceUID, String sopInstanceUID,
                                          Integer frameNumber, ImageFormat format) {
        return retrieveRenderedInstance(user, projectId, studyInstanceUID, seriesInstanceUID,
                sopInstanceUID, frameNumber, format, null);
    }

    @Override
    public RenderedInstanceResult retrieveRenderedInstance(UserI user, String projectId, String studyInstanceUID,
                                          String seriesInstanceUID, String sopInstanceUID,
                                          Integer frameNumber, ImageFormat format,
                                          RenderingParams params) {
        File dicomFile = getInstance(user, projectId, studyInstanceUID, seriesInstanceUID, sopInstanceUID);
        return renderInstance(dicomFile, sopInstanceUID, frameNumber, format, params);
    }

    @Override
    public RenderedInstanceResult retrieveRenderedStudy(UserI user, String projectId,
                                                         String studyInstanceUID, Integer frameNumber,
                                                         ImageFormat format, RenderingParams params) {
        File dicomFile = getRepresentativeInstance(user, projectId, studyInstanceUID, null);
        return renderInstance(dicomFile, studyInstanceUID, frameNumber, format, params);
    }

    @Override
    public RenderedInstanceResult retrieveRenderedSeries(UserI user, String projectId,
                                                          String studyInstanceUID, String seriesInstanceUID,
                                                          Integer frameNumber, ImageFormat format,
                                                          RenderingParams params) {
        File dicomFile = getRepresentativeInstance(user, projectId, studyInstanceUID, seriesInstanceUID);
        return renderInstance(dicomFile, seriesInstanceUID, frameNumber, format, params);
    }

    @Override
    public RenderedInstanceResult retrieveThumbnailStudy(UserI user, String projectId,
                                                          String studyInstanceUID, RenderingParams params,
                                                          ImageFormat format) {
        RenderingParams effective = RenderingParams.withDefaultThumbnailSize(params);
        ImageFormat effectiveFormat = format != null ? format : ImageFormat.JPEG;
        return retrieveRenderedStudy(user, projectId, studyInstanceUID, null, effectiveFormat, effective);
    }

    @Override
    public RenderedInstanceResult retrieveThumbnailSeries(UserI user, String projectId,
                                                           String studyInstanceUID, String seriesInstanceUID,
                                                           RenderingParams params, ImageFormat format) {
        RenderingParams effective = RenderingParams.withDefaultThumbnailSize(params);
        ImageFormat effectiveFormat = format != null ? format : ImageFormat.JPEG;
        return retrieveRenderedSeries(user, projectId, studyInstanceUID, seriesInstanceUID,
                null, effectiveFormat, effective);
    }

    @Override
    public RenderedInstanceResult retrieveThumbnailInstance(UserI user, String projectId,
                                                            String studyInstanceUID, String seriesInstanceUID,
                                                            String sopInstanceUID, RenderingParams params,
                                                            ImageFormat format) {
        RenderingParams effective = RenderingParams.withDefaultThumbnailSize(params);
        ImageFormat effectiveFormat = format != null ? format : ImageFormat.JPEG;
        return retrieveRenderedInstance(user, projectId, studyInstanceUID, seriesInstanceUID,
                sopInstanceUID, null, effectiveFormat, effective);
    }

    @Override
    public RenderedInstanceResult retrieveThumbnailFrame(UserI user, String projectId,
                                                          String studyInstanceUID, String seriesInstanceUID,
                                                          String sopInstanceUID, String frameList,
                                                          RenderingParams params, ImageFormat format) {
        RenderingParams effective = RenderingParams.withDefaultThumbnailSize(params);
        ImageFormat effectiveFormat = format != null ? format : ImageFormat.JPEG;
        // Use the first frame number from the list
        Integer frameNumber = null;
        if (frameList != null && !frameList.isEmpty()) {
            try {
                frameNumber = Integer.parseInt(frameList.split(",")[0].trim());
            } catch (NumberFormatException e) {
                throw new BadRequestException("frameList", "invalid frame number");
            }
        }
        return retrieveRenderedInstance(user, projectId, studyInstanceUID, seriesInstanceUID,
                sopInstanceUID, frameNumber, effectiveFormat, effective);
    }

    /**
     * Render a DICOM instance to the requested image format.
     * Dispatches to the appropriate per-format rendering method.
     *
     * @param dicomFile the DICOM file to render
     * @param identifier instance identifier for logging and error messages
     * @param frameNumber requested frame number (1-based), or null for default
     * @param format output image format (JPEG, PNG, or GIF)
     * @param params rendering parameters (viewport, window, quality), may be null
     * @return rendered image result, or null if rendering failed
     */
    private RenderedInstanceResult renderInstance(File dicomFile, String identifier,
                                                   Integer frameNumber, ImageFormat format,
                                                   RenderingParams params) {
        logger.debug("Rendering instance: {} (frame: {}, format: {})", identifier,
                frameNumber != null ? frameNumber : "default", format);

        try {
            switch (format) {
                case GIF:
                    return renderDicomToGif(dicomFile, frameNumber, params);
                case PNG:
                    return renderDicomToPng(dicomFile, frameNumber, params);
                case JPEG:
                default:
                    return renderDicomToJpeg(dicomFile, frameNumber, params);
            }
        } catch (UnsupportedOperationException e) {
            throw e;
        } catch (Throwable e) {
            if (e instanceof UnsatisfiedLinkError || e instanceof NoClassDefFoundError) {
                logger.error("Failed to render instance due to missing native libraries: {}", e.getMessage());
                throw new UnsupportedOperationException(
                        "Cannot render image. This DICOM file uses compression formats (JPEG-LS or JPEG 2000) " +
                        "that require OpenCV native libraries. " +
                        "Install OpenCV (macOS: 'brew install opencv', Ubuntu: 'apt-get install libopencv-dev') " +
                        "or use the retrieveInstance endpoint to download the original DICOM file.");
            }
            throw new DicomWebException("Error rendering instance " + identifier, e,
                    500, "RenderError");
        }
    }

    /**
     * Select a representative DICOM file for study or series level rendering.
     * Picks the middle instance of the specified (or first) series.
     *
     * @param user the authenticated user
     * @param projectId XNAT project identifier
     * @param studyUID Study Instance UID
     * @param seriesUID Series Instance UID, or null to use the first series in the study
     * @return the DICOM file for the representative instance
     * @throws ResourceNotFoundException if the study or series contains no instances
     */
    private File getRepresentativeInstance(UserI user, String projectId,
                                            String studyUID, String seriesUID) {
        String effectiveSeriesUID = seriesUID;

        if (effectiveSeriesUID == null) {
            List<Attributes> seriesList = searchSeries(user, projectId, studyUID, null);
            if (seriesList.isEmpty()) {
                throw new ResourceNotFoundException("Study", studyUID);
            }
            effectiveSeriesUID = seriesList.get(0).getString(Tag.SeriesInstanceUID);
        }

        List<Attributes> instances = searchInstances(user, projectId, studyUID, effectiveSeriesUID, null);
        if (instances.isEmpty()) {
            throw new ResourceNotFoundException("Series", effectiveSeriesUID);
        }

        int midIndex = instances.size() / 2;
        String sopUID = instances.get(midIndex).getString(Tag.SOPInstanceUID);
        return getInstance(user, projectId, studyUID, effectiveSeriesUID, sopUID);
    }

    // Helper methods

    /**
     * Find all sessions with the given StudyInstanceUID in the project
     * XNAT allows multiple image sessions in the same project to have the same UID
     * Returns all matching sessions to treat them as a single DICOM study
     */
    private List<XnatImagesessiondata> findSessionsByUID(UserI user, String projectId, String studyUID) {
        List<XnatImagesessiondata> matchingSessions = new ArrayList<>();

        try {
            // Search by UID field
            ArrayList sessions = XnatImagesessiondata.getXnatImagesessiondatasByField(
                "xnat:imageSessionData/UID", studyUID, user, false);

            if (sessions != null && !sessions.isEmpty()) {
                for (Object sessionObj : sessions) {
                    if (sessionObj instanceof XnatImagesessiondata) {
                        XnatImagesessiondata session = (XnatImagesessiondata) sessionObj;
                        if(!Permissions.canRead(user,session)){
                            continue;
                        }

                        // Verify it's in the correct project
                        if (projectId.equals(session.getProject())) {
                            matchingSessions.add(session);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error finding sessions by UID: " + studyUID, e);
        }

        return matchingSessions;
    }

    /**
     * Find scan by SeriesInstanceUID using direct SQL query
     * This is more efficient than loading entire sessions to find a scan
     * @param user The user making the request
     * @param seriesInstanceUID The SeriesInstanceUID to search for
     * @return The XnatImagescandata object, or null if not found
     */
    private List<XnatImagescandata> findScanIdsBySeriesUIDDirect(UserI user, String projectId, String seriesInstanceUID) {
        final List<XnatImagescandata> matchingByProjectUID = new ArrayList();
        try {
            // Use XFT to query for the scan by UID
            final List<XnatImagescandata> scans = XnatImagescandata.getXnatImagescandatasByField(
                "xnat:imageScanData/UID", seriesInstanceUID, user, false);

            if (scans != null && !scans.isEmpty()) {
                // Return the first matching scan
                final XnatImagescandata scan = scans.get(0);
                if(StringUtils.equals(projectId, scan.getProject())){
                    matchingByProjectUID.add(scan);
                }else{
                    for(XnatImagescandataShareI share: scan.getSharing_share()){
                        if(StringUtils.equals(share.getProject(),projectId)){
                            matchingByProjectUID.add(scan);
                        }
                    }
                }
            }

            if(!matchingByProjectUID.isEmpty()){
                return matchingByProjectUID;
            }

            // If not found by UID field, might need to check DICOM files
            // This is a fallback for scans where UID is not set in XNAT
            logger.debug("Scan not found by UID field for SeriesInstanceUID: {}", seriesInstanceUID);

        } catch (Exception e) {
            logger.error("Error finding scan by SeriesInstanceUID: " + seriesInstanceUID, e);
        }

        return null;
    }

    /**
     * Check if user has unrestricted access to project data
     * Returns true if user is an owner, member, or collaborator
     * Returns false if user is in a custom group (may have restricted access by modality)
     */
    private boolean isUserOwnerMemberOrCollaborator(UserI user, String projectId) {
        try {
            String username = user.getUsername();

            // Check if user is owner
            if (Permissions.isProjectOwner(user,projectId)) {
                logger.debug("User {} is owner of project {}", username, projectId);
                return true;
            }

            // Check if user is member
            if (Permissions.isProjectMember(user,projectId)) {
                logger.debug("User {} is member of project {}", username, projectId);
                return true;
            }

            // Check if user is collaborator
            if (Permissions.isProjectCollaborator(user,projectId)) {
                logger.debug("User {} is collaborator of project {}", username, projectId);
                return true;
            }

            // User is in a custom group - may have restricted access
            logger.debug("User {} is in custom group for project {} - has restricted access", username, projectId);
            return false;

        } catch (Exception e) {
            logger.warn("Error checking user group membership for project {}: {}", projectId, e.getMessage());
            // Default to restricted access if we can't determine group
            return false;
        }
    }

    /**
     * Create study-level DICOM attributes from session
     */
    private Attributes createStudyAttributes(String projectId, XnatImagesessiondata session) {
        Attributes attrs = new Attributes();

        try {
            // Required return attributes for QIDO-RS Study query
            String studyUID = session.getUid();
            if (studyUID != null && !studyUID.isEmpty()) {
                attrs.setString(Tag.StudyInstanceUID, VR.UI, studyUID);
            }

            // Use helper methods to get DICOM patient information from original DICOM data
            attrs.setString(Tag.PatientName, VR.PN, getPatientName(projectId, session));
            attrs.setString(Tag.PatientID, VR.LO, getPatientID(projectId, session));

            // Format date
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

            String label = session.getLabel();
            attrs.setString(Tag.StudyDescription, VR.LO, label != null ? label : "");

            // Use helper method to get AccessionNumber from original DICOM data
            attrs.setString(Tag.AccessionNumber, VR.SH, getAccessionNumber(session));

            String id = session.getId();
            attrs.setString(Tag.StudyID, VR.SH, id != null ? id : "");

            // Add modalities in study
            final List<XnatImagescandataI> scans = session.getScans_scan();
            if (scans != null && !scans.isEmpty()) {
                List<String> modalities = new ArrayList<>();
                for (XnatImagescandataI scan : scans) {
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
     * Create study-level DICOM attributes from query table row
     * Maps XFTTable row data to DICOM Attributes efficiently without loading full objects
     */
    private Attributes createStudyAttributesFromRow(Object[] row,
                                                   Integer idCol, Integer uidCol, Integer projectCol,
                                                   Integer sessionLabelCol, Integer dateCol, Integer timeCol,
                                                   Integer subjectIdCol, Integer subjectLabelCol,
                                                   Integer elementNameCol) {
        Attributes attrs = new Attributes();

        try {
            // StudyInstanceUID (required)
            String studyUID = (String) row[uidCol];
            if (studyUID != null && !studyUID.isEmpty()) {
                attrs.setString(Tag.StudyInstanceUID, VR.UI, studyUID);
            }

            // Patient Name = xnat:imageSessionData/label
            String patientName = sessionLabelCol != null ? (String) row[sessionLabelCol] : null;
            attrs.setString(Tag.PatientName, VR.PN, patientName != null ? patientName : "UNKNOWN");

            // Patient ID = xnat:subjectData/label
            String patientID = subjectLabelCol != null ? (String) row[subjectLabelCol] : null;
            attrs.setString(Tag.PatientID, VR.LO, patientID != null ? patientID : "UNKNOWN");

            // Study Date
            if (dateCol != null && row[dateCol] != null) {
                String dateStr = row[dateCol].toString().replaceAll("-", "");
                attrs.setString(Tag.StudyDate, VR.DA, dateStr);
            } else {
                attrs.setString(Tag.StudyDate, VR.DA, "");
            }

            // Study Time
            if (timeCol != null && row[timeCol] != null) {
                String timeStr = row[timeCol].toString().replaceAll(":", "");
                attrs.setString(Tag.StudyTime, VR.TM, timeStr);
            } else {
                attrs.setString(Tag.StudyTime, VR.TM, "");
            }

            // Study Description = xnat:imageSessionData/project
            String studyDescription = projectCol != null ? (String) row[projectCol] : null;
            attrs.setString(Tag.StudyDescription, VR.LO, studyDescription != null ? studyDescription : "");

            // Accession Number = xnat:imageSessionData/ID
            String accessionNumber = idCol != null ? (String) row[idCol] : null;
            attrs.setString(Tag.AccessionNumber, VR.SH, accessionNumber != null ? accessionNumber : "");

            // Study ID
            String id = idCol != null ? (String) row[idCol] : null;
            attrs.setString(Tag.StudyID, VR.SH, id != null ? id : "");

            // Modalities in Study - derive from xnat:imageSessionData/extension_item/element_name
            String elementName = elementNameCol != null ? (String) row[elementNameCol] : null;
            if (elementName != null && !elementName.isEmpty()) {
                String modality = deriveModalityFromElementName(elementName);
                if (modality != null) {
                    attrs.setString(Tag.ModalitiesInStudy, VR.CS, modality);
                }
            }

        } catch (Exception e) {
            logger.error("Error creating study attributes from row", e);
            return null;
        }

        return attrs;
    }

    /**
     * Augment study attributes with fields that require loading the session and subject objects.
     * Adds NumberOfStudyRelatedSeries/Instances, RetrieveURL, PatientSex, PatientBirthDate,
     * and ReferringPhysicianName per PS3.18 Table 10.6.3-3.
     */
    private void augmentStudyAttributes(Attributes attrs, XnatImagesessiondata session,
                                        String projectId, String studyUID) {
        try {
            // NumberOfStudyRelatedSeries and NumberOfStudyRelatedInstances
            List<XnatImagescandataI> scans = session.getScans_scan();
            int numberOfSeries = scans != null ? scans.size() : 0;
            int numberOfInstances = 0;
            if (scans != null) {
                for (XnatImagescandataI scan : scans) {
                    int fc = getFileCount(scan);
                    if (fc >= 0) {
                        numberOfInstances += fc;
                    }
                }
            }
            attrs.setInt(Tag.NumberOfStudyRelatedSeries, VR.IS, numberOfSeries);
            attrs.setInt(Tag.NumberOfStudyRelatedInstances, VR.IS, numberOfInstances);

            // RetrieveURL
            final String prefBaseUrl = preferences.getBaseUrl();
            final String baseUrl = (null == prefBaseUrl || prefBaseUrl.isEmpty())
                    ? XDAT.getSiteConfigPreferences().getSiteUrl() : prefBaseUrl;
            String retrieveUrl = String.format("%s/xapi/dicomweb/projects/%s/studies/%s",
                    baseUrl, projectId, studyUID);
            attrs.setString(Tag.RetrieveURL, VR.UR, retrieveUrl);

            // Patient demographics from subject
            XnatSubjectdata subject = session.getSubjectData();
            if (subject != null) {
                // PatientSex
                String gender = subject.getGender();
                String patientSex;
                if ("m".equalsIgnoreCase(gender)) {
                    patientSex = "M";
                } else if ("f".equalsIgnoreCase(gender)) {
                    patientSex = "F";
                } else {
                    patientSex = "O";
                }
                attrs.setString(Tag.PatientSex, VR.CS, patientSex);

                // PatientBirthDate
                java.util.Date dob = subject.getDOB();
                if (dob != null) {
                    SimpleDateFormat df = new SimpleDateFormat("yyyyMMdd");
                    attrs.setString(Tag.PatientBirthDate, VR.DA, df.format(dob));
                }
            } else {
                attrs.setString(Tag.PatientSex, VR.CS, "O");
            }

            // ReferringPhysicianName — not available in XNAT, set empty (PS3.18 requires tag presence)
            attrs.setString(Tag.ReferringPhysicianName, VR.PN, "");
        } catch (Exception e) {
            logger.error("Error augmenting study attributes for session {}", session.getId(), e);
        }
    }

    /**
     * Derive DICOM modality from XNAT element name
     * Maps element names like "xnat:mrSessionData" to "MR", "xnat:ctSessionData" to "CT", etc.
     */
    private String deriveModalityFromElementName(String elementName) {
        if (elementName == null || elementName.isEmpty()) {
            return null;
        }

        // Remove namespace prefix if present
        String name = elementName.contains(":") ? elementName.substring(elementName.indexOf(":") + 1) : elementName;

        // Convert to uppercase and remove "SessionData" or "ImageSessionData" suffix
        name = name.toUpperCase()
                   .replace("SESSIONDATA", "")
                   .replace("IMAGESESSIONDATA", "");

        // Map common XNAT session types to DICOM modalities
        switch (name) {
            case "MR":
                return "MR";
            case "CT":
                return "CT";
            case "PET":
                return "PT";
            case "US":
                return "US";
            case "CR":
                return "CR";
            case "DX":
                return "DX";
            case "MG":
                return "MG";
            case "NM":
                return "NM";
            case "XA":
                return "XA";
            case "RF":
                return "RF";
            case "OT":
                return "OT";
            default:
                // If we can't map it, return the cleaned name (max 16 chars for DICOM CS)
                return name.length() > 16 ? name.substring(0, 16) : name;
        }
    }

    /**
     * Create enhanced study-level DICOM attributes with comprehensive metadata
     */
    private Attributes createEnhancedStudyAttributes(String projectId, XnatImagesessiondata session) {
        Attributes attrs = new Attributes();

        try {
            // Study Instance UID (required)
            String studyUID = session.getUid();
            if (studyUID != null && !studyUID.isEmpty()) {
                attrs.setString(Tag.StudyInstanceUID, VR.UI, studyUID);
            }

            // Patient identification - use helper methods to get DICOM patient information
            attrs.setString(Tag.PatientName, VR.PN, getPatientName(projectId, session));
            attrs.setString(Tag.PatientID, VR.LO, getPatientID(projectId, session));

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

            // Use helper method to get AccessionNumber from original DICOM data
            attrs.setString(Tag.AccessionNumber, VR.SH, getAccessionNumber(session));

            String id = session.getId();
            attrs.setString(Tag.StudyID, VR.SH, id != null ? id : "");

            // Referring physician - would need to be populated from custom fields if available
            // attrs.setString(Tag.ReferringPhysicianName, VR.PN, "");

            // Count series and instances
            List<XnatImagescandataI> scans = session.getScans_scan();
            int numberOfSeries = 0;
            int numberOfInstances = 0;
            List<String> modalities = new ArrayList<>();

            if (scans != null && !scans.isEmpty()) {
                numberOfSeries = scans.size();

                for (XnatImagescandataI scan : scans) {
                    // Collect modalities
                    String modality = scan.getModality();
                    if (modality != null && !modality.isEmpty() && !modalities.contains(modality)) {
                        modalities.add(modality);
                    }

                    // Count instances in this series
                    numberOfInstances += getFileCount(scan);
                }
            }

            // Set modalities in study
            if (!modalities.isEmpty()) {
                attrs.setString(Tag.ModalitiesInStudy, VR.CS, String.join("\\", modalities));
            }

            // Number of series and instances
            attrs.setInt(Tag.NumberOfStudyRelatedSeries, VR.IS, numberOfSeries);

            //TODO: calculate the numberOfInstances b
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

    private String getPatientID(String projectId, XnatImagesessiondata session) {
        XnatSubjectdata subj = session.getSubjectData();
        if(StringUtils.equals(subj.getProject(),projectId)){
            return session.getLabel();
        }else{
            for(XnatProjectparticipantI share : subj.getSharing_share()){
                if(StringUtils.equals(share.getProject(),projectId)){
                    return share.getLabel();
                }
            }
        }

        return subj.getLabel();
    }

    private String getAccessionNumber(XnatImagesessiondata session) {
        return session.getId();
    }

    private String getPatientName(String projectId, XnatImagesessiondata session) {
        if(StringUtils.equals(session.getProject(),projectId)){
            return session.getLabel();
        }else{
            for(XnatExperimentdataShareI share : session.getSharing_share()){
                if(StringUtils.equals(share.getProject(),projectId)){
                    return share.getLabel();
                }
            }
        }

        return session.getLabel();
    }

    /**
     * Create series-level DICOM attributes from scan.
     * Populates return keys per PS3.18 Table 10.6.3-4.
     */
    private Attributes createSeriesAttributes(XnatImagescandataI scan, String studyUID, String projectId) {
        Attributes attrs = new Attributes();

        try {
            // Series-level attributes (Required)
            String seriesUID = scan.getUid();
            if (seriesUID != null && !seriesUID.isEmpty()) {
                attrs.setString(Tag.SeriesInstanceUID, VR.UI, seriesUID);
            }

            String modality = scan.getModality();
            attrs.setString(Tag.Modality, VR.CS, modality != null ? modality : "OT");

            String scanId = scan.getId();
            attrs.setString(Tag.SeriesNumber, VR.IS, scanId != null ? scanId : "1");

            // NumberOfSeriesRelatedInstances (Required per PS3.18 Table 10.6.3-4)
            int fileCount = getFileCount(scan);
            if (fileCount >= 0) {
                attrs.setInt(Tag.NumberOfSeriesRelatedInstances, VR.IS, fileCount);
            }

            // RetrieveURL (Required if retrievable, per PS3.18 Table 10.6.3-4)
            if (seriesUID != null && !seriesUID.isEmpty()) {
                final String prefBaseUrl = preferences.getBaseUrl();
                final String baseUrl = (null == prefBaseUrl || prefBaseUrl.isEmpty())
                        ? XDAT.getSiteConfigPreferences().getSiteUrl() : prefBaseUrl;
                String retrieveUrl = String.format("%s/xapi/dicomweb/projects/%s/studies/%s/series/%s",
                        baseUrl, projectId, studyUID, seriesUID);
                attrs.setString(Tag.RetrieveURL, VR.UR, retrieveUrl);
            }

            // Conditional attributes (Type C — present if known)
            String description = scan.getSeriesDescription();
            attrs.setString(Tag.SeriesDescription, VR.LO, description != null ? description : "");

            // PerformedProcedureStepStartDate (Type C per PS3.18 Table 10.6.3-4)
            Object startDate = scan.getStartDate();
            if (startDate != null) {
                String dateStr = startDate.toString().replaceAll("-", "");
                if (!dateStr.isEmpty()) {
                    attrs.setString(Tag.PerformedProcedureStepStartDate, VR.DA, dateStr);
                }
            }

            // PerformedProcedureStepStartTime (Type C per PS3.18 Table 10.6.3-4)
            Object startTime = scan.getStarttime();
            if (startTime != null) {
                String timeStr = startTime.toString().replaceAll(":", "");
                if (!timeStr.isEmpty()) {
                    attrs.setString(Tag.PerformedProcedureStepStartTime, VR.TM, timeStr);
                }
            }

            // Add study-level attributes
            attrs.setString(Tag.StudyInstanceUID, VR.UI, studyUID);

        } catch (Exception e) {
            logger.error("Error creating series attributes", e);
        }

        return attrs;
    }

    private Integer getFileCount(XnatImagescandataI scan) {
        return scan.getFile().stream()
                .filter(XnatResourcecatalog.class::isInstance)
                .filter(resource -> "DICOM".equals(resource.getLabel()))
                .map(XnatAbstractresourceI::getFileCount)
                .findAny()
                .orElse(-1);
    }

    /**
     * Queries DICOM instance metadata from database cache.
     *
     * <p>This method provides significantly faster metadata retrieval than reading files directly
     * from disk. It queries the database cache populated by the DICOMweb event listener.
     *
     * @param scan the XNAT scan to query instances for
     * @return stream of DICOM Attributes from database, or empty list if not found or on error
     */
    private Stream<Attributes> queryInstancesFromDatabase(final XnatImagescandataI scan) {
        final String seriesUid = scan.getUid();
        if (seriesUid == null || seriesUid.isEmpty()) {
            logger.debug("Scan {} has no series UID, cannot query database", scan.getId());
            return Stream.of();
        }
        try {
            // Query series by UID
            final DwSeries series = dicomwebDataService.getSeriesByProperty(
                    "seriesInstanceUid", seriesUid, true
            );
            if (series == null) {
                logger.debug("Series {} not found in database cache", seriesUid);
                return Stream.of();
            }

            // Get all instances for this series using query-by-example
            final DwInstance exampleInstance = new DwInstance();
            exampleInstance.setSeries(series);

            return dwInstanceDataService.getAll(exampleInstance, true).stream()
                    .flatMap(inst -> {
                        try {
                            return Optional.ofNullable(inst.getMetadata())
                                    .map(Stream::of)
                                    .orElse(Stream.of());
                        } catch (IOException e) {
                            logger.warn("Error deserializing metadata for instance {}: {}",
                                    inst.getSopInstanceUid(),
                                    e.getMessage());
                            return Stream.of();
                        }
                    });
        } catch (Exception e) {
            logger.warn("Error querying instances from database for scan {}: {}",
                    scan.getId(), e.getMessage(), e);
            return Stream.of();
        }
    }

    /**
     * Stream the DICOM resources for the provided scan
     * @param scan scan that might represent a DICOM series
     * @return DICOM resources for the scan
     */
    private Stream<XnatAbstractresource> dicomResourceStream(final XnatImagescandataI scan) {
        return Optional.ofNullable(scan.getFile())
                .map(List::stream)
                .orElse(Stream.of())    // Java 9: .stream().flatMap(List::stream)
                .filter(XnatAbstractresource.class::isInstance)
                .map(XnatAbstractresource.class::cast)
                .filter(XnatDicomServiceImpl::isDicomResource);
    }

    /**
     * Get full attributes with bulk data URIs for each DICOM instance in the provided scan.
     * @param scan XNAT scan representing a DICOM series
     * @return List of instances
     */
    private List<Attributes> readInstanceMetadataFromScanFiles(final XnatImagescandataI scan) {
        return dicomResourceStream(scan)
                .flatMap(resource -> resolveDicomFiles(resource, scan, null))
                .flatMap(file -> {
                    try (DicomInputStream dis = new DicomInputStream(file)) {
                        dis.setIncludeBulkData(DicomInputStream.IncludeBulkData.URI);
                        final Optional<Attributes> fmi = Optional.ofNullable(dis.readFileMetaInformation());
                        final Attributes attrs = dis.readDataset();
                        fmi.ifPresent(attrs::addAll);
                        return Stream.of(attrs);
                    } catch (IOException e) {
                        logger.debug("Error reading DICOM candidate {}", file.getAbsolutePath(), e);
                        return Stream.of();
                    }
                })
                .collect(Collectors.toList());
    }

    /**
     * Read DICOM files from scan resources (fallback when database cache unavailable)
     */
    private Stream<Attributes> readInstanceSearchResponseFromScanFiles(final XnatImagescandataI scan) {
        // TODO: there's a reasonable argument that we should be building and storing DwInstances
        //       here, as this is a scan that didn't have metadata in the database.
        return dicomResourceStream(scan)
                .flatMap(resource -> resolveDicomFiles(resource, scan, null))
                .flatMap(file -> {
                    try (DicomInputStream dis = new DicomInputStream(file)) {
                        // run Attributes through a DwInstance to standardize which attributes we return
                        dis.setIncludeBulkData(DicomInputStream.IncludeBulkData.NO);
                        final DwInstance inst = new DwInstance();
                        inst.setData(dis.readDataset());
                        final Attributes attrs = inst.getMetadata();
                        return Stream.of(attrs);
                    } catch (IOException e) {
                        logger.debug("Error reading DICOM candidate {}", file.getAbsolutePath(), e);
                        return Stream.of();
                    }
                });
    }

    private Stream<File> resolveDicomFiles(final XnatAbstractresource resource, final XnatImagescandataI scan, final String fileSOPUID) {
        final XnatImagesessiondata session = ((XnatImagescandata) scan).getImageSessionData();
        if (resource instanceof XnatResourcecatalog && session != null) {
            try {
                final CatalogUtils.CatalogData catalogData = CatalogUtils.CatalogData.getOrCreate(session, (XnatResourcecatalog) resource);
                return catalogData.catBean.getEntries_entry().stream()
                        .filter(CatDcmentryBean.class::isInstance)
                        .map(CatDcmentryBean.class::cast)
                        .filter(e -> null == fileSOPUID || fileSOPUID.equals(e.getUid()))
                        .map(e -> CatalogUtils.getFile(e, catalogData.catPath, session.getProject()));
            } catch (ServerException e) {
                logger.error("Unable to resolve catalog for resource {}", resource.getXnatAbstractresourceId(), e);
            } catch (Exception e) {
                logger.error("Unexpected error resolving catalog for resource {}", resource.getXnatAbstractresourceId(), e);
            }
        }
        return Stream.of();
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

    private static boolean isDicomResource(XnatAbstractresource resource) {
        return matchesDicomDescriptor(resource.getLabel())
                || matchesDicomDescriptor(resource.getFormat())
                || matchesDicomDescriptor(resource.getContent());
    }

    private static boolean matchesDicomDescriptor(String value) {
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
     * Find scan by SeriesInstanceUID.
     * First tries to match scan.getUid(), then falls back to reading DICOM files.
     */
    private XnatImagescandata findScanBySeriesUIDDirect(List<XnatImagescandataI> scans, String seriesInstanceUID) {
        // First pass: try to match scan.getUid()
        for (XnatImagescandataI scan : scans) {
            if (scan instanceof XnatImagescandata && seriesInstanceUID.equals(scan.getUid())) {
                return (XnatImagescandata) scan;
            }
        }

        return null;
    }

    /**
     * Find specific DICOM file by SOPInstanceUID
     */
    private File findDicomFileInScan(XnatImagescandataI scan, String sopInstanceUID) {
        try {
            return dicomResourceStream(scan)
                    .flatMap(resource -> resolveDicomFiles(resource, scan, sopInstanceUID))
                    .findAny()
                    .orElse(null);
        } catch (Exception e) {
            logger.error("Error finding DICOM file in scan", e);
            return null;
        }
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
                    // Use XNAT's configured archive path instead of system property
                    final String baseArchive = XDAT.getSiteConfigPreferences().getArchivePath();
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
     * Get the dcm4che3 ImageReader for DICOM. This is a little complicated because we
     * run in a context where there's also a dcm4che2 ImageReader.
     * @return dcm4che3 ImageReader, or null if one can't be found
     */
    private ImageReader getDicomImageReader() {
        final Iterable<ImageReader> readers = () -> ImageIO.getImageReadersByFormatName("DICOM");
        return StreamSupport.stream(readers.spliterator(), false)
                .filter(reader -> reader.getClass().getName().startsWith("org.dcm4che3"))
                .findAny()
                .orElse(null);
    }

    /**
     * Intermediate result from reading and transforming a DICOM frame,
     * before encoding to a specific image format.
     */
    private static class DecodedFrame {
        final BufferedImage image;
        final int totalFrames;
        final int frameIndex; // 0-based
        final Double frameRate;

        DecodedFrame(BufferedImage image, int totalFrames, int frameIndex, Double frameRate) {
            this.image = image;
            this.totalFrames = totalFrames;
            this.frameIndex = frameIndex;
            this.frameRate = frameRate;
        }
    }

    /**
     * Read a DICOM file and decode a single frame, applying window and viewport transforms.
     * This is the shared pipeline for JPEG and PNG rendering.
     *
     * @param dicomFile DICOM file to read
     * @param requestedFrame requested frame number (1-based), null for default (middle frame)
     * @param params rendering parameters (window, viewport)
     * @return decoded frame with metadata, or null if the image could not be read
     */
    private DecodedFrame decodeDicomFrame(File dicomFile, Integer requestedFrame,
                                          RenderingParams params) throws Exception {
        // Read DICOM metadata to determine frame count, frame rate, and transfer syntax
        int totalFrames = 1;
        Double frameRate = null;
        String transferSyntax = null;

        try (DicomInputStream dis = new DicomInputStream(dicomFile)) {
            transferSyntax = dis.getTransferSyntax();
            Attributes attrs = dis.readDataset(-1, -1);
            totalFrames = attrs.getInt(Tag.NumberOfFrames, 1);
            frameRate = extractFrameRate(attrs);
        }

        // Determine which frame to render (0-based index)
        int frameIndex;
        if (requestedFrame != null) {
            frameIndex = requestedFrame - 1;
            if (frameIndex < 0 || frameIndex >= totalFrames) {
                logger.warn("Requested frame {} out of range [1-{}], using middle frame",
                        requestedFrame, totalFrames);
                frameIndex = totalFrames / 2;
            }
        } else {
            frameIndex = totalFrames > 1 ? totalFrames / 2 : 0;
        }

        logger.debug("Rendering frame {} of {} (frameRate: {})", frameIndex + 1, totalFrames, frameRate);

        // Use ImageIO with DICOM plugin to read the image
        BufferedImage bufferedImage;
        try (ImageInputStream iis = ImageIO.createImageInputStream(dicomFile)) {
            if (iis == null) {
                logger.error("Could not create ImageInputStream for DICOM file");
                return null;
            }

            ImageReader reader = getDicomImageReader();
            if (null == reader) {
                logger.error("No DICOM ImageReader found");
                return null;
            }
            reader.setInput(iis, false);

            DicomImageReadParam param = (DicomImageReadParam) reader.getDefaultReadParam();

            // Apply window center/width if specified
            if (params != null && params.hasWindow()) {
                param.setWindowCenter(params.getWindowCenter().floatValue());
                param.setWindowWidth(params.getWindowWidth().floatValue());
                param.setAutoWindowing(false);
            }

            try {
                bufferedImage = reader.read(frameIndex, param);
            } catch (Throwable readEx) {
                reader.dispose();
                handleCodecError(readEx, transferSyntax);
                throw readEx; // Re-throw if not a native library issue
            }

            reader.dispose();
        }

        if (bufferedImage == null) {
            logger.error("Could not read image from DICOM file");
            return null;
        }

        // Apply viewport scaling if specified
        if (params != null && params.hasViewport()) {
            bufferedImage = scaleImage(bufferedImage,
                    params.getViewportWidth(), params.getViewportHeight());
        }

        return new DecodedFrame(bufferedImage, totalFrames, frameIndex, frameRate);
    }

    /**
     * Check if a read error is due to missing codec support for advanced compression
     * formats and throw a descriptive UnsupportedOperationException if so.
     * If the error is not codec-related, this method returns without throwing.
     *
     * @param readEx the exception thrown during image reading
     * @param transferSyntax the DICOM transfer syntax UID of the image
     * @throws UnsupportedOperationException if the error is due to missing native codec libraries
     */
    private void handleCodecError(Throwable readEx, String transferSyntax) {
        if (!isAdvancedCompressionFormat(transferSyntax) || !isNativeLibraryMissing(readEx)) {
            return;
        }
        String tsName = getTransferSyntaxName(transferSyntax);

        String detailedMessage;
        if (hasUnsatisfiedLinkError(readEx)) {
            logger.error("Failed to render image with transfer syntax {} ({}). " +
                            "dcm4che-imageio-opencv is installed, but native OpenCV libraries are not found. " +
                            "Please install OpenCV: " +
                            "macOS: 'brew install opencv' | " +
                            "Ubuntu: 'sudo apt-get install libopencv-dev' | " +
                            "CentOS: 'sudo yum install opencv-devel'",
                    transferSyntax, tsName);
            detailedMessage = String.format(
                    "Cannot render image with transfer syntax: %s. " +
                            "Native OpenCV libraries are not installed on the system. " +
                            "To enable rendering of JPEG-LS and JPEG 2000 images, install OpenCV:\n" +
                            "  • macOS: brew install opencv\n" +
                            "  • Ubuntu/Debian: sudo apt-get install libopencv-dev\n" +
                            "  • CentOS/RHEL: sudo yum install opencv-devel\n" +
                            "Alternatively, use the retrieveInstance endpoint to download the original DICOM file.",
                    tsName);
        } else {
            logger.error("Failed to render image with transfer syntax {} ({}). " +
                            "This format requires additional codec support that is not available. " +
                            "See plugin documentation for installation instructions.",
                    transferSyntax, tsName);
            detailedMessage = String.format(
                    "Cannot render image with transfer syntax: %s. " +
                            "This compression format requires additional codec support (e.g., OpenCV libraries). " +
                            "Most DICOM files use JPEG Baseline compression which is fully supported. " +
                            "To access this file, use the retrieveInstance endpoint to download the original DICOM file.",
                    tsName);
        }

        throw new UnsupportedOperationException(detailedMessage);
    }

    /**
     * Render a DICOM file to JPEG format.
     * Decodes the requested frame, then encodes as JPEG with optional quality control.
     *
     * @param dicomFile DICOM file to render
     * @param requestedFrame requested frame number (1-based), or null for default (middle frame)
     * @param params rendering parameters (window, viewport, quality), may be null
     * @return rendered JPEG image result, or null if rendering failed
     */
    private RenderedInstanceResult renderDicomToJpeg(File dicomFile, Integer requestedFrame,
                                                     RenderingParams params) {
        try {
            DecodedFrame frame = decodeDicomFrame(dicomFile, requestedFrame, params);
            if (frame == null) {
                return null;
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            if (params != null && params.getQuality() != null) {
                ImageWriter jpegWriter = ImageIO.getImageWritersByFormatName("JPEG").next();
                ImageWriteParam writeParam = jpegWriter.getDefaultWriteParam();
                writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                writeParam.setCompressionQuality(params.getQuality() / 100f);
                jpegWriter.setOutput(ImageIO.createImageOutputStream(baos));
                jpegWriter.write(null, new IIOImage(frame.image, null, null), writeParam);
                jpegWriter.dispose();
            } else {
                ImageIO.write(frame.image, "JPEG", baos);
            }

            logger.debug("Successfully rendered DICOM to JPEG, size: {} bytes", baos.size());
            return new RenderedInstanceResult(baos.toByteArray(), frame.totalFrames,
                    frame.frameIndex + 1, frame.frameRate, ImageFormat.JPEG);

        } catch (UnsupportedOperationException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error rendering DICOM to JPEG", e);
            return null;
        }
    }

    /**
     * Render a DICOM file to PNG format.
     * Decodes the requested frame, then encodes as lossless PNG.
     *
     * @param dicomFile DICOM file to render
     * @param requestedFrame requested frame number (1-based), or null for default (middle frame)
     * @param params rendering parameters (window, viewport), may be null; quality is ignored for PNG
     * @return rendered PNG image result, or null if rendering failed
     */
    private RenderedInstanceResult renderDicomToPng(File dicomFile, Integer requestedFrame,
                                                    RenderingParams params) {
        try {
            DecodedFrame frame = decodeDicomFrame(dicomFile, requestedFrame, params);
            if (frame == null) {
                return null;
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(frame.image, "PNG", baos);

            logger.debug("Successfully rendered DICOM to PNG, size: {} bytes", baos.size());
            return new RenderedInstanceResult(baos.toByteArray(), frame.totalFrames,
                    frame.frameIndex + 1, frame.frameRate, ImageFormat.PNG);

        } catch (UnsupportedOperationException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error rendering DICOM to PNG", e);
            return null;
        }
    }

    /**
     * Check if the transfer syntax is an advanced compression format that requires native libraries.
     * JPEG-LS and JPEG 2000 require OpenCV native libraries.
     */
    private boolean isAdvancedCompressionFormat(String transferSyntax) {
        if (transferSyntax == null) {
            return false;
        }
        // JPEG-LS: 1.2.840.10008.1.2.4.80 (Lossless), 1.2.840.10008.1.2.4.81 (Near-lossless)
        // JPEG 2000: 1.2.840.10008.1.2.4.90 (Lossless), 1.2.840.10008.1.2.4.91 (Lossy)
        return transferSyntax.startsWith("1.2.840.10008.1.2.4.80") ||
               transferSyntax.startsWith("1.2.840.10008.1.2.4.81") ||
               transferSyntax.startsWith("1.2.840.10008.1.2.4.90") ||
               transferSyntax.startsWith("1.2.840.10008.1.2.4.91");
    }

    /**
     * Check if the exception/error indicates missing native libraries.
     * Typically manifests as UnsatisfiedLinkError, NoClassDefFoundError, or specific IOException messages.
     */
    private boolean isNativeLibraryMissing(Throwable e) {
        if (e == null) {
            return false;
        }

        // Check for NoClassDefFoundError or UnsatisfiedLinkError (native library not loaded)
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof UnsatisfiedLinkError || cause instanceof NoClassDefFoundError) {
                return true;
            }
            // Check for common error messages indicating missing codec support
            String message = cause.getMessage();
            if (message != null) {
                String lowerMsg = message.toLowerCase();
                if (lowerMsg.contains("no image reader") ||
                    lowerMsg.contains("unsupported") ||
                    lowerMsg.contains("cannot read") ||
                    lowerMsg.contains("codec") ||
                    lowerMsg.contains("native") ||
                    lowerMsg.contains("could not initialize class") ||
                    lowerMsg.contains("streamsegment")) {
                    return true;
                }
            }
            cause = cause.getCause();
        }

        return false;
    }

    /**
     * Check if the exception/error chain contains UnsatisfiedLinkError or NoClassDefFoundError.
     * This specifically indicates that native libraries (e.g., OpenCV) are not installed.
     */
    private boolean hasUnsatisfiedLinkError(Throwable e) {
        if (e == null) {
            return false;
        }

        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof UnsatisfiedLinkError || cause instanceof NoClassDefFoundError) {
                return true;
            }
            cause = cause.getCause();
        }

        return false;
    }

    /**
     * Get human-readable name for a transfer syntax UID.
     */
    private String getTransferSyntaxName(String transferSyntax) {
        if (transferSyntax == null) return "Unknown";
        switch (transferSyntax) {
            case "1.2.840.10008.1.2": return "Implicit VR Little Endian";
            case "1.2.840.10008.1.2.1": return "Explicit VR Little Endian";
            case "1.2.840.10008.1.2.2": return "Explicit VR Big Endian";
            case "1.2.840.10008.1.2.4.50": return "JPEG Baseline";
            case "1.2.840.10008.1.2.4.51": return "JPEG Extended";
            case "1.2.840.10008.1.2.4.57": return "JPEG Lossless";
            case "1.2.840.10008.1.2.4.70": return "JPEG Lossless SV1";
            case "1.2.840.10008.1.2.4.80": return "JPEG-LS Lossless";
            case "1.2.840.10008.1.2.4.81": return "JPEG-LS Near-lossless";
            case "1.2.840.10008.1.2.4.90": return "JPEG 2000 Lossless";
            case "1.2.840.10008.1.2.4.91": return "JPEG 2000 Lossy";
            case "1.2.840.10008.1.2.5": return "RLE Lossless";
            default: return transferSyntax;
        }
    }

    /**
     * Extract frame rate from DICOM attributes.
     * Tries multiple tags in order of preference:
     * 1. Frame Time (0018,1063) - time per frame in milliseconds
     * 2. Cine Rate (0018,0040) - frames per second
     * 3. Recommended Display Frame Rate (0008,2144)
     *
     * @return frame rate in frames per second, or null if not available
     */
    private Double extractFrameRate(Attributes attrs) {
        // Try Frame Time (milliseconds per frame)
        double frameTime = attrs.getDouble(Tag.FrameTime, 0.0);
        if (frameTime > 0) {
            return 1000.0 / frameTime;  // Convert to FPS
        }

        // Try Cine Rate (frames per second)
        double cineRate = attrs.getDouble(Tag.CineRate, 0.0);
        if (cineRate > 0) {
            return cineRate;
        }

        // Try Recommended Display Frame Rate
        int recommendedRate = attrs.getInt(Tag.RecommendedDisplayFrameRate, 0);
        if (recommendedRate > 0) {
            return (double) recommendedRate;
        }

        return null;
    }

    /**
     * Render a DICOM file to GIF format (animated for multi-frame)
     * @param dicomFile DICOM file to render
     * @param requestedFrame optional frame number (1-based) - if specified, renders single frame as static GIF
     * @return RenderedInstanceResult with GIF data and metadata
     */
    private RenderedInstanceResult renderDicomToGif(File dicomFile, Integer requestedFrame,
                                                      RenderingParams params) {
        try {
            // First, read DICOM metadata to determine frame count and frame rate
            int totalFrames = 1;
            Double frameRate = null;

            try (DicomInputStream dis = new DicomInputStream(dicomFile)) {
                Attributes attrs = dis.readDataset(-1, -1);
                totalFrames = attrs.getInt(Tag.NumberOfFrames, 1);
                frameRate = extractFrameRate(attrs);
            }

            // If single frame or specific frame requested, render as static GIF
            if (totalFrames == 1 || requestedFrame != null) {
                return renderSingleFrameAsGif(dicomFile, requestedFrame, totalFrames, frameRate, params);
            }

            // Multi-frame: render as animated GIF
            return renderAnimatedGif(dicomFile, totalFrames, frameRate, params);

        } catch (Exception e) {
            logger.error("Error rendering DICOM to GIF", e);
            return null;
        }
    }

    /**
     * Render a single frame as static GIF
     */
    private RenderedInstanceResult renderSingleFrameAsGif(File dicomFile, Integer requestedFrame,
                                                          int totalFrames, Double frameRate,
                                                          RenderingParams params) throws Exception {
        int frameIndex = 0;
        // Determine which frame to render
        if (requestedFrame != null) {
            frameIndex = requestedFrame - 1;
            if (frameIndex < 0 || frameIndex >= totalFrames) {
                frameIndex = totalFrames / 2;
            }
        } else {
            frameIndex = totalFrames > 1 ? totalFrames / 2 : 0;
        }

        BufferedImage image;
        try (ImageInputStream iis = ImageIO.createImageInputStream(dicomFile)) {
            if (iis == null) {
                logger.error("Could not create ImageInputStream for DICOM file");
                return null;
            }

            ImageReader reader = getDicomImageReader();
            if (null == reader) {
                logger.error("No DICOM ImageReader found");
                return null;
            }

            reader.setInput(iis, false);
            DicomImageReadParam param = (DicomImageReadParam) reader.getDefaultReadParam();
            image = reader.read(frameIndex, param);
            reader.dispose();
        }

        if (image == null) {
            return null;
        }

        // Apply viewport scaling if specified
        if (params != null && params.hasViewport()) {
            image = scaleImage(image, params.getViewportWidth(), params.getViewportHeight());
        }

        // Encode as GIF
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "GIF", baos);

        logger.debug("Rendered single frame {} as static GIF, size: {} bytes", frameIndex + 1, baos.size());

        return new RenderedInstanceResult(baos.toByteArray(), totalFrames, frameIndex + 1,
                frameRate, ImageFormat.GIF);
    }

    /**
     * Render all frames as animated GIF
     */
    private RenderedInstanceResult renderAnimatedGif(File dicomFile, int totalFrames,
                                                     Double frameRate, RenderingParams params) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ImageInputStream iis = ImageIO.createImageInputStream(dicomFile)) {
            if (iis == null) {
                logger.error("Could not create ImageInputStream for DICOM file");
                return null;
            }

            final ImageReader reader = getDicomImageReader();
            if (null == reader) {
                logger.error("No DICOM ImageReader found");
                return null;
            }
            reader.setInput(iis, false);
            DicomImageReadParam param = (DicomImageReadParam) reader.getDefaultReadParam();

            // Calculate frame delay in centiseconds (1/100 second)
            // Default to 10 fps (100ms = 10 centiseconds) if no frame rate available
            int delayInCentiseconds = 10;  // Default
            if (frameRate != null && frameRate > 0) {
                // Convert FPS to delay in centiseconds
                delayInCentiseconds = (int) Math.round(100.0 / frameRate);
                if (delayInCentiseconds < 1) delayInCentiseconds = 1;  // Minimum 1 centisecond
            }

            logger.debug("Rendering {} frames as animated GIF, delay: {} centiseconds (frameRate: {})",
                    totalFrames, delayInCentiseconds, frameRate);

            // Create animated GIF encoder

            AnimatedGifEncoder encoder = new AnimatedGifEncoder();
            encoder.start(baos);
            encoder.setDelay(delayInCentiseconds * 10);  // setDelay expects milliseconds
            encoder.setRepeat(0);  // 0 = loop forever

            // Read and encode all frames
            for (int frameIndex = 0; frameIndex < totalFrames; frameIndex++) {
                BufferedImage image = reader.read(frameIndex, param);
                if (image != null) {
                    if (params != null && params.hasViewport()) {
                        image = scaleImage(image, params.getViewportWidth(), params.getViewportHeight());
                    }
                    encoder.addFrame(image);
                    logger.trace("Added frame {} to animated GIF", frameIndex + 1);
                } else {
                    logger.warn("Failed to read frame {}", frameIndex + 1);
                }
            }

            encoder.finish();
            reader.dispose();
        }

        logger.trace("Successfully rendered {} frames as animated GIF, size: {} bytes",
                totalFrames, baos.size());

        return new RenderedInstanceResult(baos.toByteArray(), totalFrames, totalFrames,
                frameRate, ImageFormat.GIF);
    }

    /**
     * Scale a BufferedImage to target dimensions using bilinear interpolation.
     *
     * @param src the source image to scale
     * @param targetWidth desired width in pixels
     * @param targetHeight desired height in pixels
     * @return a new BufferedImage scaled to the target dimensions
     */
    private BufferedImage scaleImage(BufferedImage src, int targetWidth, int targetHeight) {
        int type = src.getType() != 0 ? src.getType() : BufferedImage.TYPE_INT_RGB;
        BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, type);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, targetWidth, targetHeight, null);
        g.dispose();
        return scaled;
    }

    @Override
    public List<byte[]> retrieveFrames(UserI user, String projectId, String studyInstanceUID,
                                      String seriesInstanceUID, String sopInstanceUID, String frameNumbers) {
        File dicomFile = getInstance(user, projectId, studyInstanceUID, seriesInstanceUID, sopInstanceUID);

        List<Integer> frameList = parseFrameNumbers(frameNumbers);
        if (frameList.isEmpty()) {
            throw new BadRequestException("frameList", "no valid frame numbers provided");
        }

        List<byte[]> frames = new ArrayList<>();

        try (DicomInputStream dis = new DicomInputStream(dicomFile)) {
            Attributes attrs = dis.readDataset(-1, -1);
            int numberOfFrames = attrs.getInt(Tag.NumberOfFrames, 1);

            logger.trace("Retrieving frames {} from instance {} (total frames: {})",
                    frameNumbers, sopInstanceUID, numberOfFrames);

            for (Integer frameNumber : frameList) {
                if (frameNumber < 1 || frameNumber > numberOfFrames) {
                    logger.warn("Frame number {} out of range (1-{})", frameNumber, numberOfFrames);
                    continue;
                }

                byte[] frameData = extractFramePixelData(dicomFile, frameNumber - 1);
                if (frameData != null) {
                    frames.add(frameData);
                }
            }
        } catch (IOException e) {
            throw new DicomWebException("Error reading frames from instance " + sopInstanceUID, e,
                    500, "ReadError");
        }

        logger.trace("Retrieved {} frame(s) from instance: {}", frames.size(), sopInstanceUID);
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
            BufferedImage image;
            try (ImageInputStream iis = ImageIO.createImageInputStream(dicomFile)) {
                if (iis == null) {
                    logger.error("Could not create ImageInputStream");
                    return null;
                }

                ImageReader reader = getDicomImageReader();
                if (null == reader) {
                    logger.error("No DICOM ImageReader found");
                    return null;
                }

                reader.setInput(iis, false);

                int numImages = reader.getNumImages(true);
                if (frameIndex < 0 || frameIndex >= numImages) {
                    logger.error("Frame index {} out of range (0-{})", frameIndex, numImages - 1);
                    reader.dispose();
                    return null;
                }

                // Read and decompress the frame
                DicomImageReadParam param = (DicomImageReadParam) reader.getDefaultReadParam();

                try {
                    image = reader.read(frameIndex, param);
                } catch (Throwable readEx) {
                    reader.dispose();

                    // Check if this is due to missing OpenCV native libraries
                    if (readEx instanceof NoClassDefFoundError || readEx instanceof UnsatisfiedLinkError) {
                        logger.error("Failed to read frame due to missing native libraries: {}", readEx.getMessage());
                        throw new UnsupportedOperationException(
                                "Cannot extract frame data. This DICOM file uses compression formats (JPEG-LS or JPEG 2000) " +
                                        "that require OpenCV native libraries. " +
                                        "Install OpenCV (macOS: 'brew install opencv', Ubuntu: 'apt-get install libopencv-dev') " +
                                        "or use the retrieveInstance endpoint to download the original DICOM file.");
                    }
                    throw new RuntimeException("Failed to read frame: " + readEx.getMessage(), readEx);
                }

                reader.dispose();
            }

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

        // StudyTime matching (range support)
        if (query.contains(Tag.StudyTime)) {
            String queryValue = query.getString(Tag.StudyTime);
            String attrValue = attrs.getString(Tag.StudyTime);
            if (!matchesDicomTime(attrValue, queryValue)) {
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

    /**
     * DICOM time matching with range support
     * Supports formats: HHMMSS, HHMMSS.FFFFFF, HHMMSS-, -HHMMSS, HHMMSS-HHMMSS
     * Time values are compared as strings (lexicographic comparison works for DICOM TM format)
     */
    private boolean matchesDicomTime(String attrValue, String queryValue) {
        if (queryValue == null || queryValue.isEmpty()) {
            return true;
        }

        if (attrValue == null || attrValue.isEmpty()) {
            return false;
        }

        // Normalize time values (remove fractional seconds for comparison if needed)
        String normalizedAttr = normalizeTime(attrValue);
        String normalizedQuery = queryValue;

        // Single time match
        if (!normalizedQuery.contains("-")) {
            String normalizedQueryTime = normalizeTime(normalizedQuery);
            return normalizedAttr.startsWith(normalizedQueryTime) || normalizedQueryTime.startsWith(normalizedAttr);
        }

        // Range match: startTime-endTime
        String[] parts = normalizedQuery.split("-", -1);

        if (parts.length == 2) {
            String startTime = normalizeTime(parts[0]);
            String endTime = normalizeTime(parts[1]);

            // startTime- (from time onwards)
            if (endTime.isEmpty()) {
                return normalizedAttr.compareTo(startTime) >= 0;
            }

            // -endTime (up to time)
            if (startTime.isEmpty()) {
                return normalizedAttr.compareTo(endTime) <= 0;
            }

            // startTime-endTime (between times)
            return normalizedAttr.compareTo(startTime) >= 0 && normalizedAttr.compareTo(endTime) <= 0;
        }

        return false;
    }

    /**
     * Normalize DICOM time value for comparison
     * Removes fractional seconds and ensures consistent length
     */
    private String normalizeTime(String time) {
        if (time == null || time.isEmpty()) {
            return "";
        }
        // Remove fractional seconds (after decimal point)
        int dotIndex = time.indexOf('.');
        if (dotIndex > 0) {
            time = time.substring(0, dotIndex);
        }
        // Pad to 6 characters (HHMMSS) if shorter
        while (time.length() < 6) {
            time = time + "0";
        }
        return time;
    }

    @Override
    public StowRsResponse storeInstances(UserI user, String projectId, List<InputStream> dicomInstances) {
        logger.debug("STOW-RS: Storing {} DICOM instances for user {} in project {}",
            dicomInstances.size(), user.getLogin(), projectId);

        List<InstanceStatus> statuses = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;

        try {
            // Verify project access
            XnatProjectdata project = XnatProjectdata.getXnatProjectdatasById(projectId, user, false);
            if (project == null) {
                logger.debug("User {} does not have access to project: {}", user.getLogin(), projectId);
                throw new SecurityException("No access to project: " + projectId);
            }

            // Group instances by StudyInstanceUID to create proper sessions
            java.util.Map<String, java.util.List<DicomInstance>> instancesByStudy = new java.util.HashMap<>();

            // First pass: Read DICOM metadata and group by StudyInstanceUID
            for (int i = 0; i < dicomInstances.size(); i++) {
                InputStream stream = dicomInstances.get(i);

                try {
                    // Read DICOM with all data (including PixelData) because we need to write complete files later
                    Attributes attrs = DicomWebUtils.readDicom(stream);
                    String studyInstanceUID = attrs.getString(Tag.StudyInstanceUID);
                    String sopInstanceUID = attrs.getString(Tag.SOPInstanceUID);
                    String sopClassUID = attrs.getString(Tag.SOPClassUID);
                    String seriesNumber = attrs.getString(Tag.SeriesNumber, "1");

                    if (sopInstanceUID == null || sopClassUID == null || studyInstanceUID == null) {
                        logger.warn("DICOM instance missing required UIDs");
                        statuses.add(new InstanceStatus(sopInstanceUID, sopClassUID, false,
                            "Missing required UIDs", 0xA900));
                        failureCount++;
                        continue;
                    }

                    // Group by StudyInstanceUID
                    instancesByStudy.computeIfAbsent(studyInstanceUID, k -> new java.util.ArrayList<>())
                        .add(new DicomInstance(attrs, sopInstanceUID, sopClassUID, seriesNumber));

                } catch (Exception e) {
                    logger.error("STOW-RS: Error reading DICOM instance " + (i + 1), e);
                    statuses.add(new InstanceStatus(null, null, false,
                        "Error reading DICOM: " + e.getMessage(), 0xC000));
                    failureCount++;
                }
            }

            // Second pass: Process each study group using PrearcDatabase
            for (java.util.Map.Entry<String, java.util.List<DicomInstance>> entry : instancesByStudy.entrySet()) {
                String studyInstanceUID = entry.getKey();
                java.util.List<DicomInstance> instances = entry.getValue();

                logger.trace("STOW-RS: Processing study {} with {} instances", studyInstanceUID, instances.size());

                try {
                    // Get or create prearchive session for this StudyInstanceUID
                    org.nrg.xnat.helpers.prearchive.SessionData session =
                        getOrCreatePrearchiveSession(project, user, studyInstanceUID, instances.get(0).getAttributes());

                    File sessionDir = new File(session.getUrl());
                    logger.trace("STOW-RS: Using session directory: {}", sessionDir.getAbsolutePath());

                    // Write each instance to the session
                    for (DicomInstance instance : instances) {
                        try {
                            File seriesDir = new File(sessionDir, "SCANS/" + instance.getSeriesNumber());
                            seriesDir.mkdirs();

                            File dicomFile = new File(seriesDir, instance.getSopInstanceUID() + ".dcm");
                            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(dicomFile);
                                 org.dcm4che3.io.DicomOutputStream dos = new org.dcm4che3.io.DicomOutputStream(fos, org.dcm4che3.data.UID.ExplicitVRLittleEndian)) {
                                dos.writeDataset(null, instance.getAttributes());
                            }

                            logger.trace("STOW-RS: Wrote DICOM file: {}", dicomFile.getAbsolutePath());
                            statuses.add(new InstanceStatus(instance.getSopInstanceUID(), instance.getSopClassUID(), true, null, 0));
                            successCount++;

                        } catch (Exception e) {
                            logger.error("STOW-RS: Error writing instance", e);
                            statuses.add(new InstanceStatus(instance.getSopInstanceUID(), instance.getSopClassUID(), false,
                                e.getMessage(), 0xC000));
                            failureCount++;
                        }
                    }

                } catch (Exception e) {
                    System.err.println("=== ERROR processing study " + studyInstanceUID + ": " + e.getClass().getName() + ": " + e.getMessage());
                    e.printStackTrace(System.err);
                    logger.error("STOW-RS: Error processing study " + studyInstanceUID, e);
                    // Mark all instances in this study as failed
                    for (DicomInstance instance : instances) {
                        statuses.add(new InstanceStatus(instance.getSopInstanceUID(), instance.getSopClassUID(), false,
                            "Session error: " + e.getMessage(), 0xC000));
                        failureCount++;
                    }
                }
            }

        } catch (SecurityException e) {
            logger.error("Security exception during STOW-RS", e);
            throw e;
        } catch (Exception e) {
            logger.error("STOW-RS storage failed", e);
            throw new RuntimeException("Storage failed: " + e.getMessage(), e);
        }

        logger.debug("STOW-RS: Completed - {} succeeded, {} failed", successCount, failureCount);
        return new StowRsResponse(successCount, failureCount, statuses);
    }

    /**
     * Get existing prearchive session for StudyInstanceUID or create new one
     */
    private org.nrg.xnat.helpers.prearchive.SessionData getOrCreatePrearchiveSession(
            XnatProjectdata project, UserI user, String studyInstanceUID, Attributes dicomAttrs) throws Exception {

        // Get prearchive root path
        String timestamp = org.nrg.xnat.helpers.prearchive.PrearcUtils.makeTimestamp();
        String prearchiveBasePath = org.nrg.xnat.turbine.utils.ArcSpecManager.GetInstance().getGlobalPrearchivePath();
        File prearchiveRootDir = java.nio.file.Paths.get(prearchiveBasePath, project.getId(), timestamp).toFile();

        // Create SessionData to initialize or find existing session
        org.nrg.xnat.helpers.prearchive.SessionData initialize = new org.nrg.xnat.helpers.prearchive.SessionData();
        initialize.setProject(project.getId());
        initialize.setTag(studyInstanceUID);  // KEY: This is how sessions are matched!

        // Derive session name from DICOM metadata (XNAT uses PatientID by default)
        String patientID = dicomAttrs.getString(Tag.PatientID, "");
        String sessionLabel = patientID.replaceAll("[^a-zA-Z0-9_]", "_");
        if (sessionLabel.isEmpty()) {
            // Fallback if no PatientID
            sessionLabel = "DICOMWEB_" + studyInstanceUID.substring(0, Math.min(20, studyInstanceUID.length()));
        }

        initialize.setFolderName(sessionLabel);
        initialize.setName(sessionLabel);
        initialize.setUrl(new File(prearchiveRootDir, sessionLabel).getAbsolutePath());
        initialize.setTimestamp(timestamp);
        initialize.setStatus(org.nrg.xnat.helpers.prearchive.PrearcUtils.PrearcStatus.RECEIVING);
        initialize.setLastBuiltDate(java.util.Calendar.getInstance().getTime());
        initialize.setSource("DICOMWEB_STOW");

        // Set study date if available
        java.util.Date studyDate = dicomAttrs.getDate(Tag.StudyDate);
        if (studyDate != null) {
            initialize.setScan_date(studyDate);
        }

        // Get or create session - this will find existing session with same StudyInstanceUID!
        org.nrg.xnat.helpers.prearchive.PrearcDatabase.Either<org.nrg.xnat.helpers.prearchive.SessionData, org.nrg.xnat.helpers.prearchive.SessionData> getOrCreate =
            org.nrg.xnat.helpers.prearchive.PrearcDatabase.eitherGetOrCreateSession(
                initialize,
                prearchiveRootDir,
                org.nrg.framework.constants.PrearchiveCode.Manual);

        org.nrg.xnat.helpers.prearchive.SessionData session = getOrCreate.isLeft() ? getOrCreate.getLeft() : getOrCreate.getRight();

        logger.debug("STOW-RS: {} session for StudyInstanceUID {}: {}",
                getOrCreate.isLeft() ? "Created new" : "Using existing", studyInstanceUID, session.getUrl());

        return session;
    }

    // ==================== Bulk Data & Pixel Data Methods ====================

    /**
     * Extract bulk data items from a DICOM file.
     *
     * @param dicomFile the DICOM file
     * @param baseUri base URI for generating BulkDataURI
     * @param studyUID Study Instance UID
     * @param seriesUID Series Instance UID
     * @param instanceUID SOP Instance UID
     * @param pixelDataOnly if true, only include pixel data tags
     * @return list of BulkDataItem
     */
    private List<BulkDataHandler.BulkDataItem> extractBulkDataItems(
            File dicomFile, String baseUri, String studyUID, String seriesUID,
            String instanceUID, boolean pixelDataOnly) {
        List<BulkDataHandler.BulkDataItem> items = new ArrayList<>();
        try (DicomInputStream dis = new DicomInputStream(dicomFile)) {
            dis.setIncludeBulkData(DicomInputStream.IncludeBulkData.YES);
            Attributes attrs = dis.readDataset();

            attrs.accept(new Attributes.Visitor() {
                @Override
                public boolean visit(Attributes attrs, int tag, VR vr, Object value) throws Exception {
                    if (!BulkDataHandler.shouldUseBulkDataURI(tag, vr, value)) {
                        return true;
                    }
                    if (pixelDataOnly && !BulkDataHandler.isPixelDataTag(tag)) {
                        return true;
                    }
                    byte[] data = attrs.getBytes(tag);
                    if (data != null && data.length > 0) {
                        String contentLocation = BulkDataHandler.generateBulkDataURI(
                                baseUri, studyUID, seriesUID, instanceUID, tag);
                        items.add(new BulkDataHandler.BulkDataItem(contentLocation, data));
                    }
                    return true;
                }
            }, false);
        } catch (Exception e) {
            logger.error("Error extracting bulk data from file {}", dicomFile.getAbsolutePath(), e);
        }
        return items;
    }

    /** {@inheritDoc} */
    @Override
    public List<BulkDataHandler.BulkDataItem> retrieveInstanceBulkData(
            UserI user, String projectId, String studyUID, String seriesUID,
            String instanceUID, String baseUri) {
        File dicomFile = getInstance(user, projectId, studyUID, seriesUID, instanceUID);
        return extractBulkDataItems(dicomFile, baseUri, studyUID, seriesUID, instanceUID, false);
    }

    /** {@inheritDoc} */
    @Override
    public List<BulkDataHandler.BulkDataItem> retrieveSeriesBulkData(
            UserI user, String projectId, String studyUID, String seriesUID, String baseUri) {
        return retrieveBulkDataAcrossInstances(user, projectId, studyUID, seriesUID, baseUri, false);
    }

    /** {@inheritDoc} */
    @Override
    public List<BulkDataHandler.BulkDataItem> retrieveStudyBulkData(
            UserI user, String projectId, String studyUID, String baseUri) {
        List<BulkDataHandler.BulkDataItem> items = new ArrayList<>();
        List<Attributes> seriesList = searchSeries(user, projectId, studyUID, null);
        for (Attributes seriesAttrs : seriesList) {
            String seriesUID = seriesAttrs.getString(Tag.SeriesInstanceUID);
            items.addAll(retrieveBulkDataAcrossInstances(user, projectId, studyUID, seriesUID, baseUri, false));
        }
        return items;
    }

    /** {@inheritDoc} */
    @Override
    public List<BulkDataHandler.BulkDataItem> retrieveInstancePixelData(
            UserI user, String projectId, String studyUID, String seriesUID,
            String instanceUID, String baseUri) {
        File dicomFile = getInstance(user, projectId, studyUID, seriesUID, instanceUID);
        return extractBulkDataItems(dicomFile, baseUri, studyUID, seriesUID, instanceUID, true);
    }

    /** {@inheritDoc} */
    @Override
    public List<BulkDataHandler.BulkDataItem> retrieveSeriesPixelData(
            UserI user, String projectId, String studyUID, String seriesUID, String baseUri) {
        return retrieveBulkDataAcrossInstances(user, projectId, studyUID, seriesUID, baseUri, true);
    }

    /** {@inheritDoc} */
    @Override
    public List<BulkDataHandler.BulkDataItem> retrieveStudyPixelData(
            UserI user, String projectId, String studyUID, String baseUri) {
        List<BulkDataHandler.BulkDataItem> items = new ArrayList<>();
        List<Attributes> seriesList = searchSeries(user, projectId, studyUID, null);
        for (Attributes seriesAttrs : seriesList) {
            String seriesUID = seriesAttrs.getString(Tag.SeriesInstanceUID);
            items.addAll(retrieveBulkDataAcrossInstances(user, projectId, studyUID, seriesUID, baseUri, true));
        }
        return items;
    }

    private List<BulkDataHandler.BulkDataItem> retrieveBulkDataAcrossInstances(
            UserI user, String projectId, String studyUID, String seriesUID,
            String baseUri, boolean pixelDataOnly) {
        List<BulkDataHandler.BulkDataItem> items = new ArrayList<>();
        try {
            List<Attributes> instances = searchInstances(user, projectId, studyUID, seriesUID, null);
            for (Attributes attrs : instances) {
                String sopUID = attrs.getString(Tag.SOPInstanceUID);
                try {
                    File dicomFile = getInstance(user, projectId, studyUID, seriesUID, sopUID);
                    items.addAll(extractBulkDataItems(dicomFile, baseUri, studyUID, seriesUID, sopUID, pixelDataOnly));
                } catch (ResourceNotFoundException e) {
                    logger.debug("Instance {} not found while retrieving bulk data", sopUID);
                }
            }
        } catch (Exception e) {
            logger.error("Error retrieving bulk data for series {}", seriesUID, e);
        }
        return items;
    }

    /**
     * Helper class to hold DICOM instance data
     */
    private static class DicomInstance {
        private final Attributes attributes;
        private final String sopInstanceUID;
        private final String sopClassUID;
        private final String seriesNumber;

        public DicomInstance(Attributes attrs, String sopUID, String sopClass, String seriesNum) {
            this.attributes = attrs;
            this.sopInstanceUID = sopUID;
            this.sopClassUID = sopClass;
            this.seriesNumber = seriesNum;
        }

        public Attributes getAttributes() { return attributes; }
        public String getSopInstanceUID() { return sopInstanceUID; }
        public String getSopClassUID() { return sopClassUID; }
        public String getSeriesNumber() { return seriesNumber; }
    }

}
