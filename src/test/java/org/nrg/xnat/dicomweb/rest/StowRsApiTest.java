/*
 * XNAT DICOMweb Proxy Plugin
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */

package org.nrg.xnat.dicomweb.rest;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.dicomweb.service.XnatDicomService;
import org.nrg.xnat.dicomweb.service.XnatDicomService.StowRsResponse;
import org.nrg.xnat.dicomweb.service.XnatDicomService.InstanceStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for StowRsApi
 */
public class StowRsApiTest {

    @Mock
    private XnatDicomService mockDicomService;

    @Mock
    private UserManagementServiceI mockUserManagementService;

    @Mock
    private RoleHolder mockRoleHolder;

    @Mock
    private HttpServletRequest mockRequest;

    @Mock
    private UserI mockUser;

    private StowRsApi stowRsApi;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        stowRsApi = new StowRsApi(mockDicomService, mockUserManagementService, mockRoleHolder);
    }

    @Test
    public void testBoundaryExtraction() throws Exception {
        // Test boundary extraction from Content-Type header
        String contentType1 = "multipart/related; boundary=----boundary123";
        String contentType2 = "multipart/related; boundary=\"----boundary456\"";
        String contentType3 = "multipart/related; type=\"application/dicom\"; boundary=myboundary";

        // Use reflection to call private method for testing
        java.lang.reflect.Method method = StowRsApi.class.getDeclaredMethod("extractBoundary", String.class);
        method.setAccessible(true);

        String boundary1 = (String) method.invoke(stowRsApi, contentType1);
        String boundary2 = (String) method.invoke(stowRsApi, contentType2);
        String boundary3 = (String) method.invoke(stowRsApi, contentType3);

        assertEquals("----boundary123", boundary1);
        assertEquals("----boundary456", boundary2);
        assertEquals("myboundary", boundary3);
    }

    @Test
    public void testStowRsResponseBuilding() throws Exception {
        // Create a response with successes and failures
        List<InstanceStatus> statuses = new ArrayList<>();
        statuses.add(new InstanceStatus(
            "1.2.3.4.5", "1.2.840.10008.5.1.4.1.1.2", true, null, 0));
        statuses.add(new InstanceStatus(
            "1.2.3.4.6", "1.2.840.10008.5.1.4.1.1.2", false, "Missing UID", 0xA900));

        StowRsResponse response = new StowRsResponse(1, 1, statuses);

        // Verify response counts
        assertEquals(1, response.getSuccessCount());
        assertEquals(1, response.getFailureCount());
        assertEquals(2, response.getInstanceStatuses().size());

        // Verify individual statuses
        InstanceStatus success = response.getInstanceStatuses().get(0);
        assertTrue(success.isSuccess());
        assertEquals("1.2.3.4.5", success.getSopInstanceUID());
        assertNull(success.getErrorMessage());

        InstanceStatus failure = response.getInstanceStatuses().get(1);
        assertFalse(failure.isSuccess());
        assertEquals("1.2.3.4.6", failure.getSopInstanceUID());
        assertEquals("Missing UID", failure.getErrorMessage());
        assertEquals(0xA900, failure.getWarningCode());
    }

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

    @Test
    public void testInstanceStatusCreation() {
        // Test successful instance status
        InstanceStatus success = new InstanceStatus(
            "1.2.3.4.5",
            "1.2.840.10008.5.1.4.1.1.2",
            true,
            null,
            0
        );

        assertTrue(success.isSuccess());
        assertEquals("1.2.3.4.5", success.getSopInstanceUID());
        assertEquals("1.2.840.10008.5.1.4.1.1.2", success.getSopClassUID());
        assertNull(success.getErrorMessage());
        assertEquals(0, success.getWarningCode());

        // Test failed instance status
        InstanceStatus failure = new InstanceStatus(
            "1.2.3.4.6",
            "1.2.840.10008.5.1.4.1.1.2",
            false,
            "Missing required attributes",
            0xA900
        );

        assertFalse(failure.isSuccess());
        assertEquals("1.2.3.4.6", failure.getSopInstanceUID());
        assertEquals("Missing required attributes", failure.getErrorMessage());
        assertEquals(0xA900, failure.getWarningCode());
    }

    @Test
    public void testStowRsResponseAggregation() {
        // Create response with multiple instances
        List<InstanceStatus> statuses = new ArrayList<>();

        // Add 5 successful instances
        for (int i = 0; i < 5; i++) {
            statuses.add(new InstanceStatus(
                "1.2.3.4." + i,
                "1.2.840.10008.5.1.4.1.1.2",
                true,
                null,
                0
            ));
        }

        // Add 2 failed instances
        for (int i = 5; i < 7; i++) {
            statuses.add(new InstanceStatus(
                "1.2.3.4." + i,
                "1.2.840.10008.5.1.4.1.1.2",
                false,
                "Error " + i,
                0xC000
            ));
        }

        StowRsResponse response = new StowRsResponse(5, 2, statuses);

        assertEquals(5, response.getSuccessCount());
        assertEquals(2, response.getFailureCount());
        assertEquals(7, response.getInstanceStatuses().size());

        // Count successes and failures
        int successCount = 0;
        int failureCount = 0;
        for (InstanceStatus status : response.getInstanceStatuses()) {
            if (status.isSuccess()) {
                successCount++;
            } else {
                failureCount++;
            }
        }

        assertEquals(5, successCount);
        assertEquals(2, failureCount);
    }
}
