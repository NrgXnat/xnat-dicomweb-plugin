/*
 * XNAT DICOMweb Plugin
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 */

package org.nrg.xnat.dicomweb.service;

import org.nrg.xft.security.UserI;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * Service interface for STOW-RS (STore Over the Web by RESTful Services) operations.
 * Handles the business logic for storing DICOM instances via DICOMweb.
 */
public interface StowRsService {

    /**
     * Store DICOM instances from a multipart/related request.
     *
     * @param user The authenticated user
     * @param params The STOW-RS parameters (project, subject, session, etc.)
     * @param request The HTTP request containing multipart/related DICOM data
     * @return StowRsResult containing the outcome of the store operation
     * @throws StowRsException if the store operation fails
     */
    StowRsResult storeInstances(UserI user, Map<String, Object> params, HttpServletRequest request)
            throws StowRsException;
}
