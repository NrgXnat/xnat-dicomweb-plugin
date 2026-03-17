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
import org.nrg.xnat.dicomweb.exceptions.BadRequestException;
import org.nrg.xnat.dicomweb.exceptions.NotAcceptableException;
import org.nrg.xnat.dicomweb.exceptions.ResourceNotFoundException;
import org.nrg.xnat.dicomweb.service.ImageFormat;
import org.nrg.xnat.dicomweb.service.RenderedInstanceResult;
import org.nrg.xnat.dicomweb.service.RenderingParams;
import org.nrg.xnat.dicomweb.service.XnatDicomService;
import org.nrg.xnat.dicomweb.utils.BulkDataHandler;
import org.nrg.xnat.dicomweb.utils.BulkDataHandler.BulkDataItem;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;


/**
 * Tests for WadoRsApi, specifically the study metadata endpoint fix
 */
public class WadoRsApiTest {

    @Mock
    private XnatDicomService mockDicomService;

    @Mock
    private UserManagementServiceI mockUserManagementService;

    @Mock
    private RoleHolder mockRoleHolder;

    @Mock
    private UserI mockUser;

    private WadoRsApi wadoRsApi;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        wadoRsApi = new WadoRsApi(mockDicomService, mockUserManagementService, mockRoleHolder) {
            @Override
            protected UserI getSessionUser() {
                return mockUser;
            }
        };
    }

    @Test
    public void testRetrieveStudyMetadata_ReturnsArrayOfInstances() throws Exception {
        // Arrange
        String projectId = "TestProject";
        String studyUID = "1.2.3.4.5";

        // Create mock instance metadata (simulating multiple instances in a study)
        List<Attributes> mockInstances = createMockInstances(3);

        when(mockDicomService.retrieveAllStudyInstanceMetadata(any(UserI.class), eq(projectId), eq(studyUID)))
            .thenReturn(mockInstances);

        // Mock HttpServletRequest
        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getRequestURL()).thenReturn(new StringBuffer("http://localhost:8080/xapi/dicomweb/projects/TestProject/studies/" + studyUID + "/metadata"));

        // Act
        ResponseEntity<String> response = wadoRsApi.retrieveStudyMetadata(projectId, studyUID, mockRequest);

        // Assert
        assertEquals("Should return 200 OK", HttpStatus.OK, response.getStatusCode());
        assertNotNull("Response body should not be null", response.getBody());

        String responseBody = response.getBody();
        assertTrue("Response should start with '['", responseBody.startsWith("["));
        assertTrue("Response should end with ']'", responseBody.endsWith("]"));

        // Verify it contains multiple instances (should have multiple SOP Instance UIDs)
        int sopInstanceUIDCount = countOccurrences(responseBody, "\"00080018\"");
        assertEquals("Should contain 3 SOP Instance UIDs", 3, sopInstanceUIDCount);
    }

    @Test(expected = ResourceNotFoundException.class)
    public void testRetrieveStudyMetadata_NotFound() throws Exception {
        // Arrange
        String projectId = "TestProject";
        String studyUID = "1.2.3.4.5";

        when(mockDicomService.retrieveAllStudyInstanceMetadata(any(UserI.class), eq(projectId), eq(studyUID)))
            .thenReturn(new ArrayList<>());

        // Mock HttpServletRequest
        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getRequestURL()).thenReturn(new StringBuffer("http://localhost:8080/xapi/dicomweb/projects/TestProject/studies/" + studyUID + "/metadata"));

        // Act - should throw ResourceNotFoundException
        wadoRsApi.retrieveStudyMetadata(projectId, studyUID, mockRequest);
    }

    @Test
    public void testRetrieveStudyMetadata_EachInstanceHasSOPInstanceUID() throws Exception {
        // Arrange
        String projectId = "TestProject";
        String studyUID = "1.2.3.4.5";

        List<Attributes> mockInstances = createMockInstances(2);

        when(mockDicomService.retrieveAllStudyInstanceMetadata(any(UserI.class), eq(projectId), eq(studyUID)))
            .thenReturn(mockInstances);

        // Mock HttpServletRequest
        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getRequestURL()).thenReturn(new StringBuffer("http://localhost:8080/xapi/dicomweb/projects/TestProject/studies/" + studyUID + "/metadata"));

        // Act
        ResponseEntity<String> response = wadoRsApi.retrieveStudyMetadata(projectId, studyUID, mockRequest);

        // Assert
        String responseBody = response.getBody();
        assertNotNull(responseBody);

        // Each instance should have both SOP Instance UID (00080018) and SOP Class UID (00080016)
        assertTrue("Should contain SOP Instance UID tag", responseBody.contains("\"00080018\""));
        assertTrue("Should contain SOP Class UID tag", responseBody.contains("\"00080016\""));
    }

    @Test
    public void testRetrieveAllStudyInstanceMetadata_NoNullPointerException() throws Exception {
        // Regression test: verify that retrieveAllStudyInstanceMetadata doesn't throw NPE
        // when called with valid parameters (unlike searchInstances which required non-null seriesUID)
        String projectId = "TestProject";
        String studyUID = "1.2.3.4.5";

        List<Attributes> mockInstances = createMockInstances(5);

        when(mockDicomService.retrieveAllStudyInstanceMetadata(any(UserI.class), eq(projectId), eq(studyUID)))
            .thenReturn(mockInstances);

        // Mock HttpServletRequest
        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getRequestURL()).thenReturn(new StringBuffer("http://localhost:8080/xapi/dicomweb/projects/TestProject/studies/" + studyUID + "/metadata"));

        // Act - should not throw NullPointerException
        ResponseEntity<String> response = wadoRsApi.retrieveStudyMetadata(projectId, studyUID, mockRequest);

        // Assert
        assertEquals("Should successfully return all instances without NPE", HttpStatus.OK, response.getStatusCode());
        assertNotNull("Response should contain data", response.getBody());

        // Verify all 5 instances are in the response
        int sopInstanceUIDCount = countOccurrences(response.getBody(), "\"00080018\"");
        assertEquals("Should return all 5 instances", 5, sopInstanceUIDCount);
    }

    // Helper methods

    private List<Attributes> createMockInstances(int count) {
        List<Attributes> instances = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Attributes attrs = new Attributes();
            attrs.setString(Tag.SOPClassUID, VR.UI, "1.2.840.10008.5.1.4.1.1.2"); // CT Image Storage
            attrs.setString(Tag.SOPInstanceUID, VR.UI, "1.2.3.4.5.6." + i);
            attrs.setString(Tag.StudyInstanceUID, VR.UI, "1.2.3.4.5");
            attrs.setString(Tag.SeriesInstanceUID, VR.UI, "1.2.3.4.5.100");
            attrs.setString(Tag.Modality, VR.CS, "CT");
            attrs.setInt(Tag.InstanceNumber, VR.IS, i + 1);
            instances.add(attrs);
        }
        return instances;
    }

    private int countOccurrences(String str, String substring) {
        int count = 0;
        int index = 0;
        while ((index = str.indexOf(substring, index)) != -1) {
            count++;
            index += substring.length();
        }
        return count;
    }

    // ========== Instance Retrieval Tests ==========

    @Test
    public void testRetrieveInstance_Success() throws Exception {
        // Arrange
        String projectId = "TestProject";
        String studyUID = "1.2.3.4.5";
        String seriesUID = "1.2.3.4.5.100";
        String instanceUID = "1.2.3.4.5.6.1";

        byte[] mockDicomData = new byte[]{0x00, 0x01, 0x02, 0x03};
        java.io.ByteArrayInputStream mockStream = new java.io.ByteArrayInputStream(mockDicomData);

        when(mockDicomService.retrieveInstance(any(UserI.class), eq(projectId), eq(studyUID),
                eq(seriesUID), eq(instanceUID)))
            .thenReturn(mockStream);

        // Act
        ResponseEntity<?> response = wadoRsApi.retrieveInstance(projectId, studyUID, seriesUID, instanceUID);

        // Assert
        assertEquals("Should return 200 OK", HttpStatus.OK, response.getStatusCode());
        assertNotNull("Content-Type should be set", response.getHeaders().getContentType());
        assertEquals("Should return application/dicom",
                "application/dicom", response.getHeaders().getContentType().toString());
    }

    @Test(expected = ResourceNotFoundException.class)
    public void testRetrieveInstance_NotFound() throws Exception {
        // Arrange
        String projectId = "TestProject";
        String studyUID = "1.2.3.4.5";
        String seriesUID = "1.2.3.4.5.100";
        String instanceUID = "1.2.3.4.5.6.999";

        when(mockDicomService.retrieveInstance(any(UserI.class), eq(projectId), eq(studyUID),
                eq(seriesUID), eq(instanceUID)))
            .thenThrow(new ResourceNotFoundException("Instance", instanceUID));

        // Act - should throw ResourceNotFoundException
        wadoRsApi.retrieveInstance(projectId, studyUID, seriesUID, instanceUID);
    }

    // ========== Instance Metadata Tests ==========

    @Test
    public void testRetrieveInstanceMetadata_Success() throws Exception {
        // Arrange
        String projectId = "TestProject";
        String studyUID = "1.2.3.4.5";
        String seriesUID = "1.2.3.4.5.100";
        String instanceUID = "1.2.3.4.5.6.1";

        Attributes mockAttrs = new Attributes();
        mockAttrs.setString(Tag.SOPClassUID, VR.UI, "1.2.840.10008.5.1.4.1.1.2");
        mockAttrs.setString(Tag.SOPInstanceUID, VR.UI, instanceUID);

        when(mockDicomService.retrieveMetadata(any(UserI.class), eq(projectId), eq(studyUID),
                eq(seriesUID), eq(instanceUID)))
            .thenReturn(mockAttrs);

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getRequestURL()).thenReturn(new StringBuffer(
                "http://localhost:8080/xapi/dicomweb/projects/TestProject/studies/" + studyUID +
                "/series/" + seriesUID + "/instances/" + instanceUID + "/metadata"));
        when(mockRequest.getHeader("Accept")).thenReturn("application/dicom+json");

        // Act
        ResponseEntity<String> response = wadoRsApi.retrieveInstanceMetadata(projectId, studyUID,
                seriesUID, instanceUID, mockRequest);

        // Assert
        assertEquals("Should return 200 OK", HttpStatus.OK, response.getStatusCode());
        assertNotNull("Response body should not be null", response.getBody());
        assertTrue("Response should be JSON array", response.getBody().startsWith("["));
    }

    @Test(expected = ResourceNotFoundException.class)
    public void testRetrieveInstanceMetadata_NotFound() throws Exception {
        // Arrange
        String projectId = "TestProject";
        String studyUID = "1.2.3.4.5";
        String seriesUID = "1.2.3.4.5.100";
        String instanceUID = "1.2.3.4.5.6.999";

        when(mockDicomService.retrieveMetadata(any(UserI.class), eq(projectId), eq(studyUID),
                eq(seriesUID), eq(instanceUID)))
            .thenThrow(new ResourceNotFoundException("Instance", instanceUID));

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getRequestURL()).thenReturn(new StringBuffer(
                "http://localhost:8080/xapi/dicomweb/projects/TestProject/studies/" + studyUID +
                "/series/" + seriesUID + "/instances/" + instanceUID + "/metadata"));

        // Act - should throw ResourceNotFoundException
        wadoRsApi.retrieveInstanceMetadata(projectId, studyUID, seriesUID, instanceUID, mockRequest);
    }

    // ========== Series Retrieval Tests ==========

    @Test
    public void testRetrieveSeries_Success() throws Exception {
        // Arrange
        String projectId = "TestProject";
        String studyUID = "1.2.3.4.5";
        String seriesUID = "1.2.3.4.5.100";

        List<java.io.InputStream> mockStreams = new ArrayList<>();
        mockStreams.add(new java.io.ByteArrayInputStream(new byte[]{1, 2, 3}));
        mockStreams.add(new java.io.ByteArrayInputStream(new byte[]{4, 5, 6}));

        when(mockDicomService.retrieveSeries(any(UserI.class), eq(projectId), eq(studyUID), eq(seriesUID)))
            .thenReturn(mockStreams);

        // Act
        ResponseEntity<?> response = wadoRsApi.retrieveSeries(projectId, studyUID, seriesUID);

        // Assert
        assertEquals("Should return 200 OK", HttpStatus.OK, response.getStatusCode());
        assertNotNull("Content-Type should be set", response.getHeaders().getContentType());
        String contentType = response.getHeaders().getContentType().toString();
        assertTrue("Should return multipart/related", contentType.startsWith("multipart/related"));
    }

    @Test(expected = ResourceNotFoundException.class)
    public void testRetrieveSeries_NotFound() throws Exception {
        // Arrange
        String projectId = "TestProject";
        String studyUID = "1.2.3.4.5";
        String seriesUID = "1.2.3.4.5.999";

        when(mockDicomService.retrieveSeries(any(UserI.class), eq(projectId), eq(studyUID), eq(seriesUID)))
            .thenReturn(new ArrayList<>());

        // Act - should throw ResourceNotFoundException
        wadoRsApi.retrieveSeries(projectId, studyUID, seriesUID);
    }

    // ========== Study Retrieval Tests ==========

    @Test
    public void testRetrieveStudy_Success() throws Exception {
        // Arrange
        String projectId = "TestProject";
        String studyUID = "1.2.3.4.5";

        List<java.io.InputStream> mockStreams = new ArrayList<>();
        mockStreams.add(new java.io.ByteArrayInputStream(new byte[]{1, 2, 3}));
        mockStreams.add(new java.io.ByteArrayInputStream(new byte[]{4, 5, 6}));
        mockStreams.add(new java.io.ByteArrayInputStream(new byte[]{7, 8, 9}));

        when(mockDicomService.retrieveStudy(any(UserI.class), eq(projectId), eq(studyUID)))
            .thenReturn(mockStreams);

        // Act
        ResponseEntity<?> response = wadoRsApi.retrieveStudy(projectId, studyUID);

        // Assert
        assertEquals("Should return 200 OK", HttpStatus.OK, response.getStatusCode());
        assertNotNull("Content-Type should be set", response.getHeaders().getContentType());
        String contentType = response.getHeaders().getContentType().toString();
        assertTrue("Should return multipart/related", contentType.startsWith("multipart/related"));
    }

    @Test(expected = ResourceNotFoundException.class)
    public void testRetrieveStudy_NotFound() throws Exception {
        // Arrange
        String projectId = "TestProject";
        String studyUID = "1.2.3.4.5.999";

        when(mockDicomService.retrieveStudy(any(UserI.class), eq(projectId), eq(studyUID)))
            .thenReturn(new ArrayList<>());

        // Act - should throw ResourceNotFoundException
        wadoRsApi.retrieveStudy(projectId, studyUID);
    }

    // ========== Series Metadata Tests ==========

    @Test
    public void testRetrieveSeriesMetadata_Success() throws Exception {
        // Arrange
        String projectId = "TestProject";
        String studyUID = "1.2.3.4.5";
        String seriesUID = "1.2.3.4.5.100";

        List<Attributes> mockInstances = createMockInstances(3);

        when(mockDicomService.searchInstances(any(UserI.class), eq(projectId), eq(studyUID),
                eq(seriesUID), any()))
            .thenReturn(mockInstances);

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getRequestURL()).thenReturn(new StringBuffer(
                "http://localhost:8080/xapi/dicomweb/projects/TestProject/studies/" + studyUID +
                "/series/" + seriesUID + "/metadata"));
        when(mockRequest.getHeader("Accept")).thenReturn("application/dicom+json");

        // Act
        ResponseEntity<String> response = wadoRsApi.retrieveSeriesMetadata(projectId, studyUID,
                seriesUID, mockRequest);

        // Assert
        assertEquals("Should return 200 OK", HttpStatus.OK, response.getStatusCode());
        assertNotNull("Response body should not be null", response.getBody());
        assertTrue("Response should be JSON array", response.getBody().startsWith("["));

        int sopInstanceUIDCount = countOccurrences(response.getBody(), "\"00080018\"");
        assertEquals("Should contain 3 SOP Instance UIDs", 3, sopInstanceUIDCount);
    }

    @Test(expected = ResourceNotFoundException.class)
    public void testRetrieveSeriesMetadata_NotFound() throws Exception {
        // Arrange
        String projectId = "TestProject";
        String studyUID = "1.2.3.4.5";
        String seriesUID = "1.2.3.4.5.999";

        when(mockDicomService.searchInstances(any(UserI.class), eq(projectId), eq(studyUID),
                eq(seriesUID), any()))
            .thenReturn(new ArrayList<>());

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getRequestURL()).thenReturn(new StringBuffer(
                "http://localhost:8080/xapi/dicomweb/projects/TestProject/studies/" + studyUID +
                "/series/" + seriesUID + "/metadata"));

        // Act - should throw ResourceNotFoundException
        wadoRsApi.retrieveSeriesMetadata(projectId, studyUID, seriesUID, mockRequest);
    }

    // ========== Rendered Instance Tests ==========

    @Test
    public void testRetrieveInstanceRendered_Success() throws Exception {
        // Arrange
        String projectId = "TestProject";
        String studyUID = "1.2.3.4.5";
        String seriesUID = "1.2.3.4.5.100";
        String instanceUID = "1.2.3.4.5.6.1";

        byte[] mockImageData = new byte[]{(byte)0xFF, (byte)0xD8, (byte)0xFF, (byte)0xE0}; // JPEG header
        org.nrg.xnat.dicomweb.service.RenderedInstanceResult mockResult =
                new org.nrg.xnat.dicomweb.service.RenderedInstanceResult(
                        mockImageData, 1, 1, null);

        when(mockDicomService.retrieveRenderedInstance(any(UserI.class), eq(projectId), eq(studyUID),
                eq(seriesUID), eq(instanceUID), any(), any(), any()))
            .thenReturn(mockResult);

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getHeader("Accept")).thenReturn("image/jpeg");

        HttpServletResponse mockResponse = mock(HttpServletResponse.class);
        javax.servlet.ServletOutputStream mockOutputStream = mock(javax.servlet.ServletOutputStream.class);
        when(mockResponse.getOutputStream()).thenReturn(mockOutputStream);

        // Act
        wadoRsApi.retrieveInstanceRendered(projectId, studyUID, seriesUID, instanceUID, null, mockRequest, mockResponse);

        // Assert
        verify(mockResponse).setContentType("image/jpeg");
        verify(mockOutputStream).write(mockImageData);
    }

    @Test(expected = ResourceNotFoundException.class)
    public void testRetrieveInstanceRendered_NotFound() throws Exception {
        // Arrange
        String projectId = "TestProject";
        String studyUID = "1.2.3.4.5";
        String seriesUID = "1.2.3.4.5.100";
        String instanceUID = "1.2.3.4.5.6.999";

        when(mockDicomService.retrieveRenderedInstance(any(UserI.class), eq(projectId), eq(studyUID),
                eq(seriesUID), eq(instanceUID), any(), any(), any()))
            .thenThrow(new ResourceNotFoundException("Instance", instanceUID));

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getHeader("Accept")).thenReturn("image/jpeg");

        HttpServletResponse mockResponse = mock(HttpServletResponse.class);

        // Act - should throw ResourceNotFoundException
        wadoRsApi.retrieveInstanceRendered(projectId, studyUID, seriesUID, instanceUID, null, mockRequest, mockResponse);
    }

    @Test
    public void testRetrieveInstanceRendered_WithFrameNumber() throws Exception {
        // Arrange
        String projectId = "TestProject";
        String studyUID = "1.2.3.4.5";
        String seriesUID = "1.2.3.4.5.100";
        String instanceUID = "1.2.3.4.5.6.1";
        Integer frameNumber = 5;

        byte[] mockImageData = new byte[]{1, 2, 3, 4};
        org.nrg.xnat.dicomweb.service.RenderedInstanceResult mockResult =
                new org.nrg.xnat.dicomweb.service.RenderedInstanceResult(
                        mockImageData, 10, 5, 15.0);

        when(mockDicomService.retrieveRenderedInstance(any(UserI.class), eq(projectId), eq(studyUID),
                eq(seriesUID), eq(instanceUID), eq(frameNumber), any(), any()))
            .thenReturn(mockResult);

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getHeader("Accept")).thenReturn("image/jpeg");

        HttpServletResponse mockResponse = mock(HttpServletResponse.class);
        javax.servlet.ServletOutputStream mockOutputStream = mock(javax.servlet.ServletOutputStream.class);
        when(mockResponse.getOutputStream()).thenReturn(mockOutputStream);

        // Act
        wadoRsApi.retrieveInstanceRendered(projectId, studyUID, seriesUID, instanceUID, frameNumber, mockRequest, mockResponse);

        // Assert
        verify(mockResponse).setHeader("X-Frame-Count", "10");
        verify(mockResponse).setHeader("X-Frame-Number", "5");
        verify(mockResponse).setHeader("X-Multi-Frame", "true");
    }

    // ========== Frame retrieval tests ==========

    @Test
    public void testRetrieveFrames_SingleFrame_ReturnsOctetStream() throws Exception {
        // Arrange
        String projectId = "TestProject";
        String studyUID = "1.2.3.4.5";
        String seriesUID = "1.2.3.4.5.100";
        String instanceUID = "1.2.3.4.5.6.1";
        String frameList = "1";

        List<byte[]> mockFrames = new ArrayList<>();
        mockFrames.add(new byte[]{1, 2, 3, 4, 5});

        when(mockDicomService.retrieveFrames(any(UserI.class), eq(projectId), eq(studyUID),
                eq(seriesUID), eq(instanceUID), eq(frameList)))
            .thenReturn(mockFrames);

        // Act
        ResponseEntity<?> response = wadoRsApi.retrieveFrames(projectId, studyUID, seriesUID,
                instanceUID, frameList, null);

        // Assert
        assertEquals("Should return 200 OK", HttpStatus.OK, response.getStatusCode());
        assertNotNull("Content-Type should be set", response.getHeaders().getContentType());
        assertEquals("Should return application/octet-stream for single frame",
                "application/octet-stream", response.getHeaders().getContentType().toString());
    }

    @Test
    public void testRetrieveFrames_MultipleFrames_ReturnsMultipart() throws Exception {
        // Arrange
        String projectId = "TestProject";
        String studyUID = "1.2.3.4.5";
        String seriesUID = "1.2.3.4.5.100";
        String instanceUID = "1.2.3.4.5.6.1";
        String frameList = "1,2,3";

        List<byte[]> mockFrames = new ArrayList<>();
        mockFrames.add(new byte[]{1, 2, 3});
        mockFrames.add(new byte[]{4, 5, 6});
        mockFrames.add(new byte[]{7, 8, 9});

        when(mockDicomService.retrieveFrames(any(UserI.class), eq(projectId), eq(studyUID),
                eq(seriesUID), eq(instanceUID), eq(frameList)))
            .thenReturn(mockFrames);

        // Act
        ResponseEntity<?> response = wadoRsApi.retrieveFrames(projectId, studyUID, seriesUID,
                instanceUID, frameList, null);

        // Assert
        assertEquals("Should return 200 OK", HttpStatus.OK, response.getStatusCode());
        assertNotNull("Content-Type should be set", response.getHeaders().getContentType());
        String contentType = response.getHeaders().getContentType().toString();
        assertTrue("Should return multipart/related for multiple frames",
                contentType.startsWith("multipart/related"));
        assertTrue("Should include boundary parameter", contentType.contains("boundary="));
    }

    @Test(expected = ResourceNotFoundException.class)
    public void testRetrieveFrames_NotFound() throws Exception {
        // Arrange
        String projectId = "TestProject";
        String studyUID = "1.2.3.4.5";
        String seriesUID = "1.2.3.4.5.100";
        String instanceUID = "1.2.3.4.5.6.999";
        String frameList = "1";

        when(mockDicomService.retrieveFrames(any(UserI.class), eq(projectId), eq(studyUID),
                eq(seriesUID), eq(instanceUID), eq(frameList)))
            .thenThrow(new ResourceNotFoundException("Instance", instanceUID));

        // Act - should throw ResourceNotFoundException
        wadoRsApi.retrieveFrames(projectId, studyUID, seriesUID, instanceUID, frameList, null);
    }

    @Test(expected = ResourceNotFoundException.class)
    public void testRetrieveFrames_InvalidFrameNumbers() throws Exception {
        // Arrange
        String projectId = "TestProject";
        String studyUID = "1.2.3.4.5";
        String seriesUID = "1.2.3.4.5.100";
        String instanceUID = "1.2.3.4.5.6.1";
        String frameList = "999";

        // Service returns empty list when frames are out of range
        when(mockDicomService.retrieveFrames(any(UserI.class), eq(projectId), eq(studyUID),
                eq(seriesUID), eq(instanceUID), eq(frameList)))
            .thenReturn(new ArrayList<>());

        // Act - should throw ResourceNotFoundException
        wadoRsApi.retrieveFrames(projectId, studyUID, seriesUID, instanceUID, frameList, null);
    }

    @Test
    public void testRetrieveFrames_NonSequentialFrames() throws Exception {
        // Arrange
        String projectId = "TestProject";
        String studyUID = "1.2.3.4.5";
        String seriesUID = "1.2.3.4.5.100";
        String instanceUID = "1.2.3.4.5.6.1";
        String frameList = "3,1,5";

        List<byte[]> mockFrames = new ArrayList<>();
        mockFrames.add(new byte[]{3});
        mockFrames.add(new byte[]{1});
        mockFrames.add(new byte[]{5});

        when(mockDicomService.retrieveFrames(any(UserI.class), eq(projectId), eq(studyUID),
                eq(seriesUID), eq(instanceUID), eq(frameList)))
            .thenReturn(mockFrames);

        // Act
        ResponseEntity<?> response = wadoRsApi.retrieveFrames(projectId, studyUID, seriesUID,
                instanceUID, frameList, null);

        // Assert
        assertEquals("Should return 200 OK for non-sequential frames",
                HttpStatus.OK, response.getStatusCode());
        assertEquals("Should return 3 frames", 3, mockFrames.size());
    }

    // ========== Content Negotiation Tests ==========

    // ---- Metadata: JSON vs XML via Accept header ----

    @Test
    public void testInstanceMetadata_AcceptJson_ReturnsJson() throws Exception {
        Attributes mockAttrs = createMockInstances(1).get(0);
        when(mockDicomService.retrieveMetadata(any(UserI.class), anyString(), anyString(),
                anyString(), anyString()))
            .thenReturn(mockAttrs);

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getRequestURL()).thenReturn(new StringBuffer(
                "http://localhost:8080/xapi/dicomweb/projects/P/studies/1/series/2/instances/3/metadata"));
        when(mockRequest.getHeader("Accept")).thenReturn("application/dicom+json");

        ResponseEntity<String> response = wadoRsApi.retrieveInstanceMetadata(
                "P", "1", "2", "3", mockRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("application/dicom+json", response.getHeaders().getContentType().toString());
        assertTrue("JSON response should be an array", response.getBody().startsWith("["));
    }

    @Test
    public void testInstanceMetadata_AcceptXml_ReturnsXml() throws Exception {
        Attributes mockAttrs = createMockInstances(1).get(0);
        when(mockDicomService.retrieveMetadata(any(UserI.class), anyString(), anyString(),
                anyString(), anyString()))
            .thenReturn(mockAttrs);

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getRequestURL()).thenReturn(new StringBuffer(
                "http://localhost:8080/xapi/dicomweb/projects/P/studies/1/series/2/instances/3/metadata"));
        when(mockRequest.getHeader("Accept")).thenReturn("application/dicom+xml");

        ResponseEntity<String> response = wadoRsApi.retrieveInstanceMetadata(
                "P", "1", "2", "3", mockRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("application/dicom+xml", response.getHeaders().getContentType().toString());
    }

    @Test
    public void testInstanceMetadata_NoAcceptHeader_DefaultsToJson() throws Exception {
        Attributes mockAttrs = createMockInstances(1).get(0);
        when(mockDicomService.retrieveMetadata(any(UserI.class), anyString(), anyString(),
                anyString(), anyString()))
            .thenReturn(mockAttrs);

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getRequestURL()).thenReturn(new StringBuffer(
                "http://localhost:8080/xapi/dicomweb/projects/P/studies/1/series/2/instances/3/metadata"));
        // No Accept header set — getHeader returns null

        ResponseEntity<String> response = wadoRsApi.retrieveInstanceMetadata(
                "P", "1", "2", "3", mockRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("application/dicom+json", response.getHeaders().getContentType().toString());
    }

    @Test
    public void testInstanceMetadata_WildcardAccept_DefaultsToJson() throws Exception {
        Attributes mockAttrs = createMockInstances(1).get(0);
        when(mockDicomService.retrieveMetadata(any(UserI.class), anyString(), anyString(),
                anyString(), anyString()))
            .thenReturn(mockAttrs);

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getRequestURL()).thenReturn(new StringBuffer(
                "http://localhost:8080/xapi/dicomweb/projects/P/studies/1/series/2/instances/3/metadata"));
        when(mockRequest.getHeader("Accept")).thenReturn("*/*");

        ResponseEntity<String> response = wadoRsApi.retrieveInstanceMetadata(
                "P", "1", "2", "3", mockRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("application/dicom+json", response.getHeaders().getContentType().toString());
    }

    @Test
    public void testInstanceMetadata_UnsupportedType_FallsBackToDefault() throws Exception {
        // Metadata resources have a default (JSON), so unsupported types fall back
        Attributes mockAttrs = createMockInstances(1).get(0);
        when(mockDicomService.retrieveMetadata(any(UserI.class), anyString(), anyString(),
                anyString(), anyString()))
            .thenReturn(mockAttrs);

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getRequestURL()).thenReturn(new StringBuffer(
                "http://localhost:8080/xapi/dicomweb/projects/P/studies/1/series/2/instances/3/metadata"));
        when(mockRequest.getHeader("Accept")).thenReturn("image/jpeg");

        ResponseEntity<String> response = wadoRsApi.retrieveInstanceMetadata(
                "P", "1", "2", "3", mockRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("application/dicom+json", response.getHeaders().getContentType().toString());
    }

    // ---- Metadata: quality-based negotiation ----

    @Test
    public void testStudyMetadata_QualityPrefersXml_ReturnsXml() throws Exception {
        List<Attributes> mockInstances = createMockInstances(2);
        when(mockDicomService.retrieveAllStudyInstanceMetadata(any(UserI.class), anyString(), anyString()))
            .thenReturn(mockInstances);

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getRequestURL()).thenReturn(new StringBuffer(
                "http://localhost:8080/xapi/dicomweb/projects/P/studies/1/metadata"));
        when(mockRequest.getHeader("Accept")).thenReturn(
                "application/dicom+json;q=0.5, application/dicom+xml;q=1.0");

        ResponseEntity<String> response = wadoRsApi.retrieveStudyMetadata(
                "P", "1", mockRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("application/dicom+xml", response.getHeaders().getContentType().toString());
    }

    @Test
    public void testSeriesMetadata_QualityPrefersJson_ReturnsJson() throws Exception {
        List<Attributes> mockInstances = createMockInstances(2);
        when(mockDicomService.searchInstances(any(UserI.class), anyString(), anyString(),
                anyString(), any()))
            .thenReturn(mockInstances);

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getRequestURL()).thenReturn(new StringBuffer(
                "http://localhost:8080/xapi/dicomweb/projects/P/studies/1/series/2/metadata"));
        when(mockRequest.getHeader("Accept")).thenReturn(
                "application/dicom+xml;q=0.8, application/dicom+json;q=1.0");

        ResponseEntity<String> response = wadoRsApi.retrieveSeriesMetadata(
                "P", "1", "2", mockRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("application/dicom+json", response.getHeaders().getContentType().toString());
    }

    // ---- Metadata: accept query parameter ----

    @Test
    public void testInstanceMetadata_AcceptQueryParam_OverridesHeader() throws Exception {
        Attributes mockAttrs = createMockInstances(1).get(0);
        when(mockDicomService.retrieveMetadata(any(UserI.class), anyString(), anyString(),
                anyString(), anyString()))
            .thenReturn(mockAttrs);

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getRequestURL()).thenReturn(new StringBuffer(
                "http://localhost:8080/xapi/dicomweb/projects/P/studies/1/series/2/instances/3/metadata"));
        // Header says JSON, but query param says XML — param wins
        when(mockRequest.getHeader("Accept")).thenReturn("application/dicom+json");

        ResponseEntity<String> response = wadoRsApi.retrieveInstanceMetadata(
                "P", "1", "2", "3", "application/dicom+xml", mockRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("application/dicom+xml", response.getHeaders().getContentType().toString());
    }

    @Test(expected = BadRequestException.class)
    public void testInstanceMetadata_AcceptQueryParamWildcard_Rejected() throws Exception {
        Attributes mockAttrs = createMockInstances(1).get(0);
        when(mockDicomService.retrieveMetadata(any(UserI.class), anyString(), anyString(),
                anyString(), anyString()))
            .thenReturn(mockAttrs);

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getRequestURL()).thenReturn(new StringBuffer(
                "http://localhost:8080/xapi/dicomweb/projects/P/studies/1/series/2/instances/3/metadata"));

        // Wildcards not allowed in accept query parameter per PS 3.18
        wadoRsApi.retrieveInstanceMetadata("P", "1", "2", "3", "*/*", mockRequest);
    }

    // ---- Rendered: content negotiation ----

    @Test
    public void testRendered_AcceptGif_PassesGifFormat() throws Exception {
        byte[] mockImageData = new byte[]{1, 2, 3, 4};
        RenderedInstanceResult mockResult =
                new RenderedInstanceResult(mockImageData, 1, 1, null, ImageFormat.GIF);

        when(mockDicomService.retrieveRenderedInstance(any(UserI.class), anyString(), anyString(),
                anyString(), anyString(), any(), eq(ImageFormat.GIF), any()))
            .thenReturn(mockResult);

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getHeader("Accept")).thenReturn("image/gif");

        HttpServletResponse mockResponse = mock(HttpServletResponse.class);
        javax.servlet.ServletOutputStream mockOutputStream = mock(javax.servlet.ServletOutputStream.class);
        when(mockResponse.getOutputStream()).thenReturn(mockOutputStream);

        wadoRsApi.retrieveInstanceRendered("P", "1", "2", "3", null, mockRequest, mockResponse);

        verify(mockDicomService).retrieveRenderedInstance(
                any(UserI.class), eq("P"), eq("1"), eq("2"), eq("3"),
                isNull(), eq(ImageFormat.GIF), any());
    }

    @Test
    public void testRendered_AcceptPng_PassesPngFormat() throws Exception {
        byte[] mockImageData = new byte[]{1, 2, 3, 4};
        RenderedInstanceResult mockResult =
                new RenderedInstanceResult(mockImageData, 1, 1, null, ImageFormat.PNG);

        when(mockDicomService.retrieveRenderedInstance(any(UserI.class), anyString(), anyString(),
                anyString(), anyString(), any(), eq(ImageFormat.PNG), any()))
            .thenReturn(mockResult);

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getHeader("Accept")).thenReturn("image/png");

        HttpServletResponse mockResponse = mock(HttpServletResponse.class);
        javax.servlet.ServletOutputStream mockOutputStream = mock(javax.servlet.ServletOutputStream.class);
        when(mockResponse.getOutputStream()).thenReturn(mockOutputStream);

        wadoRsApi.retrieveInstanceRendered("P", "1", "2", "3", null, mockRequest, mockResponse);

        verify(mockDicomService).retrieveRenderedInstance(
                any(UserI.class), eq("P"), eq("1"), eq("2"), eq("3"),
                isNull(), eq(ImageFormat.PNG), any());
    }

    @Test
    public void testRendered_NoAcceptHeader_DefaultsToJpeg() throws Exception {
        byte[] mockImageData = new byte[]{1, 2, 3, 4};
        RenderedInstanceResult mockResult =
                new RenderedInstanceResult(mockImageData, 1, 1, null);

        when(mockDicomService.retrieveRenderedInstance(any(UserI.class), anyString(), anyString(),
                anyString(), anyString(), any(), eq(ImageFormat.JPEG), any()))
            .thenReturn(mockResult);

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        // No Accept header

        HttpServletResponse mockResponse = mock(HttpServletResponse.class);
        javax.servlet.ServletOutputStream mockOutputStream = mock(javax.servlet.ServletOutputStream.class);
        when(mockResponse.getOutputStream()).thenReturn(mockOutputStream);

        wadoRsApi.retrieveInstanceRendered("P", "1", "2", "3", null, mockRequest, mockResponse);

        verify(mockDicomService).retrieveRenderedInstance(
                any(UserI.class), eq("P"), eq("1"), eq("2"), eq("3"),
                isNull(), eq(ImageFormat.JPEG), any());
    }

    @Test
    public void testRendered_QualityPrefersGif_PassesGif() throws Exception {
        byte[] mockImageData = new byte[]{1, 2, 3, 4};
        RenderedInstanceResult mockResult =
                new RenderedInstanceResult(mockImageData, 1, 1, null, ImageFormat.GIF);

        when(mockDicomService.retrieveRenderedInstance(any(UserI.class), anyString(), anyString(),
                anyString(), anyString(), any(), eq(ImageFormat.GIF), any()))
            .thenReturn(mockResult);

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getHeader("Accept")).thenReturn("image/jpeg;q=0.5, image/gif;q=1.0");

        HttpServletResponse mockResponse = mock(HttpServletResponse.class);
        javax.servlet.ServletOutputStream mockOutputStream = mock(javax.servlet.ServletOutputStream.class);
        when(mockResponse.getOutputStream()).thenReturn(mockOutputStream);

        wadoRsApi.retrieveInstanceRendered("P", "1", "2", "3", null, mockRequest, mockResponse);

        verify(mockDicomService).retrieveRenderedInstance(
                any(UserI.class), eq("P"), eq("1"), eq("2"), eq("3"),
                isNull(), eq(ImageFormat.GIF), any());
    }

    @Test
    public void testRendered_AcceptQueryParam_OverridesHeader() throws Exception {
        byte[] mockImageData = new byte[]{1, 2, 3, 4};
        RenderedInstanceResult mockResult =
                new RenderedInstanceResult(mockImageData, 1, 1, null, ImageFormat.GIF);

        when(mockDicomService.retrieveRenderedInstance(any(UserI.class), anyString(), anyString(),
                anyString(), anyString(), any(), eq(ImageFormat.GIF), any()))
            .thenReturn(mockResult);

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getHeader("Accept")).thenReturn("image/jpeg");

        HttpServletResponse mockResponse = mock(HttpServletResponse.class);
        javax.servlet.ServletOutputStream mockOutputStream = mock(javax.servlet.ServletOutputStream.class);
        when(mockResponse.getOutputStream()).thenReturn(mockOutputStream);

        // Header says JPEG, query param says GIF — param wins
        wadoRsApi.retrieveInstanceRendered("P", "1", "2", "3", null,
                "image/gif", null, null, null, mockRequest, mockResponse);

        verify(mockDicomService).retrieveRenderedInstance(
                any(UserI.class), eq("P"), eq("1"), eq("2"), eq("3"),
                isNull(), eq(ImageFormat.GIF), any());
    }

    @Test
    public void testRendered_UnsupportedType_FallsBackToJpeg() throws Exception {
        // Rendered resources have a default (JPEG), so unsupported types fall back
        byte[] mockImageData = new byte[]{1, 2, 3, 4};
        RenderedInstanceResult mockResult =
                new RenderedInstanceResult(mockImageData, 1, 1, null);

        when(mockDicomService.retrieveRenderedInstance(any(UserI.class), anyString(), anyString(),
                anyString(), anyString(), any(), eq(ImageFormat.JPEG), any()))
            .thenReturn(mockResult);

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getHeader("Accept")).thenReturn("video/mp4");

        HttpServletResponse mockResponse = mock(HttpServletResponse.class);
        javax.servlet.ServletOutputStream mockOutputStream = mock(javax.servlet.ServletOutputStream.class);
        when(mockResponse.getOutputStream()).thenReturn(mockOutputStream);

        wadoRsApi.retrieveInstanceRendered("P", "1", "2", "3", null, mockRequest, mockResponse);

        verify(mockDicomService).retrieveRenderedInstance(
                any(UserI.class), eq("P"), eq("1"), eq("2"), eq("3"),
                isNull(), eq(ImageFormat.JPEG), any());
    }

    // ---- Instance retrieval: content negotiation ----

    @Test
    public void testRetrieveInstance_AcceptDicom_Succeeds() throws Exception {
        byte[] mockDicomData = new byte[]{0x00, 0x01, 0x02, 0x03};
        java.io.ByteArrayInputStream mockStream = new java.io.ByteArrayInputStream(mockDicomData);

        when(mockDicomService.retrieveInstance(any(UserI.class), anyString(), anyString(),
                anyString(), anyString()))
            .thenReturn(mockStream);

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getHeader("Accept")).thenReturn("application/dicom");

        ResponseEntity<?> response = wadoRsApi.retrieveInstance(
                "P", "1", "2", "3", null, mockRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("application/dicom", response.getHeaders().getContentType().toString());
    }

    @Test
    public void testRetrieveInstance_WildcardAccept_Succeeds() throws Exception {
        byte[] mockDicomData = new byte[]{0x00, 0x01, 0x02, 0x03};
        java.io.ByteArrayInputStream mockStream = new java.io.ByteArrayInputStream(mockDicomData);

        when(mockDicomService.retrieveInstance(any(UserI.class), anyString(), anyString(),
                anyString(), anyString()))
            .thenReturn(mockStream);

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getHeader("Accept")).thenReturn("*/*");

        ResponseEntity<?> response = wadoRsApi.retrieveInstance(
                "P", "1", "2", "3", null, mockRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    // ---- Study/Series multipart: content negotiation ----

    @Test
    public void testRetrieveStudy_AcceptMultipartDicom_Succeeds() throws Exception {
        List<java.io.InputStream> mockStreams = new ArrayList<>();
        mockStreams.add(new java.io.ByteArrayInputStream(new byte[]{1, 2, 3}));

        when(mockDicomService.retrieveStudy(any(UserI.class), anyString(), anyString()))
            .thenReturn(mockStreams);

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getHeader("Accept")).thenReturn(
                "multipart/related;type=\"application/dicom\"");

        ResponseEntity<?> response = wadoRsApi.retrieveStudy(
                "P", "1", null, mockRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String contentType = response.getHeaders().getContentType().toString();
        assertTrue(contentType.startsWith("multipart/related"));
    }

    @Test
    public void testRetrieveStudy_WildcardAccept_Succeeds() throws Exception {
        List<java.io.InputStream> mockStreams = new ArrayList<>();
        mockStreams.add(new java.io.ByteArrayInputStream(new byte[]{1, 2, 3}));

        when(mockDicomService.retrieveStudy(any(UserI.class), anyString(), anyString()))
            .thenReturn(mockStreams);

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getHeader("Accept")).thenReturn("*/*");

        ResponseEntity<?> response = wadoRsApi.retrieveStudy(
                "P", "1", null, mockRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    public void testRetrieveSeries_NoAcceptHeader_Succeeds() throws Exception {
        List<java.io.InputStream> mockStreams = new ArrayList<>();
        mockStreams.add(new java.io.ByteArrayInputStream(new byte[]{1, 2, 3}));

        when(mockDicomService.retrieveSeries(any(UserI.class), anyString(), anyString(), anyString()))
            .thenReturn(mockStreams);

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        // No Accept header

        ResponseEntity<?> response = wadoRsApi.retrieveSeries(
                "P", "1", "2", null, mockRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String contentType = response.getHeaders().getContentType().toString();
        assertTrue(contentType.startsWith("multipart/related"));
    }

    // ---- Frames: accept query parameter ----

    // ========== Instance Bulk Data Tests ==========

    @Test
    public void testRetrieveInstanceBulkData_Success() throws Exception {
        String projectId = "TestProject";
        String studyUID = "1.2.3.4.5";
        String seriesUID = "1.2.3.4.5.100";
        String instanceUID = "1.2.3.4.5.6.1";

        List<BulkDataItem> mockItems = new ArrayList<>();
        mockItems.add(new BulkDataItem(
                "http://localhost/studies/1.2.3.4.5/series/1.2.3.4.5.100/instances/1.2.3.4.5.6.1/bulkdata/7FE00010",
                new byte[]{1, 2, 3, 4, 5}));

        when(mockDicomService.retrieveInstanceBulkData(any(UserI.class), eq(projectId),
                eq(studyUID), eq(seriesUID), eq(instanceUID), anyString()))
            .thenReturn(mockItems);

        ResponseEntity<?> response = wadoRsApi.retrieveInstanceBulkData(
                projectId, studyUID, seriesUID, instanceUID);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String contentType = response.getHeaders().getContentType().toString();
        assertTrue("Should return multipart/related", contentType.startsWith("multipart/related"));
        assertTrue("Should include octet-stream type", contentType.contains("application/octet-stream"));
    }

    @Test
    public void testRetrieveInstanceBulkData_ResponseContainsContentLocation() throws Exception {
        String projectId = "TestProject";
        String studyUID = "1.2.3.4.5";
        String seriesUID = "1.2.3.4.5.100";
        String instanceUID = "1.2.3.4.5.6.1";
        String expectedLocation = "http://localhost/studies/1.2.3.4.5/series/1.2.3.4.5.100/instances/1.2.3.4.5.6.1/bulkdata/7FE00010";

        List<BulkDataItem> mockItems = new ArrayList<>();
        mockItems.add(new BulkDataItem(expectedLocation, new byte[]{1, 2, 3}));

        when(mockDicomService.retrieveInstanceBulkData(any(UserI.class), eq(projectId),
                eq(studyUID), eq(seriesUID), eq(instanceUID), anyString()))
            .thenReturn(mockItems);

        ResponseEntity<InputStreamResource> response = wadoRsApi.retrieveInstanceBulkData(
                projectId, studyUID, seriesUID, instanceUID);

        // Read the multipart body and verify Content-Location header is present
        InputStreamResource resource = response.getBody();
        assertNotNull(resource);
        byte[] body = toBytes(resource.getInputStream());
        String bodyStr = new String(body);
        assertTrue("Multipart body should contain Content-Location",
                bodyStr.contains("Content-Location: " + expectedLocation));
    }

    @Test(expected = ResourceNotFoundException.class)
    public void testRetrieveInstanceBulkData_NotFound() throws Exception {
        when(mockDicomService.retrieveInstanceBulkData(any(UserI.class), anyString(),
                anyString(), anyString(), anyString(), anyString()))
            .thenReturn(new ArrayList<>());

        wadoRsApi.retrieveInstanceBulkData("P", "1", "2", "3");
    }

    // ========== Series Bulk Data Tests ==========

    @Test
    public void testRetrieveSeriesBulkData_AggregatesFromMultipleInstances() throws Exception {
        String projectId = "TestProject";
        String studyUID = "1.2.3.4.5";
        String seriesUID = "1.2.3.4.5.100";

        List<BulkDataItem> mockItems = new ArrayList<>();
        mockItems.add(new BulkDataItem("loc1", new byte[]{1, 2}));
        mockItems.add(new BulkDataItem("loc2", new byte[]{3, 4}));

        when(mockDicomService.retrieveSeriesBulkData(any(UserI.class), eq(projectId),
                eq(studyUID), eq(seriesUID), anyString()))
            .thenReturn(mockItems);

        ResponseEntity<?> response = wadoRsApi.retrieveSeriesBulkData(
                projectId, studyUID, seriesUID);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String contentType = response.getHeaders().getContentType().toString();
        assertTrue(contentType.startsWith("multipart/related"));
    }

    // ========== Study Bulk Data Tests ==========

    @Test
    public void testRetrieveStudyBulkData_AggregatesFromMultipleInstances() throws Exception {
        String projectId = "TestProject";
        String studyUID = "1.2.3.4.5";

        List<BulkDataItem> mockItems = new ArrayList<>();
        mockItems.add(new BulkDataItem("loc1", new byte[]{1}));
        mockItems.add(new BulkDataItem("loc2", new byte[]{2}));
        mockItems.add(new BulkDataItem("loc3", new byte[]{3}));

        when(mockDicomService.retrieveStudyBulkData(any(UserI.class), eq(projectId),
                eq(studyUID), anyString()))
            .thenReturn(mockItems);

        ResponseEntity<?> response = wadoRsApi.retrieveStudyBulkData(projectId, studyUID);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    // ========== Instance Pixel Data Tests ==========

    @Test
    public void testRetrieveInstancePixelData_Success() throws Exception {
        String projectId = "TestProject";
        String studyUID = "1.2.3.4.5";
        String seriesUID = "1.2.3.4.5.100";
        String instanceUID = "1.2.3.4.5.6.1";

        List<BulkDataItem> mockItems = new ArrayList<>();
        mockItems.add(new BulkDataItem(
                "http://localhost/studies/1.2.3.4.5/series/1.2.3.4.5.100/instances/1.2.3.4.5.6.1/bulkdata/7FE00010",
                new byte[]{10, 20, 30}));

        when(mockDicomService.retrieveInstancePixelData(any(UserI.class), eq(projectId),
                eq(studyUID), eq(seriesUID), eq(instanceUID), anyString()))
            .thenReturn(mockItems);

        ResponseEntity<?> response = wadoRsApi.retrieveInstancePixelData(
                projectId, studyUID, seriesUID, instanceUID);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String contentType = response.getHeaders().getContentType().toString();
        assertTrue(contentType.startsWith("multipart/related"));
    }

    @Test(expected = ResourceNotFoundException.class)
    public void testRetrieveInstancePixelData_NotFound() throws Exception {
        when(mockDicomService.retrieveInstancePixelData(any(UserI.class), anyString(),
                anyString(), anyString(), anyString(), anyString()))
            .thenReturn(new ArrayList<>());

        wadoRsApi.retrieveInstancePixelData("P", "1", "2", "3");
    }

    // ========== Existing single-tag bulk data Content-Location test ==========

    @Test
    public void testRetrieveBulkData_HasContentLocationHeader() throws Exception {
        String projectId = "TestProject";
        String studyUID = "1.2.3.4.5";
        String seriesUID = "1.2.3.4.5.100";
        String instanceUID = "1.2.3.4.5.6.1";
        String tag = "7FE00010";

        // Create a minimal valid DICOM dataset with pixel data
        org.dcm4che3.data.Attributes attrs = new org.dcm4che3.data.Attributes();
        attrs.setBytes(org.dcm4che3.data.Tag.PixelData, org.dcm4che3.data.VR.OW, new byte[]{1, 2, 3, 4});
        attrs.setString(org.dcm4che3.data.Tag.SOPClassUID, org.dcm4che3.data.VR.UI, "1.2.840.10008.5.1.4.1.1.2");
        attrs.setString(org.dcm4che3.data.Tag.SOPInstanceUID, org.dcm4che3.data.VR.UI, instanceUID);

        // Write to a byte array as Part 10 format
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        org.dcm4che3.io.DicomOutputStream dos = new org.dcm4che3.io.DicomOutputStream(baos, "1.2.840.10008.1.2.1");
        org.dcm4che3.data.Attributes fmi = attrs.createFileMetaInformation("1.2.840.10008.1.2.1");
        dos.writeDataset(fmi, attrs);
        dos.close();

        java.io.ByteArrayInputStream mockStream = new java.io.ByteArrayInputStream(baos.toByteArray());
        when(mockDicomService.retrieveInstance(any(UserI.class), eq(projectId), eq(studyUID),
                eq(seriesUID), eq(instanceUID)))
            .thenReturn(mockStream);

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getRequestURL()).thenReturn(new StringBuffer(
                "http://localhost:8080/xapi/dicomweb/projects/" + projectId +
                "/studies/" + studyUID + "/series/" + seriesUID +
                "/instances/" + instanceUID + "/bulkdata/" + tag));
        when(mockRequest.getHeader("Accept")).thenReturn("application/octet-stream");

        HttpServletResponse mockResponse = mock(HttpServletResponse.class);
        javax.servlet.ServletOutputStream mockOutputStream = mock(javax.servlet.ServletOutputStream.class);
        when(mockResponse.getOutputStream()).thenReturn(mockOutputStream);

        wadoRsApi.retrieveBulkData(projectId, studyUID, seriesUID, instanceUID, tag,
                null, mockRequest, mockResponse);

        verify(mockResponse).setHeader(eq("Content-Location"), contains("7FE00010"));
    }

    // ========== Content Negotiation for Bulk Data Endpoints ==========

    @Test
    public void testRetrieveInstanceBulkData_AcceptOctetStream_Succeeds() throws Exception {
        List<BulkDataItem> mockItems = new ArrayList<>();
        mockItems.add(new BulkDataItem("loc", new byte[]{1}));

        when(mockDicomService.retrieveInstanceBulkData(any(UserI.class), anyString(),
                anyString(), anyString(), anyString(), anyString()))
            .thenReturn(mockItems);

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getHeader("Accept")).thenReturn("application/octet-stream");
        when(mockRequest.getRequestURL()).thenReturn(new StringBuffer(
                "http://localhost:8080/xapi/dicomweb/projects/P/studies/1/series/2/instances/3/bulkdata"));

        ResponseEntity<?> response = wadoRsApi.retrieveInstanceBulkData(
                "P", "1", "2", "3", null, mockRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    public void testRetrieveInstanceBulkData_MultipartRelated_Succeeds() throws Exception {
        List<BulkDataItem> mockItems = new ArrayList<>();
        mockItems.add(new BulkDataItem("loc", new byte[]{1}));

        when(mockDicomService.retrieveInstanceBulkData(any(UserI.class), anyString(),
                anyString(), anyString(), anyString(), anyString()))
            .thenReturn(mockItems);

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getHeader("Accept")).thenReturn(
                "multipart/related;type=\"application/octet-stream\"");
        when(mockRequest.getRequestURL()).thenReturn(new StringBuffer(
                "http://localhost:8080/xapi/dicomweb/projects/P/studies/1/series/2/instances/3/bulkdata"));

        ResponseEntity<?> response = wadoRsApi.retrieveInstanceBulkData(
                "P", "1", "2", "3", null, mockRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    // Helper to read InputStream to byte array
    private byte[] toBytes(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    @Test
    public void testRetrieveFrames_AcceptOctetStream_Succeeds() throws Exception {
        List<byte[]> mockFrames = new ArrayList<>();
        mockFrames.add(new byte[]{1, 2, 3});

        when(mockDicomService.retrieveFrames(any(UserI.class), anyString(), anyString(),
                anyString(), anyString(), anyString()))
            .thenReturn(mockFrames);

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getHeader("Accept")).thenReturn("application/octet-stream");

        ResponseEntity<?> response = wadoRsApi.retrieveFrames(
                "P", "1", "2", "3", "1", null, mockRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("application/octet-stream",
                response.getHeaders().getContentType().toString());
    }

    // ========== Study Rendered Tests ==========

    @Test
    public void testRetrieveStudyRendered_Success() throws Exception {
        byte[] mockImageData = new byte[]{(byte)0xFF, (byte)0xD8, (byte)0xFF, (byte)0xE0};
        RenderedInstanceResult mockResult =
                new RenderedInstanceResult(mockImageData, 1, 1, null);

        when(mockDicomService.retrieveRenderedStudy(any(UserI.class), eq("P"), eq("1"),
                any(), any(ImageFormat.class), any()))
            .thenReturn(mockResult);

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getHeader("Accept")).thenReturn("image/jpeg");

        HttpServletResponse mockResponse = mock(HttpServletResponse.class);
        javax.servlet.ServletOutputStream mockOutputStream = mock(javax.servlet.ServletOutputStream.class);
        when(mockResponse.getOutputStream()).thenReturn(mockOutputStream);

        wadoRsApi.retrieveStudyRendered("P", "1", mockRequest, mockResponse);

        verify(mockResponse).setContentType("image/jpeg");
        verify(mockOutputStream).write(mockImageData);
    }

    @Test(expected = ResourceNotFoundException.class)
    public void testRetrieveStudyRendered_NotFound() throws Exception {
        when(mockDicomService.retrieveRenderedStudy(any(UserI.class), anyString(), anyString(),
                any(), any(ImageFormat.class), any()))
            .thenThrow(new ResourceNotFoundException("Study", "1.2.3"));

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getHeader("Accept")).thenReturn("image/jpeg");

        HttpServletResponse mockResponse = mock(HttpServletResponse.class);

        wadoRsApi.retrieveStudyRendered("P", "1.2.3", mockRequest, mockResponse);
    }

    // ========== Series Rendered Tests ==========

    @Test
    public void testRetrieveSeriesRendered_Success() throws Exception {
        byte[] mockImageData = new byte[]{1, 2, 3, 4};
        RenderedInstanceResult mockResult =
                new RenderedInstanceResult(mockImageData, 1, 1, null);

        when(mockDicomService.retrieveRenderedSeries(any(UserI.class), eq("P"), eq("1"), eq("2"),
                any(), any(ImageFormat.class), any()))
            .thenReturn(mockResult);

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getHeader("Accept")).thenReturn("image/jpeg");

        HttpServletResponse mockResponse = mock(HttpServletResponse.class);
        javax.servlet.ServletOutputStream mockOutputStream = mock(javax.servlet.ServletOutputStream.class);
        when(mockResponse.getOutputStream()).thenReturn(mockOutputStream);

        wadoRsApi.retrieveSeriesRendered("P", "1", "2", mockRequest, mockResponse);

        verify(mockResponse).setContentType("image/jpeg");
        verify(mockOutputStream).write(mockImageData);
    }

    @Test(expected = ResourceNotFoundException.class)
    public void testRetrieveSeriesRendered_NotFound() throws Exception {
        when(mockDicomService.retrieveRenderedSeries(any(UserI.class), anyString(), anyString(),
                anyString(), any(), any(ImageFormat.class), any()))
            .thenThrow(new ResourceNotFoundException("Series", "1.2.3"));

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getHeader("Accept")).thenReturn("image/jpeg");

        HttpServletResponse mockResponse = mock(HttpServletResponse.class);

        wadoRsApi.retrieveSeriesRendered("P", "1", "1.2.3", mockRequest, mockResponse);
    }

    // ========== Frame Rendered Tests ==========

    @Test
    public void testRetrieveFrameRendered_Success() throws Exception {
        byte[] mockImageData = new byte[]{1, 2, 3, 4};
        RenderedInstanceResult mockResult =
                new RenderedInstanceResult(mockImageData, 10, 3, null);

        when(mockDicomService.retrieveRenderedInstance(any(UserI.class), eq("P"), eq("1"), eq("2"),
                eq("3"), eq(3), any(ImageFormat.class), any()))
            .thenReturn(mockResult);

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getHeader("Accept")).thenReturn("image/jpeg");

        HttpServletResponse mockResponse = mock(HttpServletResponse.class);
        javax.servlet.ServletOutputStream mockOutputStream = mock(javax.servlet.ServletOutputStream.class);
        when(mockResponse.getOutputStream()).thenReturn(mockOutputStream);

        wadoRsApi.retrieveFrameRendered("P", "1", "2", "3", "3", mockRequest, mockResponse);

        verify(mockResponse).setContentType("image/jpeg");
        verify(mockResponse).setHeader("X-Frame-Count", "10");
        verify(mockResponse).setHeader("X-Frame-Number", "3");
    }

    // ========== Thumbnail Tests ==========

    @Test
    public void testRetrieveStudyThumbnail_Success() throws Exception {
        byte[] mockImageData = new byte[]{1, 2, 3};
        RenderedInstanceResult mockResult =
                new RenderedInstanceResult(mockImageData, 1, 1, null);

        when(mockDicomService.retrieveThumbnailStudy(any(UserI.class), eq("P"), eq("1"), any(), any()))
            .thenReturn(mockResult);

        HttpServletResponse mockResponse = mock(HttpServletResponse.class);
        javax.servlet.ServletOutputStream mockOutputStream = mock(javax.servlet.ServletOutputStream.class);
        when(mockResponse.getOutputStream()).thenReturn(mockOutputStream);

        wadoRsApi.retrieveStudyThumbnail("P", "1", mockResponse);

        verify(mockResponse).setContentType("image/jpeg");
        verify(mockOutputStream).write(mockImageData);
    }

    @Test
    public void testRetrieveSeriesThumbnail_Success() throws Exception {
        byte[] mockImageData = new byte[]{1, 2, 3};
        RenderedInstanceResult mockResult =
                new RenderedInstanceResult(mockImageData, 1, 1, null);

        when(mockDicomService.retrieveThumbnailSeries(any(UserI.class), eq("P"), eq("1"), eq("2"), any(), any()))
            .thenReturn(mockResult);

        HttpServletResponse mockResponse = mock(HttpServletResponse.class);
        javax.servlet.ServletOutputStream mockOutputStream = mock(javax.servlet.ServletOutputStream.class);
        when(mockResponse.getOutputStream()).thenReturn(mockOutputStream);

        wadoRsApi.retrieveSeriesThumbnail("P", "1", "2", mockResponse);

        verify(mockResponse).setContentType("image/jpeg");
        verify(mockOutputStream).write(mockImageData);
    }

    @Test
    public void testRetrieveInstanceThumbnail_Success() throws Exception {
        byte[] mockImageData = new byte[]{1, 2, 3};
        RenderedInstanceResult mockResult =
                new RenderedInstanceResult(mockImageData, 1, 1, null);

        when(mockDicomService.retrieveThumbnailInstance(any(UserI.class), eq("P"), eq("1"),
                eq("2"), eq("3"), any(), any()))
            .thenReturn(mockResult);

        HttpServletResponse mockResponse = mock(HttpServletResponse.class);
        javax.servlet.ServletOutputStream mockOutputStream = mock(javax.servlet.ServletOutputStream.class);
        when(mockResponse.getOutputStream()).thenReturn(mockOutputStream);

        wadoRsApi.retrieveInstanceThumbnail("P", "1", "2", "3", mockResponse);

        verify(mockResponse).setContentType("image/jpeg");
        verify(mockOutputStream).write(mockImageData);
    }

    @Test
    public void testRetrieveFrameThumbnail_Success() throws Exception {
        byte[] mockImageData = new byte[]{1, 2, 3};
        RenderedInstanceResult mockResult =
                new RenderedInstanceResult(mockImageData, 10, 5, null);

        when(mockDicomService.retrieveThumbnailFrame(any(UserI.class), eq("P"), eq("1"),
                eq("2"), eq("3"), eq("5"), any(), any()))
            .thenReturn(mockResult);

        HttpServletResponse mockResponse = mock(HttpServletResponse.class);
        javax.servlet.ServletOutputStream mockOutputStream = mock(javax.servlet.ServletOutputStream.class);
        when(mockResponse.getOutputStream()).thenReturn(mockOutputStream);

        wadoRsApi.retrieveFrameThumbnail("P", "1", "2", "3", "5", mockResponse);

        verify(mockResponse).setContentType("image/jpeg");
        verify(mockOutputStream).write(mockImageData);
    }

    @Test(expected = ResourceNotFoundException.class)
    public void testRetrieveStudyThumbnail_NotFound() throws Exception {
        when(mockDicomService.retrieveThumbnailStudy(any(UserI.class), anyString(), anyString(), any(), any()))
            .thenThrow(new ResourceNotFoundException("Study", "1.2.3"));

        HttpServletResponse mockResponse = mock(HttpServletResponse.class);

        wadoRsApi.retrieveStudyThumbnail("P", "1.2.3", mockResponse);
    }

    @Test(expected = ResourceNotFoundException.class)
    public void testRetrieveInstanceThumbnail_NotFound() throws Exception {
        when(mockDicomService.retrieveThumbnailInstance(any(UserI.class), anyString(), anyString(),
                anyString(), anyString(), any(), any()))
            .thenThrow(new ResourceNotFoundException("Instance", "1.2.3"));

        HttpServletResponse mockResponse = mock(HttpServletResponse.class);

        wadoRsApi.retrieveInstanceThumbnail("P", "1", "2", "1.2.3", mockResponse);
    }

    // ========== Rendered with RenderingParams Tests ==========

    @Test
    public void testRetrieveInstanceRendered_WithRenderingParams() throws Exception {
        byte[] mockImageData = new byte[]{1, 2, 3, 4};
        RenderedInstanceResult mockResult =
                new RenderedInstanceResult(mockImageData, 1, 1, null);

        when(mockDicomService.retrieveRenderedInstance(any(UserI.class), eq("P"), eq("1"), eq("2"),
                eq("3"), isNull(), eq(ImageFormat.JPEG), any(RenderingParams.class)))
            .thenReturn(mockResult);

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getHeader("Accept")).thenReturn("image/jpeg");

        HttpServletResponse mockResponse = mock(HttpServletResponse.class);
        javax.servlet.ServletOutputStream mockOutputStream = mock(javax.servlet.ServletOutputStream.class);
        when(mockResponse.getOutputStream()).thenReturn(mockOutputStream);

        wadoRsApi.retrieveInstanceRendered("P", "1", "2", "3", null, null,
                "640,480", "400,2000", "85", mockRequest, mockResponse);

        verify(mockDicomService).retrieveRenderedInstance(
                any(UserI.class), eq("P"), eq("1"), eq("2"), eq("3"),
                isNull(), eq(ImageFormat.JPEG), any(RenderingParams.class));
    }

    @Test
    public void testRetrieveStudyRendered_ContentNegotiation_Gif() throws Exception {
        byte[] mockImageData = new byte[]{1, 2, 3, 4};
        RenderedInstanceResult mockResult =
                new RenderedInstanceResult(mockImageData, 1, 1, null, ImageFormat.GIF);

        when(mockDicomService.retrieveRenderedStudy(any(UserI.class), anyString(), anyString(),
                any(), eq(ImageFormat.GIF), any()))
            .thenReturn(mockResult);

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getHeader("Accept")).thenReturn("image/gif");

        HttpServletResponse mockResponse = mock(HttpServletResponse.class);
        javax.servlet.ServletOutputStream mockOutputStream = mock(javax.servlet.ServletOutputStream.class);
        when(mockResponse.getOutputStream()).thenReturn(mockOutputStream);

        wadoRsApi.retrieveStudyRendered("P", "1", null, null,
                null, null, null, mockRequest, mockResponse);

        verify(mockDicomService).retrieveRenderedStudy(
                any(UserI.class), eq("P"), eq("1"),
                isNull(), eq(ImageFormat.GIF), isNull());
    }

    @Test
    public void testRetrieveStudyRendered_NoAcceptHeader_DefaultsToJpeg() throws Exception {
        byte[] mockImageData = new byte[]{1, 2, 3, 4};
        RenderedInstanceResult mockResult =
                new RenderedInstanceResult(mockImageData, 1, 1, null);

        when(mockDicomService.retrieveRenderedStudy(any(UserI.class), anyString(), anyString(),
                any(), eq(ImageFormat.JPEG), any()))
            .thenReturn(mockResult);

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        // No Accept header

        HttpServletResponse mockResponse = mock(HttpServletResponse.class);
        javax.servlet.ServletOutputStream mockOutputStream = mock(javax.servlet.ServletOutputStream.class);
        when(mockResponse.getOutputStream()).thenReturn(mockOutputStream);

        wadoRsApi.retrieveStudyRendered("P", "1", null, null,
                null, null, null, mockRequest, mockResponse);

        verify(mockDicomService).retrieveRenderedStudy(
                any(UserI.class), eq("P"), eq("1"),
                isNull(), eq(ImageFormat.JPEG), isNull());
    }
}
