/*
 * XNAT DICOMweb Plugin
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.dicomweb.service.XnatDicomService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for QidoRsApi query parameter filtering
 */
public class QidoRsApiTest {

    @Mock
    private XnatDicomService mockDicomService;

    @Mock
    private UserManagementServiceI mockUserManagementService;

    @Mock
    private RoleHolder mockRoleHolder;

    @Mock
    private UserI mockUser;

    @Mock
    private org.nrg.xnat.dicomweb.config.DicomWebProperties mockProperties;

    @Mock
    private org.nrg.xnat.dicomweb.config.DicomWebProperties.PaginationConfig mockPaginationConfig;

    private QidoRsApi qidoRsApi;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        // Setup mock properties with default values
        when(mockProperties.getPagination()).thenReturn(mockPaginationConfig);
        when(mockPaginationConfig.getDefaultPageSize()).thenReturn(100);
        when(mockPaginationConfig.getMaxPageSize()).thenReturn(1000);

        qidoRsApi = new QidoRsApi(mockDicomService, mockProperties, mockUserManagementService, mockRoleHolder) {
            @Override
            protected UserI getSessionUser() {
                return mockUser;
            }
        };
    }

    // ========== Study Search Tests ==========

    @Test
    public void testSearchStudies_NoQueryParameters() {
        // Arrange
        String projectId = "TestProject";
        List<Attributes> mockStudies = createMockStudies(2);

        when(mockDicomService.searchStudies(any(UserI.class), eq(projectId), any(Attributes.class)))
            .thenReturn(mockStudies);

        // Act
        ResponseEntity<String> response = qidoRsApi.searchStudies(projectId, null);

        // Assert
        assertEquals("Should return 200 OK", HttpStatus.OK, response.getStatusCode());
        assertNotNull("Response body should not be null", response.getBody());
        assertTrue("Response should be JSON array", response.getBody().startsWith("["));
    }

    @Test
    public void testSearchStudies_WithPatientName() {
        // Arrange
        String projectId = "TestProject";
        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("PatientName", "John*");

        List<Attributes> mockStudies = createMockStudies(1);

        ArgumentCaptor<Attributes> queryCaptor = ArgumentCaptor.forClass(Attributes.class);
        when(mockDicomService.searchStudies(any(UserI.class), eq(projectId), queryCaptor.capture()))
            .thenReturn(mockStudies);

        // Act
        ResponseEntity<String> response = qidoRsApi.searchStudies(projectId, queryParams);

        // Assert
        assertEquals("Should return 200 OK", HttpStatus.OK, response.getStatusCode());

        Attributes capturedQuery = queryCaptor.getValue();
        assertNotNull("Query attributes should not be null", capturedQuery);
        assertEquals("Should contain PatientName query", "John*", capturedQuery.getString(Tag.PatientName));
    }

    @Test
    public void testSearchStudies_WithModality() {
        // Arrange
        String projectId = "TestProject";
        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("Modality", "MR");

        List<Attributes> mockStudies = createMockStudies(1);

        ArgumentCaptor<Attributes> queryCaptor = ArgumentCaptor.forClass(Attributes.class);
        when(mockDicomService.searchStudies(any(UserI.class), eq(projectId), queryCaptor.capture()))
            .thenReturn(mockStudies);

        // Act
        ResponseEntity<String> response = qidoRsApi.searchStudies(projectId, queryParams);

        // Assert
        assertEquals("Should return 200 OK", HttpStatus.OK, response.getStatusCode());

        Attributes capturedQuery = queryCaptor.getValue();
        assertEquals("Should contain Modality query", "MR", capturedQuery.getString(Tag.Modality));
    }

    @Test
    public void testSearchStudies_WithStudyDate() {
        // Arrange
        String projectId = "TestProject";
        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("StudyDate", "20250101-20250131");

        List<Attributes> mockStudies = createMockStudies(1);

        ArgumentCaptor<Attributes> queryCaptor = ArgumentCaptor.forClass(Attributes.class);
        when(mockDicomService.searchStudies(any(UserI.class), eq(projectId), queryCaptor.capture()))
            .thenReturn(mockStudies);

        // Act
        ResponseEntity<String> response = qidoRsApi.searchStudies(projectId, queryParams);

        // Assert
        assertEquals("Should return 200 OK", HttpStatus.OK, response.getStatusCode());

        Attributes capturedQuery = queryCaptor.getValue();
        assertEquals("Should contain StudyDate range", "20250101-20250131", capturedQuery.getString(Tag.StudyDate));
    }

    @Test
    public void testSearchStudies_MultipleQueryParameters() {
        // Arrange
        String projectId = "TestProject";
        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("PatientName", "John Doe");
        queryParams.put("Modality", "CT");
        queryParams.put("StudyDate", "20250115");
        queryParams.put("AccessionNumber", "ACC123");

        List<Attributes> mockStudies = createMockStudies(1);

        ArgumentCaptor<Attributes> queryCaptor = ArgumentCaptor.forClass(Attributes.class);
        when(mockDicomService.searchStudies(any(UserI.class), eq(projectId), queryCaptor.capture()))
            .thenReturn(mockStudies);

        // Act
        ResponseEntity<String> response = qidoRsApi.searchStudies(projectId, queryParams);

        // Assert
        assertEquals("Should return 200 OK", HttpStatus.OK, response.getStatusCode());

        Attributes capturedQuery = queryCaptor.getValue();
        assertEquals("Should contain PatientName", "John Doe", capturedQuery.getString(Tag.PatientName));
        assertEquals("Should contain Modality", "CT", capturedQuery.getString(Tag.Modality));
        assertEquals("Should contain StudyDate", "20250115", capturedQuery.getString(Tag.StudyDate));
        assertEquals("Should contain AccessionNumber", "ACC123", capturedQuery.getString(Tag.AccessionNumber));
    }

    @Test
    public void testSearchStudies_EmptyResults() {
        // Arrange
        String projectId = "TestProject";
        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("PatientName", "NonExistent");

        when(mockDicomService.searchStudies(any(UserI.class), eq(projectId), any(Attributes.class)))
            .thenReturn(new ArrayList<>());

        // Act
        ResponseEntity<String> response = qidoRsApi.searchStudies(projectId, queryParams);

        // Assert
        assertEquals("Should return 200 OK with empty array", HttpStatus.OK, response.getStatusCode());
        assertEquals("Should return empty JSON array", "[]", response.getBody());
    }

    // ========== Series Search Tests ==========

    @Test
    public void testSearchSeries_NoQueryParameters() {
        // Arrange
        String projectId = "TestProject";
        String studyUID = "1.2.3.4.5";
        List<Attributes> mockSeries = createMockSeries(2);

        when(mockDicomService.searchSeries(any(UserI.class), eq(projectId), eq(studyUID), any(Attributes.class)))
            .thenReturn(mockSeries);

        // Act
        ResponseEntity<String> response = qidoRsApi.searchSeries(projectId, studyUID, null);

        // Assert
        assertEquals("Should return 200 OK", HttpStatus.OK, response.getStatusCode());
        assertNotNull("Response body should not be null", response.getBody());
    }

    @Test
    public void testSearchSeries_WithModality() {
        // Arrange
        String projectId = "TestProject";
        String studyUID = "1.2.3.4.5";
        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("Modality", "MR");

        List<Attributes> mockSeries = createMockSeries(1);

        ArgumentCaptor<Attributes> queryCaptor = ArgumentCaptor.forClass(Attributes.class);
        when(mockDicomService.searchSeries(any(UserI.class), eq(projectId), eq(studyUID), queryCaptor.capture()))
            .thenReturn(mockSeries);

        // Act
        ResponseEntity<String> response = qidoRsApi.searchSeries(projectId, studyUID, queryParams);

        // Assert
        assertEquals("Should return 200 OK", HttpStatus.OK, response.getStatusCode());

        Attributes capturedQuery = queryCaptor.getValue();
        assertEquals("Should contain Modality query", "MR", capturedQuery.getString(Tag.Modality));
    }

    @Test
    public void testSearchSeries_WithSeriesDescription() {
        // Arrange
        String projectId = "TestProject";
        String studyUID = "1.2.3.4.5";
        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("SeriesDescription", "*T1*");

        List<Attributes> mockSeries = createMockSeries(1);

        ArgumentCaptor<Attributes> queryCaptor = ArgumentCaptor.forClass(Attributes.class);
        when(mockDicomService.searchSeries(any(UserI.class), eq(projectId), eq(studyUID), queryCaptor.capture()))
            .thenReturn(mockSeries);

        // Act
        ResponseEntity<String> response = qidoRsApi.searchSeries(projectId, studyUID, queryParams);

        // Assert
        assertEquals("Should return 200 OK", HttpStatus.OK, response.getStatusCode());

        Attributes capturedQuery = queryCaptor.getValue();
        assertEquals("Should contain SeriesDescription", "*T1*", capturedQuery.getString(Tag.SeriesDescription));
    }

    @Test
    public void testSearchSeries_WithSeriesNumber() {
        // Arrange
        String projectId = "TestProject";
        String studyUID = "1.2.3.4.5";
        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("SeriesNumber", "1");

        List<Attributes> mockSeries = createMockSeries(1);

        ArgumentCaptor<Attributes> queryCaptor = ArgumentCaptor.forClass(Attributes.class);
        when(mockDicomService.searchSeries(any(UserI.class), eq(projectId), eq(studyUID), queryCaptor.capture()))
            .thenReturn(mockSeries);

        // Act
        ResponseEntity<String> response = qidoRsApi.searchSeries(projectId, studyUID, queryParams);

        // Assert
        assertEquals("Should return 200 OK", HttpStatus.OK, response.getStatusCode());

        Attributes capturedQuery = queryCaptor.getValue();
        assertEquals("Should contain SeriesNumber", "1", capturedQuery.getString(Tag.SeriesNumber));
    }

    // ========== Instance Search Tests ==========

    @Test
    public void testSearchInstances_NoQueryParameters() {
        // Arrange
        String projectId = "TestProject";
        String studyUID = "1.2.3.4.5";
        String seriesUID = "1.2.3.4.5.100";
        List<Attributes> mockInstances = createMockInstances(3);

        when(mockDicomService.searchInstances(any(UserI.class), eq(projectId), eq(studyUID), eq(seriesUID), any(Attributes.class)))
            .thenReturn(mockInstances);

        // Act
        ResponseEntity<String> response = qidoRsApi.searchInstances(projectId, studyUID, seriesUID, null);

        // Assert
        assertEquals("Should return 200 OK", HttpStatus.OK, response.getStatusCode());
        assertNotNull("Response body should not be null", response.getBody());
    }

    @Test
    public void testSearchInstances_WithSOPInstanceUID() {
        // Arrange
        String projectId = "TestProject";
        String studyUID = "1.2.3.4.5";
        String seriesUID = "1.2.3.4.5.100";
        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("SOPInstanceUID", "1.2.3.4.5.6.1");

        List<Attributes> mockInstances = createMockInstances(1);

        ArgumentCaptor<Attributes> queryCaptor = ArgumentCaptor.forClass(Attributes.class);
        when(mockDicomService.searchInstances(any(UserI.class), eq(projectId), eq(studyUID), eq(seriesUID), queryCaptor.capture()))
            .thenReturn(mockInstances);

        // Act
        ResponseEntity<String> response = qidoRsApi.searchInstances(projectId, studyUID, seriesUID, queryParams);

        // Assert
        assertEquals("Should return 200 OK", HttpStatus.OK, response.getStatusCode());

        Attributes capturedQuery = queryCaptor.getValue();
        assertEquals("Should contain SOPInstanceUID", "1.2.3.4.5.6.1", capturedQuery.getString(Tag.SOPInstanceUID));
    }

    @Test
    public void testSearchInstances_WithInstanceNumber() {
        // Arrange
        String projectId = "TestProject";
        String studyUID = "1.2.3.4.5";
        String seriesUID = "1.2.3.4.5.100";
        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("InstanceNumber", "1");

        List<Attributes> mockInstances = createMockInstances(1);

        ArgumentCaptor<Attributes> queryCaptor = ArgumentCaptor.forClass(Attributes.class);
        when(mockDicomService.searchInstances(any(UserI.class), eq(projectId), eq(studyUID), eq(seriesUID), queryCaptor.capture()))
            .thenReturn(mockInstances);

        // Act
        ResponseEntity<String> response = qidoRsApi.searchInstances(projectId, studyUID, seriesUID, queryParams);

        // Assert
        assertEquals("Should return 200 OK", HttpStatus.OK, response.getStatusCode());

        Attributes capturedQuery = queryCaptor.getValue();
        assertEquals("Should contain InstanceNumber", "1", capturedQuery.getString(Tag.InstanceNumber));
    }

    // ========== Edge Case Tests ==========

    @Test
    public void testSearchStudies_EmptyQueryParameterMap() {
        // Arrange
        String projectId = "TestProject";
        Map<String, String> queryParams = new HashMap<>();

        List<Attributes> mockStudies = createMockStudies(2);

        when(mockDicomService.searchStudies(any(UserI.class), eq(projectId), any(Attributes.class)))
            .thenReturn(mockStudies);

        // Act
        ResponseEntity<String> response = qidoRsApi.searchStudies(projectId, queryParams);

        // Assert
        assertEquals("Should return 200 OK", HttpStatus.OK, response.getStatusCode());
    }

    @Test
    public void testSearchStudies_UnsupportedQueryParameter() {
        // Arrange
        String projectId = "TestProject";
        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("UnsupportedParam", "value");

        List<Attributes> mockStudies = createMockStudies(2);

        ArgumentCaptor<Attributes> queryCaptor = ArgumentCaptor.forClass(Attributes.class);
        when(mockDicomService.searchStudies(any(UserI.class), eq(projectId), queryCaptor.capture()))
            .thenReturn(mockStudies);

        // Act
        ResponseEntity<String> response = qidoRsApi.searchStudies(projectId, queryParams);

        // Assert
        assertEquals("Should return 200 OK", HttpStatus.OK, response.getStatusCode());

        Attributes capturedQuery = queryCaptor.getValue();
        assertEquals("Query should be empty for unsupported params", 0, capturedQuery.size());
    }

    @Test
    public void testSearchStudies_EmptyQueryParameterValue() {
        // Arrange
        String projectId = "TestProject";
        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("PatientName", "");

        List<Attributes> mockStudies = createMockStudies(2);

        ArgumentCaptor<Attributes> queryCaptor = ArgumentCaptor.forClass(Attributes.class);
        when(mockDicomService.searchStudies(any(UserI.class), eq(projectId), queryCaptor.capture()))
            .thenReturn(mockStudies);

        // Act
        ResponseEntity<String> response = qidoRsApi.searchStudies(projectId, queryParams);

        // Assert
        assertEquals("Should return 200 OK", HttpStatus.OK, response.getStatusCode());

        Attributes capturedQuery = queryCaptor.getValue();
        assertEquals("Empty values should be ignored", 0, capturedQuery.size());
    }

    @Test
    public void testSearchStudies_CaseInsensitiveParameterNames() {
        // Arrange
        String projectId = "TestProject";
        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("patientname", "John");  // lowercase
        queryParams.put("MODALITY", "MR");        // uppercase

        List<Attributes> mockStudies = createMockStudies(1);

        ArgumentCaptor<Attributes> queryCaptor = ArgumentCaptor.forClass(Attributes.class);
        when(mockDicomService.searchStudies(any(UserI.class), eq(projectId), queryCaptor.capture()))
            .thenReturn(mockStudies);

        // Act
        ResponseEntity<String> response = qidoRsApi.searchStudies(projectId, queryParams);

        // Assert
        assertEquals("Should return 200 OK", HttpStatus.OK, response.getStatusCode());

        Attributes capturedQuery = queryCaptor.getValue();
        assertEquals("Should parse lowercase parameter names", "John", capturedQuery.getString(Tag.PatientName));
        assertEquals("Should parse uppercase parameter names", "MR", capturedQuery.getString(Tag.Modality));
    }

    // ========== Pagination Tests ==========

    @Test
    public void testSearchStudies_WithLimitParameter() {
        // Arrange
        String projectId = "TestProject";
        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("limit", "2");

        List<Attributes> mockStudies = createMockStudies(5);

        when(mockDicomService.searchStudies(any(UserI.class), eq(projectId), any(Attributes.class)))
            .thenReturn(mockStudies);

        // Act
        ResponseEntity<String> response = qidoRsApi.searchStudies(projectId, queryParams);

        // Assert
        assertEquals("Should return 200 OK", HttpStatus.OK, response.getStatusCode());
        assertNotNull("Response body should not be null", response.getBody());

        // Count StudyInstanceUIDs in response (should be limited to 2)
        int studyCount = countOccurrences(response.getBody(), "\"0020000D\"");
        assertEquals("Should return only 2 studies due to limit", 2, studyCount);

        // Verify X-Total-Count header shows total (5)
        assertEquals("X-Total-Count should show total count", "5",
                response.getHeaders().getFirst("X-Total-Count"));
    }

    @Test
    public void testSearchStudies_WithOffsetParameter() {
        // Arrange
        String projectId = "TestProject";
        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("offset", "2");
        queryParams.put("limit", "10");

        List<Attributes> mockStudies = createMockStudies(5);

        when(mockDicomService.searchStudies(any(UserI.class), eq(projectId), any(Attributes.class)))
            .thenReturn(mockStudies);

        // Act
        ResponseEntity<String> response = qidoRsApi.searchStudies(projectId, queryParams);

        // Assert
        assertEquals("Should return 200 OK", HttpStatus.OK, response.getStatusCode());

        // Count StudyInstanceUIDs (should be 3: indices 2,3,4)
        int studyCount = countOccurrences(response.getBody(), "\"0020000D\"");
        assertEquals("Should return 3 studies after offset", 3, studyCount);

        // Verify response contains studies starting from index 2
        assertTrue("Should contain study at index 2", response.getBody().contains("1.2.3.4.5.2"));
        assertFalse("Should NOT contain study at index 0", response.getBody().contains("1.2.3.4.5.0"));
    }

    @Test
    public void testSearchStudies_LimitExceedsMax() {
        // Arrange
        String projectId = "TestProject";
        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("limit", "9999"); // Exceeds max (1000)

        List<Attributes> mockStudies = createMockStudies(5);

        when(mockDicomService.searchStudies(any(UserI.class), eq(projectId), any(Attributes.class)))
            .thenReturn(mockStudies);

        // Act
        ResponseEntity<String> response = qidoRsApi.searchStudies(projectId, queryParams);

        // Assert - should succeed (limit capped to max)
        assertEquals("Should return 200 OK", HttpStatus.OK, response.getStatusCode());
    }

    @Test
    public void testSearchStudies_InvalidLimitValue() {
        // Arrange
        String projectId = "TestProject";
        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("limit", "invalid");

        List<Attributes> mockStudies = createMockStudies(5);

        when(mockDicomService.searchStudies(any(UserI.class), eq(projectId), any(Attributes.class)))
            .thenReturn(mockStudies);

        // Act
        ResponseEntity<String> response = qidoRsApi.searchStudies(projectId, queryParams);

        // Assert - should succeed with default limit
        assertEquals("Should return 200 OK with default limit", HttpStatus.OK, response.getStatusCode());
    }

    @Test
    public void testSearchStudies_NegativeOffset() {
        // Arrange
        String projectId = "TestProject";
        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("offset", "-5");

        List<Attributes> mockStudies = createMockStudies(5);

        when(mockDicomService.searchStudies(any(UserI.class), eq(projectId), any(Attributes.class)))
            .thenReturn(mockStudies);

        // Act
        ResponseEntity<String> response = qidoRsApi.searchStudies(projectId, queryParams);

        // Assert - should succeed with offset = 0
        assertEquals("Should return 200 OK", HttpStatus.OK, response.getStatusCode());
        // All 5 studies should be returned (negative offset treated as 0)
        int studyCount = countOccurrences(response.getBody(), "\"0020000D\"");
        assertEquals("Should return all studies when offset is negative", 5, studyCount);
    }

    @Test
    public void testSearchStudies_ZeroLimit() {
        // Arrange
        String projectId = "TestProject";
        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("limit", "0");

        List<Attributes> mockStudies = createMockStudies(5);

        when(mockDicomService.searchStudies(any(UserI.class), eq(projectId), any(Attributes.class)))
            .thenReturn(mockStudies);

        // Act
        ResponseEntity<String> response = qidoRsApi.searchStudies(projectId, queryParams);

        // Assert - should succeed with default limit (0 is invalid)
        assertEquals("Should return 200 OK", HttpStatus.OK, response.getStatusCode());
    }

    @Test
    public void testSearchStudies_OffsetBeyondResults() {
        // Arrange
        String projectId = "TestProject";
        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("offset", "100"); // Beyond the 5 results

        List<Attributes> mockStudies = createMockStudies(5);

        when(mockDicomService.searchStudies(any(UserI.class), eq(projectId), any(Attributes.class)))
            .thenReturn(mockStudies);

        // Act
        ResponseEntity<String> response = qidoRsApi.searchStudies(projectId, queryParams);

        // Assert
        assertEquals("Should return 200 OK", HttpStatus.OK, response.getStatusCode());
        assertEquals("Should return empty array when offset exceeds results", "[]", response.getBody());
        assertEquals("X-Total-Count should still show total", "5",
                response.getHeaders().getFirst("X-Total-Count"));
    }

    @Test
    public void testSearchStudies_VerifyContentType() {
        // Arrange
        String projectId = "TestProject";
        List<Attributes> mockStudies = createMockStudies(1);

        when(mockDicomService.searchStudies(any(UserI.class), eq(projectId), any(Attributes.class)))
            .thenReturn(mockStudies);

        // Act
        ResponseEntity<String> response = qidoRsApi.searchStudies(projectId, null);

        // Assert
        assertNotNull("Content-Type should be set", response.getHeaders().getContentType());
        assertTrue("Content-Type should be application/dicom+json",
                response.getHeaders().getContentType().toString().contains("application/dicom+json"));
    }

    @Test
    public void testSearchSeries_WithPagination() {
        // Arrange
        String projectId = "TestProject";
        String studyUID = "1.2.3.4.5";
        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("limit", "2");
        queryParams.put("offset", "1");

        List<Attributes> mockSeries = createMockSeries(5);

        when(mockDicomService.searchSeries(any(UserI.class), eq(projectId), eq(studyUID), any(Attributes.class)))
            .thenReturn(mockSeries);

        // Act
        ResponseEntity<String> response = qidoRsApi.searchSeries(projectId, studyUID, queryParams);

        // Assert
        assertEquals("Should return 200 OK", HttpStatus.OK, response.getStatusCode());

        // Count SeriesInstanceUIDs (should be 2: indices 1,2)
        int seriesCount = countOccurrences(response.getBody(), "\"0020000E\"");
        assertEquals("Should return 2 series", 2, seriesCount);

        assertEquals("X-Total-Count should show total", "5",
                response.getHeaders().getFirst("X-Total-Count"));
    }

    @Test
    public void testSearchInstances_WithPagination() {
        // Arrange
        String projectId = "TestProject";
        String studyUID = "1.2.3.4.5";
        String seriesUID = "1.2.3.4.5.100";
        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("limit", "3");

        List<Attributes> mockInstances = createMockInstances(10);

        when(mockDicomService.searchInstances(any(UserI.class), eq(projectId), eq(studyUID), eq(seriesUID), any(Attributes.class)))
            .thenReturn(mockInstances);

        // Act
        ResponseEntity<String> response = qidoRsApi.searchInstances(projectId, studyUID, seriesUID, queryParams);

        // Assert
        assertEquals("Should return 200 OK", HttpStatus.OK, response.getStatusCode());

        // Count SOPInstanceUIDs (should be 3)
        int instanceCount = countOccurrences(response.getBody(), "\"00080018\"");
        assertEquals("Should return 3 instances", 3, instanceCount);

        assertEquals("X-Total-Count should show total", "10",
                response.getHeaders().getFirst("X-Total-Count"));
    }

    // ========== Helper Methods ==========

    private int countOccurrences(String str, String substring) {
        int count = 0;
        int index = 0;
        while ((index = str.indexOf(substring, index)) != -1) {
            count++;
            index += substring.length();
        }
        return count;
    }

    private List<Attributes> createMockStudies(int count) {
        List<Attributes> studies = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Attributes attrs = new Attributes();
            attrs.setString(Tag.StudyInstanceUID, VR.UI, "1.2.3.4.5." + i);
            attrs.setString(Tag.PatientName, VR.PN, "Patient" + i);
            attrs.setString(Tag.PatientID, VR.LO, "PID" + i);
            attrs.setString(Tag.StudyDate, VR.DA, "20250115");
            attrs.setString(Tag.Modality, VR.CS, "MR");
            studies.add(attrs);
        }
        return studies;
    }

    private List<Attributes> createMockSeries(int count) {
        List<Attributes> series = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Attributes attrs = new Attributes();
            attrs.setString(Tag.SeriesInstanceUID, VR.UI, "1.2.3.4.5.100." + i);
            attrs.setString(Tag.Modality, VR.CS, "MR");
            attrs.setString(Tag.SeriesDescription, VR.LO, "Series" + i);
            attrs.setString(Tag.SeriesNumber, VR.IS, String.valueOf(i + 1));
            series.add(attrs);
        }
        return series;
    }

    private List<Attributes> createMockInstances(int count) {
        List<Attributes> instances = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Attributes attrs = new Attributes();
            attrs.setString(Tag.SOPClassUID, VR.UI, "1.2.840.10008.5.1.4.1.1.4");
            attrs.setString(Tag.SOPInstanceUID, VR.UI, "1.2.3.4.5.6." + i);
            attrs.setInt(Tag.InstanceNumber, VR.IS, i + 1);
            instances.add(attrs);
        }
        return instances;
    }
}
