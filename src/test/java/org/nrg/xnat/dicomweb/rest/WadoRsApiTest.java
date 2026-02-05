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

    @Test(expected = org.nrg.xnat.dicomweb.exceptions.ResourceNotFoundException.class)
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

    @Test(expected = org.nrg.xnat.dicomweb.exceptions.ResourceNotFoundException.class)
    public void testRetrieveInstance_NotFound() throws Exception {
        // Arrange
        String projectId = "TestProject";
        String studyUID = "1.2.3.4.5";
        String seriesUID = "1.2.3.4.5.100";
        String instanceUID = "1.2.3.4.5.6.999";

        when(mockDicomService.retrieveInstance(any(UserI.class), eq(projectId), eq(studyUID),
                eq(seriesUID), eq(instanceUID)))
            .thenReturn(null);

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

        when(mockDicomService.getInstanceAttributes(any(UserI.class), eq(projectId), eq(studyUID),
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

    @Test(expected = org.nrg.xnat.dicomweb.exceptions.ResourceNotFoundException.class)
    public void testRetrieveInstanceMetadata_NotFound() throws Exception {
        // Arrange
        String projectId = "TestProject";
        String studyUID = "1.2.3.4.5";
        String seriesUID = "1.2.3.4.5.100";
        String instanceUID = "1.2.3.4.5.6.999";

        when(mockDicomService.getInstanceAttributes(any(UserI.class), eq(projectId), eq(studyUID),
                eq(seriesUID), eq(instanceUID)))
            .thenReturn(null);

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

    @Test(expected = org.nrg.xnat.dicomweb.exceptions.ResourceNotFoundException.class)
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

    @Test(expected = org.nrg.xnat.dicomweb.exceptions.ResourceNotFoundException.class)
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

    @Test(expected = org.nrg.xnat.dicomweb.exceptions.ResourceNotFoundException.class)
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
                eq(seriesUID), eq(instanceUID), any(), any()))
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

    @Test(expected = org.nrg.xnat.dicomweb.exceptions.ResourceNotFoundException.class)
    public void testRetrieveInstanceRendered_NotFound() throws Exception {
        // Arrange
        String projectId = "TestProject";
        String studyUID = "1.2.3.4.5";
        String seriesUID = "1.2.3.4.5.100";
        String instanceUID = "1.2.3.4.5.6.999";

        when(mockDicomService.retrieveRenderedInstance(any(UserI.class), eq(projectId), eq(studyUID),
                eq(seriesUID), eq(instanceUID), any(), any()))
            .thenReturn(null);

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
                eq(seriesUID), eq(instanceUID), eq(frameNumber), any()))
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

    @Test(expected = org.nrg.xnat.dicomweb.exceptions.ResourceNotFoundException.class)
    public void testRetrieveFrames_NotFound() throws Exception {
        // Arrange
        String projectId = "TestProject";
        String studyUID = "1.2.3.4.5";
        String seriesUID = "1.2.3.4.5.100";
        String instanceUID = "1.2.3.4.5.6.999";
        String frameList = "1";

        when(mockDicomService.retrieveFrames(any(UserI.class), eq(projectId), eq(studyUID),
                eq(seriesUID), eq(instanceUID), eq(frameList)))
            .thenReturn(new ArrayList<>());

        // Act - should throw ResourceNotFoundException
        wadoRsApi.retrieveFrames(projectId, studyUID, seriesUID, instanceUID, frameList, null);
    }

    @Test(expected = org.nrg.xnat.dicomweb.exceptions.ResourceNotFoundException.class)
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
}
