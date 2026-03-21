package org.nrg.xnat.dicomweb.service;

import org.dcm4che3.data.Attributes;
import org.nrg.xft.security.UserI;

import org.nrg.xnat.dicomweb.util.BulkDataHandler;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.stream.Stream;

/**
 * Service interface for accessing DICOM data from XNAT
 */
public interface XnatDicomService {
    /**
     * Search for studies in a project
     * @param user calling user
     * @param projectId project to search
     * @param queryAttributes search constraints as specified in PS 3.18 10.6.1.2.3 Required Matching Attributes
     * @return study resource search results as specified in PS 3.18 Table 10.6.3-3
     */
    List<Attributes> searchStudies(UserI user, String projectId, Attributes queryAttributes);

    /**
     * Search for series within a study
     * @param user calling user
     * @param projectId project to search
     * @param studyInstanceUID Study Instance UID of containing study
     * @param queryAttributes search constraints as specified in PS 3.18 10.6.1.2.3 Required Matching Attributes
     * @return series resource search results as specified in PS 3.18 Table 10.6.3-4
     */
    List<Attributes> searchSeries(UserI user, String projectId, String studyInstanceUID, Attributes queryAttributes);

    /**
     * Search for instances within a series
     * @param user calling user
     * @param projectId project to search
     * @param studyInstanceUID Study Instance UID of containing study
     * @param seriesInstanceUID Series Instance UID of containing series
     * @param queryAttributes search constraints as specified in PS 3.18 10.6.1.2.3 Required Matching Attributes
     * @return instance resource search results as specified in PS 3.18 Table 10.6.3-5
     */
    List<Attributes> searchInstances(UserI user, String projectId, String studyInstanceUID, String seriesInstanceUID, Attributes queryAttributes);

    /**
     * Search of instances within a series, returning the metadata view (Bulk Data via URI)
     * @param user calling user
     * @param projectId project to search
     * @param studyInstanceUID containing study
     * @param seriesInstanceUID containing series
     * @param queryAttributes search constraints as specified in PS 3.18 10.6.1.2.3 Required Matching Attributes
     *                        if null or empty, all instances match
     * @return metadata search results
     */
    Stream<Attributes> searchMetadata(UserI user, String projectId, String studyInstanceUID, String seriesInstanceUID, Attributes queryAttributes);

    /**
     * Retrieve a DICOM instance
     * PS 3.18 is vague about what's included, but examples in Appendix B include Transfer Syntax UID, suggesting
     * that the intended response is FMI + full dataset.
     * @param user calling user
     * @param projectId project to search
     * @param studyInstanceUID Study Instance UID of containing study
     * @param seriesInstanceUID Series Instance UID of containing series
     * @param sopInstanceUID SOP Instance UID of instance to be retrieved
     * @return byte stream of DICOM instance in Part 10 format, or null in case of failure
     */
    InputStream retrieveInstance(UserI user, String projectId, String studyInstanceUID, String seriesInstanceUID, String sopInstanceUID) throws IOException;

    /**
     * Retrieve metadata for an instance
     * Per PS 3.18 3.9 Web Services Definitions, this is the full instance, no FMI, with bulk data as URIs
     */
    Attributes retrieveMetadata(UserI user, String projectId, String studyInstanceUID, String seriesInstanceUID, String sopInstanceUID);


    /**
     * Retrieve metadata for all instances in a study
     */
    Stream<Attributes> retrieveAllStudyInstanceMetadata(UserI user, String projectId, String studyInstanceUID);

    /**
     * Retrieve all instances in a study
     */
    List<InputStream> retrieveStudy(UserI user, String projectId, String studyInstanceUID);

    /**
     * Retrieve all instances in a series
     */
    List<InputStream> retrieveSeries(UserI user, String projectId, String studyInstanceUID, String seriesInstanceUID);

    /**
     * Retrieve a rendered instance with rendering parameters.
     *
     * @param user the authenticated user
     * @param projectId XNAT project identifier
     * @param studyInstanceUID Study Instance UID
     * @param seriesInstanceUID Series Instance UID
     * @param sopInstanceUID SOP Instance UID
     * @param frameNumber optional frame number (1-based), null for default
     * @param format desired output format
     * @param params rendering parameters (viewport, window, quality), may be null
     * @return RenderedInstanceResult containing image data and metadata
     */
    RenderedInstanceResult retrieveRenderedInstance(UserI user, String projectId, String studyInstanceUID,
                                                   String seriesInstanceUID, String sopInstanceUID,
                                                   Integer frameNumber, ImageFormat format,
                                                   RenderingParams params);

    /**
     * Retrieve a rendered representative image for a study.
     * Selects a representative instance (middle instance of first series) and renders it.
     *
     * @param user the authenticated user
     * @param projectId XNAT project identifier
     * @param studyInstanceUID Study Instance UID
     * @param frameNumber optional frame number (1-based), null for default
     * @param format desired output format
     * @param params rendering parameters, may be null
     * @return RenderedInstanceResult containing image data and metadata
     */
    RenderedInstanceResult retrieveRenderedStudy(UserI user, String projectId,
                                                 String studyInstanceUID, Integer frameNumber,
                                                 ImageFormat format, RenderingParams params);

    /**
     * Retrieve a rendered representative image for a series.
     * Selects a representative instance (middle instance) and renders it.
     *
     * @param user the authenticated user
     * @param projectId XNAT project identifier
     * @param studyInstanceUID Study Instance UID
     * @param seriesInstanceUID Series Instance UID
     * @param frameNumber optional frame number (1-based), null for default
     * @param format desired output format
     * @param params rendering parameters, may be null
     * @return RenderedInstanceResult containing image data and metadata
     */
    RenderedInstanceResult retrieveRenderedSeries(UserI user, String projectId,
                                                  String studyInstanceUID, String seriesInstanceUID,
                                                  Integer frameNumber, ImageFormat format,
                                                  RenderingParams params);

    /**
     * Retrieve a thumbnail for a study (small rendered image).
     *
     * @param user the authenticated user
     * @param projectId XNAT project identifier
     * @param studyInstanceUID Study Instance UID
     * @param params rendering parameters (viewport defaults to 128x128 if not specified)
     * @param format output image format (defaults to JPEG if null)
     * @return RenderedInstanceResult containing thumbnail image data
     */
    RenderedInstanceResult retrieveThumbnailStudy(UserI user, String projectId,
                                                  String studyInstanceUID, RenderingParams params,
                                                  ImageFormat format);

    /**
     * Retrieve a thumbnail for a series.
     *
     * @param user the authenticated user
     * @param projectId XNAT project identifier
     * @param studyInstanceUID Study Instance UID
     * @param seriesInstanceUID Series Instance UID
     * @param params rendering parameters (viewport defaults to 128x128 if not specified)
     * @param format output image format (defaults to JPEG if null)
     * @return RenderedInstanceResult containing thumbnail image data
     */
    RenderedInstanceResult retrieveThumbnailSeries(UserI user, String projectId,
                                                   String studyInstanceUID, String seriesInstanceUID,
                                                   RenderingParams params, ImageFormat format);

    /**
     * Retrieve a thumbnail for an instance.
     *
     * @param user the authenticated user
     * @param projectId XNAT project identifier
     * @param studyInstanceUID Study Instance UID
     * @param seriesInstanceUID Series Instance UID
     * @param sopInstanceUID SOP Instance UID
     * @param params rendering parameters (viewport defaults to 128x128 if not specified)
     * @param format output image format (defaults to JPEG if null)
     * @return RenderedInstanceResult containing thumbnail image data
     */
    RenderedInstanceResult retrieveThumbnailInstance(UserI user, String projectId,
                                                    String studyInstanceUID, String seriesInstanceUID,
                                                    String sopInstanceUID, RenderingParams params,
                                                    ImageFormat format);

    /**
     * Retrieve a thumbnail for specific frame(s).
     *
     * @param user the authenticated user
     * @param projectId XNAT project identifier
     * @param studyInstanceUID Study Instance UID
     * @param seriesInstanceUID Series Instance UID
     * @param sopInstanceUID SOP Instance UID
     * @param frameList comma-separated list of frame numbers (1-based)
     * @param params rendering parameters (viewport defaults to 128x128 if not specified)
     * @param format output image format (defaults to JPEG if null)
     * @return RenderedInstanceResult containing thumbnail image data
     */
    RenderedInstanceResult retrieveThumbnailFrame(UserI user, String projectId,
                                                  String studyInstanceUID, String seriesInstanceUID,
                                                  String sopInstanceUID, String frameList,
                                                  RenderingParams params, ImageFormat format);

    /**
     * Retrieve specific frame(s) from a DICOM instance
     * @param frameNumbers comma-separated list of frame numbers (1-based)
     * @return list of byte arrays, one per requested frame
     */
    List<byte[]> retrieveFrames(UserI user, String projectId, String studyInstanceUID, String seriesInstanceUID, String sopInstanceUID, String frameNumbers);

    /**
     * Store DICOM instances (STOW-RS)
     * @param user the authenticated user
     * @param projectId the target project
     * @param dicomInstances list of DICOM instance InputStreams
     * @return StowRsResponse containing success/failure status for each instance
     */
    StowRsResponse storeInstances(UserI user, String projectId, List<InputStream> dicomInstances);

    /**
     * Retrieve all bulk data elements from a single DICOM instance.
     * <p>
     * Returns every attribute whose value representation and size qualify it as bulk data
     * (per {@link BulkDataHandler#shouldUseBulkDataURI}), packaged as {@link BulkDataHandler.BulkDataItem}
     * objects with Content-Location URIs suitable for multipart response parts.
     *
     * @param user the authenticated user
     * @param projectId XNAT project identifier
     * @param studyUID Study Instance UID of the containing study
     * @param seriesUID Series Instance UID of the containing series
     * @param instanceUID SOP Instance UID of the instance
     * @param baseUri base URI for generating BulkDataURI Content-Location values
     *                (e.g. {@code "http://host/xapi/dicomweb/projects/P"})
     * @return list of bulk data items; empty if the instance contains no bulk data
     * @throws org.nrg.xnat.dicomweb.exceptions.ResourceNotFoundException if the instance is not found
     */
    List<BulkDataHandler.BulkDataItem> retrieveInstanceBulkData(UserI user, String projectId,
        String studyUID, String seriesUID, String instanceUID, String baseUri);

    /**
     * Retrieve all bulk data elements from every instance in a series.
     * <p>
     * Aggregates bulk data across all instances belonging to the specified series,
     * returning one {@link BulkDataHandler.BulkDataItem} per bulk data attribute per instance.
     *
     * @param user the authenticated user
     * @param projectId XNAT project identifier
     * @param studyUID Study Instance UID of the containing study
     * @param seriesUID Series Instance UID of the series to retrieve bulk data from
     * @param baseUri base URI for generating BulkDataURI Content-Location values
     * @return list of bulk data items aggregated across all instances in the series; empty if none found
     */
    List<BulkDataHandler.BulkDataItem> retrieveSeriesBulkData(UserI user, String projectId,
        String studyUID, String seriesUID, String baseUri);

    /**
     * Retrieve all bulk data elements from every instance in a study.
     * <p>
     * Aggregates bulk data across all series and instances belonging to the specified study,
     * returning one {@link BulkDataHandler.BulkDataItem} per bulk data attribute per instance.
     *
     * @param user the authenticated user
     * @param projectId XNAT project identifier
     * @param studyUID Study Instance UID of the study to retrieve bulk data from
     * @param baseUri base URI for generating BulkDataURI Content-Location values
     * @return list of bulk data items aggregated across all instances in the study; empty if none found
     */
    List<BulkDataHandler.BulkDataItem> retrieveStudyBulkData(UserI user, String projectId,
        String studyUID, String baseUri);

    /**
     * Retrieve pixel data from a single DICOM instance.
     * <p>
     * Returns only pixel data attributes: PixelData (7FE0,0010), FloatPixelData (7FE0,0008),
     * and DoubleFloatPixelData (7FE0,0009). This is a filtered subset of what
     * {@link #retrieveInstanceBulkData} returns.
     *
     * @param user the authenticated user
     * @param projectId XNAT project identifier
     * @param studyUID Study Instance UID of the containing study
     * @param seriesUID Series Instance UID of the containing series
     * @param instanceUID SOP Instance UID of the instance
     * @param baseUri base URI for generating BulkDataURI Content-Location values
     * @return list of pixel data items; empty if the instance contains no pixel data
     * @throws org.nrg.xnat.dicomweb.exceptions.ResourceNotFoundException if the instance is not found
     */
    List<BulkDataHandler.BulkDataItem> retrieveInstancePixelData(UserI user, String projectId,
        String studyUID, String seriesUID, String instanceUID, String baseUri);

    /**
     * Retrieve pixel data from every instance in a series.
     * <p>
     * Aggregates pixel data (PixelData, FloatPixelData, DoubleFloatPixelData) across all
     * instances belonging to the specified series.
     *
     * @param user the authenticated user
     * @param projectId XNAT project identifier
     * @param studyUID Study Instance UID of the containing study
     * @param seriesUID Series Instance UID of the series to retrieve pixel data from
     * @param baseUri base URI for generating BulkDataURI Content-Location values
     * @return list of pixel data items aggregated across all instances in the series; empty if none found
     */
    List<BulkDataHandler.BulkDataItem> retrieveSeriesPixelData(UserI user, String projectId,
        String studyUID, String seriesUID, String baseUri);

    /**
     * Retrieve pixel data from every instance in a study.
     * <p>
     * Aggregates pixel data (PixelData, FloatPixelData, DoubleFloatPixelData) across all
     * series and instances belonging to the specified study.
     *
     * @param user the authenticated user
     * @param projectId XNAT project identifier
     * @param studyUID Study Instance UID of the study to retrieve pixel data from
     * @param baseUri base URI for generating BulkDataURI Content-Location values
     * @return list of pixel data items aggregated across all instances in the study; empty if none found
     */
    List<BulkDataHandler.BulkDataItem> retrieveStudyPixelData(UserI user, String projectId,
        String studyUID, String baseUri);

    /**
     * STOW-RS response model
     */
    class StowRsResponse {
        private final int successCount;
        private final int failureCount;
        private final List<InstanceStatus> instanceStatuses;

        public StowRsResponse(int successCount, int failureCount, List<InstanceStatus> instanceStatuses) {
            this.successCount = successCount;
            this.failureCount = failureCount;
            this.instanceStatuses = instanceStatuses;
        }

        public int getSuccessCount() {
            return successCount;
        }

        public int getFailureCount() {
            return failureCount;
        }

        public List<InstanceStatus> getInstanceStatuses() {
            return instanceStatuses;
        }
    }

    /**
     * Status for individual instance in STOW-RS response
     */
    class InstanceStatus {
        private final String sopInstanceUID;
        private final String sopClassUID;
        private final boolean success;
        private final String errorMessage;
        private final int warningCode;

        public InstanceStatus(String sopInstanceUID, String sopClassUID, boolean success, String errorMessage, int warningCode) {
            this.sopInstanceUID = sopInstanceUID;
            this.sopClassUID = sopClassUID;
            this.success = success;
            this.errorMessage = errorMessage;
            this.warningCode = warningCode;
        }

        public String getSopInstanceUID() {
            return sopInstanceUID;
        }

        public String getSopClassUID() {
            return sopClassUID;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public int getWarningCode() {
            return warningCode;
        }
    }
}
