/*
 * XNAT DICOMweb Plugin
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 */

package org.nrg.xnat.dicomweb.service.impl.strategy;

import org.nrg.xft.security.UserI;
import org.nrg.xnat.dicomweb.parser.Mime4jHybridParser.MultipartPart;
import org.nrg.xnat.dicomweb.service.FailedInstance;
import org.nrg.xnat.dicomweb.service.StowRsImportResult;
import org.nrg.xnat.dicomweb.service.SuccessfulInstance;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Strategy interface for DICOM import operations.
 *
 * <p>Implementations provide different ways to import DICOM instances to XNAT,
 * with each strategy managing its own import logic and concurrent upload handling.
 *
 * <p><b>Architecture note</b>: Each strategy is responsible for:
 * <ul>
 *   <li>Parsing and validating DICOM instances</li>
 *   <li>Writing files to storage (prearchive or archive)</li>
 *   <li>Building/archiving sessions (strategy-specific timing)</li>
 *   <li>Managing concurrent uploads (strategy-specific mechanism)</li>
 * </ul>
 *
 * @since 1.0.0
 */
public interface DicomImportStrategy {

    /**
     * Get the name of this import strategy.
     * @return Strategy name for logging and configuration
     */
    String getName();

    /**
     * Import DICOM instances and return complete results.
     *
     * <p>This method handles the complete import process, including:
     * <ul>
     *   <li>Parsing and validating DICOM instances</li>
     *   <li>Writing files to storage</li>
     *   <li>Building/archiving sessions (if applicable)</li>
     *   <li>Managing concurrent uploads</li>
     * </ul>
     *
     * <p>Each strategy manages its own concurrent upload handling:
     * <ul>
     *   <li><b>GradualDicomImporter</b>: Uses import future tracking + debounce</li>
     *   <li><b>DirectArchive</b>: Uses per-study build locks</li>
     * </ul>
     *
     * @param user The authenticated user
     * @param parts The parsed multipart parts containing DICOM data
     * @param params Import parameters (projectId, strategy, etc.)
     * @param request HTTP request for building DICOMweb URLs
     * @return Complete import result with archive URLs and instance status
     * @since 1.2.0
     */
    StowRsImportResult importInstances(UserI user,
                                      List<MultipartPart> parts,
                                      Map<String, Object> params,
                                      HttpServletRequest request);

    /**
     * Import DICOM instances from multipart parts (legacy method).
     *
     * <p><b>Deprecated</b>: This method uses output parameters which couples
     * the strategy to the service layer. Use {@link #importInstances(UserI, List, Map, HttpServletRequest)}
     * instead, which returns a self-contained result object.
     *
     * <p>Default implementation: Calls the new method and populates output parameters.
     *
     * @param user The authenticated user
     * @param parts The parsed multipart parts containing DICOM data
     * @param params Import parameters (projectId, etc.)
     * @param sessionUris Output: collected session/experiment URIs for successful imports
     * @param successfulInstances Output: collected successful instance details (SOP UIDs, retrieve URLs)
     * @param failedInstances Output: collected failure information
     * @deprecated Use {@link #importInstances(UserI, List, Map, HttpServletRequest)} instead
     */
    @Deprecated
    default void importInstances(UserI user, List<MultipartPart> parts,
                                Map<String, Object> params,
                                Set<String> sessionUris,
                                List<SuccessfulInstance> successfulInstances,
                                List<FailedInstance> failedInstances) {
        // Default implementation: Call new method and populate output parameters
        StowRsImportResult result = importInstances(user, parts, params, null);
        sessionUris.addAll(result.getArchiveUrls());
        successfulInstances.addAll(result.getSuccessfulInstances());
        failedInstances.addAll(result.getFailedInstances());
    }
}
