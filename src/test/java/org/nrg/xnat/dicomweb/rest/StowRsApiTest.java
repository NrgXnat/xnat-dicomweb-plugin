/*
 * XNAT DICOMweb Proxy Plugin
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */

package org.nrg.xnat.dicomweb.rest;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.dicomweb.service.StowRsService;

import javax.servlet.http.HttpServletRequest;

import static org.junit.Assert.*;

/**
 * Unit tests for StowRsApi
 */
public class StowRsApiTest {

    @Mock
    private UserManagementServiceI mockUserManagementService;

    @Mock
    private RoleHolder mockRoleHolder;

    @Mock
    private StowRsService mockStowRsService;

    @Mock
    private HttpServletRequest mockRequest;

    @Mock
    private UserI mockUser;

    private StowRsApi stowRsApi;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        stowRsApi = new StowRsApi(mockUserManagementService, mockRoleHolder, mockStowRsService);
    }

    // Tests removed - StowRsApi now uses XNAT's standard import pipeline (GradualDicomImporter)
    // instead of XnatDicomService. Integration tests should be used to verify the full workflow.

    @Test
    public void testCreateErrorResponse() throws Exception {
        // Use reflection to call private method
        java.lang.reflect.Method method = StowRsApi.class.getDeclaredMethod("createErrorResponse", String.class);
        method.setAccessible(true);

        String error1 = (String) method.invoke(stowRsApi, "Test error");
        String error2 = (String) method.invoke(stowRsApi, "Error with \"quotes\"");

        assertTrue(error1.contains("Test error"));
        assertTrue(error2.contains("Error with"));
        assertTrue(error2.contains("\\\"quotes\\\""));
    }
}
