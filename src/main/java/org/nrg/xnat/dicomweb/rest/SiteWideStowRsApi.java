/*
 * XNAT DICOMweb Plugin
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
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.dicomweb.service.SiteWideProjectFilter;
import org.nrg.xnat.dicomweb.service.StowRsException;
import org.nrg.xnat.dicomweb.service.StowRsResult;
import org.nrg.xnat.dicomweb.service.StowRsService;
import org.nrg.xnat.dicomweb.util.DicomWebUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

/**
 * Site-wide STOW-RS (STore Over the Web by RESTful Services) REST API.
 * Accepts DICOM uploads without a project in the URL. The project is determined
 * from the DICOM data using XNAT's DicomObjectIdentifier mechanism.
 */
@XapiRestController
@Api("DICOMweb Site-Wide STOW-RS API")
public class SiteWideStowRsApi extends AbstractXapiRestController {

    private static final Logger logger = LoggerFactory.getLogger(SiteWideStowRsApi.class);

    private final StowRsService stowRsService;
    private final SiteWideProjectFilter siteWideProjectFilter;

    @Autowired
    public SiteWideStowRsApi(final UserManagementServiceI userManagementService,
                             final RoleHolder roleHolder,
                             final StowRsService stowRsService,
                             final SiteWideProjectFilter siteWideProjectFilter) {
        super(userManagementService, roleHolder);
        this.stowRsService = stowRsService;
        this.siteWideProjectFilter = siteWideProjectFilter;
    }

    /**
     * Store DICOM instances (site-wide STOW-RS).
     * Project is determined from the DICOM data using XNAT's DicomObjectIdentifier.
     * If no project can be identified, falls back to GradualDicomImporter (prearchive).
     *
     * POST /dicomweb/studies
     *
     * @param request HTTP request containing multipart/related DICOM data
     * @return STOW-RS response in DICOM JSON format
     */
    @XapiRequestMapping(
        value = "/dicomweb/studies",
        method = RequestMethod.POST,
        produces = "application/dicom+json",
        consumes = "*/*"
    )
    @ApiOperation(value = "Store DICOM instances (site-wide STOW-RS)", response = String.class)
    @ApiResponses({
        @ApiResponse(code = 200, message = "Instances stored successfully"),
        @ApiResponse(code = 400, message = "Invalid request format"),
        @ApiResponse(code = 401, message = "Authentication required"),
        @ApiResponse(code = 404, message = "Site-wide DICOMweb is not enabled"),
        @ApiResponse(code = 500, message = "Internal server error")
    })
    public ResponseEntity<String> storeInstances(
            @RequestParam(required = false) Map<String, String> queryParams,
            HttpServletRequest request) throws StowRsException {

        if (!siteWideProjectFilter.isSiteWideEnabled()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        UserI user = getSessionUser();

        // No projectId — project will be determined from DICOM data
        Map<String, Object> params = new HashMap<>();
        if (queryParams != null) {
            params.putAll(queryParams);
            logger.debug("Site-wide STOW-RS query parameters: {}", queryParams);
        }

        StowRsResult result = stowRsService.storeInstances(user, params, request);

        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(DicomWebUtils.getDicomJsonContentType()))
            .body(result.getJsonResponse());
    }
}
