/*
 * XNAT DICOMweb Proxy Plugin
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 */

package org.nrg.xnat.dicomweb.service;

import java.util.List;
import java.util.Set;

/**
 * Result of DICOM import operation by a DicomImportStrategy.
 *
 * <p>This class encapsulates all outputs from the import process:
 * <ul>
 *   <li>Archive URLs - Final archive/experiment locations</li>
 *   <li>Successful instances - Successfully imported instances with metadata</li>
 *   <li>Failed instances - Failed instances with error details</li>
 * </ul>
 *
 * <p>Design rationale:
 * Each strategy manages its own import and concurrent control logic, and returns
 * a complete result through this class. This allows strategies to be independently
 * implemented and tested without coupling to the service layer.
 *
 * @since 1.2.0
 */
public class StowRsImportResult {

    private final Set<String> archiveUrls;
    private final List<SuccessfulInstance> successfulInstances;
    private final List<FailedInstance> failedInstances;

    /**
     * Construct an import result.
     *
     * @param archiveUrls Final archive/experiment URLs (e.g., /data/experiments/EXP123)
     * @param successfulInstances List of successfully imported instances
     * @param failedInstances List of failed instances with error details
     */
    public StowRsImportResult(Set<String> archiveUrls,
                             List<SuccessfulInstance> successfulInstances,
                             List<FailedInstance> failedInstances) {
        this.archiveUrls = archiveUrls;
        this.successfulInstances = successfulInstances;
        this.failedInstances = failedInstances;
    }

    /**
     * Get final archive URLs.
     *
     * <p>Format depends on strategy:
     * <ul>
     *   <li>GradualDicomImporter: /archive/projects/{project}/subjects/{subject}/experiments/{experiment}</li>
     *   <li>DirectArchive: /data/experiments/{experimentId}</li>
     * </ul>
     *
     * @return Set of archive URLs
     */
    public Set<String> getArchiveUrls() {
        return archiveUrls;
    }

    /**
     * Get successfully imported instances.
     *
     * @return List of successful instances with SOP UIDs and retrieve URLs
     */
    public List<SuccessfulInstance> getSuccessfulInstances() {
        return successfulInstances;
    }

    /**
     * Get failed instances.
     *
     * @return List of failed instances with error details
     */
    public List<FailedInstance> getFailedInstances() {
        return failedInstances;
    }

    /**
     * Check if import was completely successful (no failures).
     *
     * @return true if no failures occurred
     */
    public boolean isFullySuccessful() {
        return failedInstances.isEmpty();
    }

    /**
     * Get total number of instances processed.
     *
     * @return Total count of successful + failed instances
     */
    public int getTotalInstances() {
        return successfulInstances.size() + failedInstances.size();
    }

    @Override
    public String toString() {
        return String.format("StowRsImportResult{archives=%d, successful=%d, failed=%d}",
                archiveUrls.size(), successfulInstances.size(), failedInstances.size());
    }
}
