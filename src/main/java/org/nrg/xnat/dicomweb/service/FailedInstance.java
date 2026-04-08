/*
 * XNAT DICOMweb Plugin
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 */

package org.nrg.xnat.dicomweb.service;

/**
 * Represents a failed DICOM instance during STOW-RS operation.
 * Contains information needed for the FailedSOPSequence in the response.
 */
public class FailedInstance {

    /**
     * DICOM failure reason codes (per PS3.18 Table 10.5.1-1)
     * Reference: DICOM PS3.18 Section 10.5 STOW-RS Response
     */
    public static final int PROCESSING_FAILURE = 0x0110;           // 272 - General processing failure
    public static final int NO_SUCH_OBJECT_INSTANCE = 0x0111;      // 273 - No such object instance
    public static final int INVALID_OBJECT_INSTANCE = 0x0117;      // 279 - Invalid object instance
    public static final int OUT_OF_RESOURCES = 0xA700;             // 42752 - Out of resources
    public static final int DATA_SET_DOES_NOT_MATCH_SOP_CLASS = 0xA900; // 43264 - Data set does not match SOP class
    public static final int CANNOT_UNDERSTAND = 0xC000;            // 49152 - Cannot understand (generic)

    private final int instanceIndex;
    private final String sopClassUid;
    private final String sopInstanceUid;
    private final String studyInstanceUid;
    private final int failureReason;
    private final String errorMessage;

    /**
     * Create a failed instance with full details including study UID
     */
    public FailedInstance(int instanceIndex, String sopClassUid, String sopInstanceUid,
                          String studyInstanceUid, int failureReason, String errorMessage) {
        this.instanceIndex = instanceIndex;
        this.sopClassUid = sopClassUid;
        this.sopInstanceUid = sopInstanceUid;
        this.studyInstanceUid = studyInstanceUid;
        this.failureReason = failureReason;
        this.errorMessage = errorMessage;
    }

    /**
     * Create a failed instance with full details (without study UID - for backward compatibility)
     */
    public FailedInstance(int instanceIndex, String sopClassUid, String sopInstanceUid,
                          int failureReason, String errorMessage) {
        this(instanceIndex, sopClassUid, sopInstanceUid, null, failureReason, errorMessage);
    }

    /**
     * Create a failed instance when SOP UIDs are not available (e.g., non-DICOM file)
     */
    public FailedInstance(int instanceIndex, int failureReason, String errorMessage) {
        this(instanceIndex, null, null, null, failureReason, errorMessage);
    }

    /**
     * Create a failed instance with default processing failure reason
     */
    public static FailedInstance processingFailure(int instanceIndex, String errorMessage) {
        return new FailedInstance(instanceIndex, PROCESSING_FAILURE, errorMessage);
    }

    /**
     * Create a failed instance for non-DICOM content
     */
    public static FailedInstance cannotUnderstand(int instanceIndex, String errorMessage) {
        return new FailedInstance(instanceIndex, CANNOT_UNDERSTAND, errorMessage);
    }

    public int getInstanceIndex() {
        return instanceIndex;
    }

    public String getSopClassUid() {
        return sopClassUid;
    }

    public String getSopInstanceUid() {
        return sopInstanceUid;
    }

    public String getStudyInstanceUid() {
        return studyInstanceUid;
    }

    public int getFailureReason() {
        return failureReason;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public boolean hasSopUids() {
        return sopClassUid != null && sopInstanceUid != null;
    }

    @Override
    public String toString() {
        return String.format("FailedInstance[index=%d, reason=0x%04X, message=%s]",
            instanceIndex, failureReason, errorMessage);
    }
}
