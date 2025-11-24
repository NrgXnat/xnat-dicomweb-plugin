/*
 * XNAT DICOMweb Proxy Plugin
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 */

package org.nrg.xnat.dicomweb.service;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Result object for STOW-RS store operation.
 * Contains the outcome of storing DICOM instances.
 */
public class StowRsResult {

    private final Set<String> prearchiveUris;
    private final Set<String> archiveUrls;
    private final String jsonResponse;
    private final int successCount;
    private final List<FailedInstance> failedInstances;

    public StowRsResult(Set<String> prearchiveUris, Set<String> archiveUrls,
                        String jsonResponse, int successCount, List<FailedInstance> failedInstances) {
        this.prearchiveUris = prearchiveUris;
        this.archiveUrls = archiveUrls;
        this.jsonResponse = jsonResponse;
        this.successCount = successCount;
        this.failedInstances = failedInstances != null ? failedInstances : Collections.emptyList();
    }

    public Set<String> getPrearchiveUris() {
        return prearchiveUris;
    }

    public Set<String> getArchiveUrls() {
        return archiveUrls;
    }

    public String getJsonResponse() {
        return jsonResponse;
    }

    public int getSuccessCount() {
        return successCount;
    }

    public int getFailureCount() {
        return failedInstances.size();
    }

    public List<FailedInstance> getFailedInstances() {
        return failedInstances;
    }

    public boolean hasSuccesses() {
        return successCount > 0;
    }

    public boolean hasFailures() {
        return !failedInstances.isEmpty();
    }
}
