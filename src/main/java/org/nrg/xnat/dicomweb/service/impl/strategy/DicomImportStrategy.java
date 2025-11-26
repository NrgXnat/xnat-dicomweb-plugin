/*
 * XNAT DICOMweb Proxy Plugin
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 */

package org.nrg.xnat.dicomweb.service.impl.strategy;

import org.nrg.xft.security.UserI;
import org.nrg.xnat.dicomweb.parser.Mime4jHybridParser.MultipartPart;
import org.nrg.xnat.dicomweb.service.FailedInstance;
import org.nrg.xnat.dicomweb.service.SuccessfulInstance;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Strategy interface for DICOM import operations.
 * Implementations provide different ways to import DICOM instances to XNAT.
 */
public interface DicomImportStrategy {

    /**
     * Get the name of this import strategy.
     * @return Strategy name for logging and configuration
     */
    String getName();

    /**
     * Import DICOM instances from multipart parts.
     *
     * @param user The authenticated user
     * @param parts The parsed multipart parts containing DICOM data
     * @param params Import parameters (projectId, etc.)
     * @param prearchiveUris Output: collected prearchive URIs for successful imports
     * @param successfulInstances Output: collected successful instance details (SOP UIDs, retrieve URLs)
     * @param failedInstances Output: collected failure information
     */
    void importInstances(UserI user, List<MultipartPart> parts,
                         Map<String, Object> params,
                         Set<String> prearchiveUris,
                         List<SuccessfulInstance> successfulInstances,
                         List<FailedInstance> failedInstances);
}
