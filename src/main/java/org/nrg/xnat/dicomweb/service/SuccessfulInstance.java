/*
 * XNAT DICOMweb Proxy Plugin
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 */

package org.nrg.xnat.dicomweb.service;

/**
 * Represents a successfully stored DICOM instance.
 * Used to build STOW-RS ReferencedSOPSequence response.
 */
public class SuccessfulInstance {
    private final String sopClassUid;
    private final String sopInstanceUid;
    private final String studyInstanceUid;
    private final String seriesInstanceUid;
    private final String retrieveUrl;

    public SuccessfulInstance(String sopClassUid, String sopInstanceUid, String retrieveUrl) {
        this(sopClassUid, sopInstanceUid, null, null, retrieveUrl);
    }

    public SuccessfulInstance(String sopClassUid, String sopInstanceUid,
                             String studyInstanceUid, String seriesInstanceUid,
                             String retrieveUrl) {
        this.sopClassUid = sopClassUid;
        this.sopInstanceUid = sopInstanceUid;
        this.studyInstanceUid = studyInstanceUid;
        this.seriesInstanceUid = seriesInstanceUid;
        this.retrieveUrl = retrieveUrl;
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

    public String getSeriesInstanceUid() {
        return seriesInstanceUid;
    }

    public String getRetrieveUrl() {
        return retrieveUrl;
    }

    @Override
    public String toString() {
        return "SuccessfulInstance{" +
                "sopClassUid='" + sopClassUid + '\'' +
                ", sopInstanceUid='" + sopInstanceUid + '\'' +
                ", studyInstanceUid='" + studyInstanceUid + '\'' +
                ", seriesInstanceUid='" + seriesInstanceUid + '\'' +
                ", retrieveUrl='" + retrieveUrl + '\'' +
                '}';
    }
}
