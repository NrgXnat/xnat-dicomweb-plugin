/*
 * XNAT DICOMweb Proxy Plugin
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 */

package org.nrg.xnat.dicomweb.rest;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xnat.dicomweb.service.StowRsService;

import static org.junit.Assert.*;

/**
 * Unit tests for StowRsApi
 *
 * Note: Most STOW-RS functionality requires XNAT context and is tested via
 * integration tests in test-stowrs-suite.sh
 */
public class StowRsApiTest {

    @Mock
    private UserManagementServiceI mockUserManagementService;

    @Mock
    private RoleHolder mockRoleHolder;

    @Mock
    private StowRsService mockStowRsService;

    private StowRsApi stowRsApi;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        stowRsApi = new StowRsApi(mockUserManagementService, mockRoleHolder, mockStowRsService);
    }

    @Test
    public void testApiInstantiation() {
        // Verify the API can be instantiated with mock services
        assertNotNull("StowRsApi should be instantiated", stowRsApi);
    }

    // Note: Full STOW-RS tests require XNAT context and are covered by:
    // - test-stowrs-suite.sh (7 integration tests)
    // - StowRsServiceImplTest (grouping logic tests)
    // - SuccessfulInstanceTest
    // - FailedInstanceTest
    // - Mime4jHybridParserTest (multipart parsing)
}
