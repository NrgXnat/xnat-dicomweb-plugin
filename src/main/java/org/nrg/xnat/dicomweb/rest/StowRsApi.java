/*
 * XNAT DICOMweb Proxy Plugin
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 */

package org.nrg.xnat.dicomweb.rest;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import org.nrg.framework.annotations.XapiRestController;
import org.nrg.xapi.rest.AbstractXapiRestController;
import org.nrg.xapi.rest.XapiRequestMapping;
import org.nrg.xdat.om.XnatProjectdata;
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.dicomweb.exceptions.ForbiddenException;
import org.nrg.xnat.dicomweb.service.StowRsException;
import org.nrg.xnat.dicomweb.service.StowRsResult;
import org.nrg.xnat.dicomweb.service.StowRsService;
import org.nrg.xnat.dicomweb.utils.DicomWebUtils;
import org.nrg.xnat.helpers.uri.URIManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

/**
 * STOW-RS (STore Over the Web by RESTful Services) REST API.
 * Implements DICOMweb storage endpoints.
 *
 * This controller handles HTTP request/response only.
 * Business logic is delegated to StowRsService.
 */
@XapiRestController
@Api("DICOMweb STOW-RS API")
public class StowRsApi extends AbstractXapiRestController {

    private static final Logger logger = LoggerFactory.getLogger(StowRsApi.class);

    private final StowRsService stowRsService;

    @Autowired
    public StowRsApi(final UserManagementServiceI userManagementService,
                     final RoleHolder roleHolder,
                     final StowRsService stowRsService) {
        super(userManagementService, roleHolder);
        this.stowRsService = stowRsService;
    }

    /**
     * Store DICOM instances (STOW-RS)
     * POST /dicomweb/projects/{projectId}/studies
     *
     * @param projectId XNAT project ID
     * @param request HTTP request containing multipart/related DICOM data
     * @return STOW-RS response in DICOM JSON format
     */
    @XapiRequestMapping(
        value = "/dicomweb/projects/{projectId}/studies",
        method = RequestMethod.POST,
        produces = "application/dicom+json",
        consumes = "*/*"
    )
    @ApiOperation(value = "Store DICOM instances (STOW-RS)", response = String.class)
    @ApiResponses({
        @ApiResponse(code = 200, message = "Instances stored successfully"),
        @ApiResponse(code = 400, message = "Invalid request format"),
        @ApiResponse(code = 401, message = "Authentication required"),
        @ApiResponse(code = 403, message = "Insufficient permissions"),
        @ApiResponse(code = 500, message = "Internal server error")
    })
    public ResponseEntity<String> storeInstances(
            @PathVariable String projectId,
            @RequestParam(required = false) Map<String, String> queryParams,
            HttpServletRequest request) throws StowRsException {

        UserI user = getSessionUser();

        // Verify project exists and user has access
        XnatProjectdata project = XnatProjectdata.getXnatProjectdatasById(projectId, user, false);
        if (project == null) {
            logger.error("Project '{}' not found or user '{}' does not have access", projectId, user.getLogin());
            throw new ForbiddenException("Project", projectId);
        }

        // Build params from path variables and query parameters
        Map<String, Object> params = new HashMap<>();
        params.put(URIManager.PROJECT_ID, projectId);

        // Add query parameters (including strategy) to params
        if (queryParams != null) {
            params.putAll(queryParams);
            logger.debug("Query parameters: {}", queryParams);
        }

        // Store instances using selected strategy
        StowRsResult result = stowRsService.storeInstances(user, params, request);

        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(DicomWebUtils.getDicomJsonContentType()))
            .body(result.getJsonResponse());
    }
}
