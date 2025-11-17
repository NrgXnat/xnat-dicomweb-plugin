package org.nrg.xnat.dicomweb.service;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.nrg.xft.security.UserI;

import java.io.InputStream;
import java.util.List;

/**
 * Service interface for accessing DICOM data from XNAT
 */
public interface XnatDicomService {

    /**
     * Search for studies in a project
     */
    List<Attributes> searchStudies(UserI user, String projectId, Attributes queryAttributes);

    /**
     * Search for series within a study
     */
    List<Attributes> searchSeries(UserI user, String projectId, String studyInstanceUID, Attributes queryAttributes);

    /**
     * Search for instances within a series
     */
    List<Attributes> searchInstances(UserI user, String projectId, String studyInstanceUID, String seriesInstanceUID, Attributes queryAttributes);

    /**
     * Retrieve a DICOM instance
     */
    InputStream retrieveInstance(UserI user, String projectId, String studyInstanceUID, String seriesInstanceUID, String sopInstanceUID);

    /**
     * Retrieve metadata for an instance
     */
    Attributes retrieveMetadata(UserI user, String projectId, String studyInstanceUID, String seriesInstanceUID, String sopInstanceUID);

    /**
     * Retrieve study-level metadata
     */
    Attributes retrieveStudyMetadata(UserI user, String projectId, String studyInstanceUID);

    /**
     * Retrieve metadata for all instances in a study
     */
    List<Attributes> retrieveAllStudyInstanceMetadata(UserI user, String projectId, String studyInstanceUID);

    /**
     * Retrieve all instances in a study
     */
    List<InputStream> retrieveStudy(UserI user, String projectId, String studyInstanceUID);

    /**
     * Retrieve all instances in a series
     */
    List<InputStream> retrieveSeries(UserI user, String projectId, String studyInstanceUID, String seriesInstanceUID);

    /**
     * Retrieve a rendered instance as JPEG
     */
    byte[] retrieveRenderedInstance(UserI user, String projectId, String studyInstanceUID, String seriesInstanceUID, String sopInstanceUID);

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
