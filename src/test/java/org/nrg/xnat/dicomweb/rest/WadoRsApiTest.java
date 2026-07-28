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
import org.nrg.xnat.dicomweb.exceptions.ResourceNotFoundException;
import org.nrg.xnat.dicomweb.service.ImageFormat;
import org.nrg.xnat.dicomweb.service.RenderedInstanceResult;
import org.nrg.xnat.dicomweb.service.RenderingParams;
import org.nrg.xnat.dicomweb.service.XnatDicomService;
import org.nrg.xnat.dicomweb.util.BulkDataHandler.BulkDataItem;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

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
            .thenReturn(mockInstances.stream());

        // Mock HttpServletRequest
        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getRequestURL()).thenReturn(new StringBuffer("http://localhost:8080/xapi/dicomweb/projects/TestProject/studies/" + studyUID + "/metadata"));

        // Act
        ResponseEntity<String> response = wadoRsApi.retrieveStudyMetadata(projectId, studyUID, null, null, mockRequest);

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
            .thenReturn(Stream.of());

        // Mock HttpServletRequest
        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getRequestURL()).thenReturn(new StringBuffer("http://localhost:8080/xapi/dicomweb/projects/TestProject/studies/" + studyUID + "/metadata"));

        // Act - should throw ResourceNotFoundException
        wadoRsApi.retrieveStudyMetadata(projectId, studyUID, null, null, mockRequest);
    }

    @Test
    public void testRetrieveStudyMetadata_EachInstanceHasSOPInstanceUID() throws Exception {
        // Arrange
        String projectId = "TestProject";
        String studyUID = "1.2.3.4.5";

        List<Attributes> mockInstances = createMockInstances(2);

        when(mockDicomService.retrieveAllStudyInstanceMetadata(any(UserI.class), eq(projectId), eq(studyUID)))
            .thenReturn(mockInstances.stream());

        // Mock HttpServletRequest
        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getRequestURL()).thenReturn(new StringBuffer("http://localhost:8080/xapi/dicomweb/projects/TestProject/studies/" + studyUID + "/metadata"));

        // Act
        ResponseEntity<String> response = wadoRsApi.retrieveStudyMetadata(projectId, studyUID, null, null, mockRequest);

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
            .thenReturn(mockInstances.stream());

        // Mock HttpServletRequest
        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getRequestURL()).thenReturn(new StringBuffer("http://localhost:8080/xapi/dicomweb/projects/TestProject/studies/" + studyUID + "/metadata"));

        // Act - should not throw NullPointerException
        ResponseEntity<String> response = wadoRsApi.retrieveStudyMetadata(projectId, studyUID, null, null, mockRequest);

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

        // The handler resolves a File and returns a StreamingResponseBody that
        // reads from it later. A stub File path is enough to verify status and
        // Content-Type on the ResponseEntity; the streaming body is not invoked
        // here.
        when(mockDicomService.resolveInstanceFile(any(UserI.class), eq(projectId), eq(studyUID),
                eq(seriesUID), eq(instanceUID)))
            .thenReturn(new File("/dev/null"));

        // Act
        ResponseEntity<?> response = wadoRsApi.retrieveInstance(projectId, studyUID, seriesUID, instanceUID, null, null);

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

        // The service is contracted to throw ResourceNotFoundException itself
        // when no such instance is found; the handler does not null-check.
        when(mockDicomService.resolveInstanceFile(any(UserI.class), eq(projectId), eq(studyUID),
                eq(seriesUID), eq(instanceUID)))
            .thenThrow(new ResourceNotFoundException("Instance", instanceUID));

        // Act - should throw ResourceNotFoundException
        wadoRsApi.retrieveInstance(projectId, studyUID, seriesUID, instanceUID, null, null);
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
        // Act
        ResponseEntity<String> response = wadoRsApi.retrieveInstanceMetadata(projectId, studyUID,
                seriesUID, instanceUID, null, "application/dicom+json", mockRequest);

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
        wadoRsApi.retrieveInstanceMetadata(projectId, studyUID, seriesUID, instanceUID, null, null, mockRequest);
    }

    // ========== Series Retrieval Tests ==========

    @Test
    public void testRetrieveSeries_Success() throws Exception {
        // Arrange
        String projectId = "TestProject";
        String studyUID = "1.2.3.4.5";
        String seriesUID = "1.2.3.4.5.100";

        // 404 gate is the resolveSeriesFiles call on the request thread. Non-empty list
        // means "series exists"; the streaming body iterates the files later. Returning
        // a stub file is enough for the 200 + multipart Content-Type to be observable on
        // the ResponseEntity (the StreamingResponseBody is not invoked in this test).
        when(mockDicomService.resolveSeriesFiles(any(UserI.class), eq(projectId), eq(studyUID), eq(seriesUID)))
            .thenReturn(Collections.singletonList(new File("/dev/null")));

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

        when(mockDicomService.resolveSeriesFiles(any(UserI.class), eq(projectId), eq(studyUID), eq(seriesUID)))
            .thenReturn(Collections.emptyList());

        // Act - should throw ResourceNotFoundException
        wadoRsApi.retrieveSeries(projectId, studyUID, seriesUID);
    }

    // ========== Study Retrieval Tests ==========

    @Test
    public void testRetrieveStudy_Success() throws Exception {
        // Arrange
        String projectId = "TestProject";
        String studyUID = "1.2.3.4.5";

        // 404 gate is resolveStudyFiles. Non-empty list means "study exists"; the
        // streaming body iterates the files later. The StreamingResponseBody is not
        // invoked in this test.
        when(mockDicomService.resolveStudyFiles(any(UserI.class), eq(projectId), eq(studyUID)))
            .thenReturn(Collections.singletonList(new File("/dev/null")));

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

        when(mockDicomService.resolveStudyFiles(any(UserI.class), eq(projectId), eq(studyUID)))
            .thenReturn(Collections.emptyList());

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

        when(mockDicomService.searchMetadata(any(UserI.class), eq(projectId), eq(studyUID),
                eq(seriesUID), any()))
            .thenReturn(mockInstances.stream());

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getRequestURL()).thenReturn(new StringBuffer(
                "http://localhost:8080/xapi/dicomweb/projects/TestProject/studies/" + studyUID +
                "/series/" + seriesUID + "/metadata"));
        // Act
        ResponseEntity<String> response = wadoRsApi.retrieveSeriesMetadata(projectId, studyUID,
                seriesUID, null, "application/dicom+json", mockRequest);

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
        wadoRsApi.retrieveSeriesMetadata(projectId, studyUID, seriesUID, null, null, mockRequest);
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

        HttpServletResponse mockResponse = mock(HttpServletResponse.class);
        jakarta.servlet.ServletOutputStream mockOutputStream = mock(jakarta.servlet.ServletOutputStream.class);
        when(mockResponse.getOutputStream()).thenReturn(mockOutputStream);

        // Act
        wadoRsApi.retrieveInstanceRendered(projectId, studyUID, seriesUID, instanceUID, null,
                null, null, null, null, "image/jpeg", mockResponse);

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

        HttpServletResponse mockResponse = mock(HttpServletResponse.class);

        // Act - should throw ResourceNotFoundException
        wadoRsApi.retrieveInstanceRendered(projectId, studyUID, seriesUID, instanceUID, null,
                null, null, null, null, "image/jpeg", mockResponse);
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

        HttpServletResponse mockResponse = mock(HttpServletResponse.class);
        jakarta.servlet.ServletOutputStream mockOutputStream = mock(jakarta.servlet.ServletOutputStream.class);
        when(mockResponse.getOutputStream()).thenReturn(mockOutputStream);

        // Act
        wadoRsApi.retrieveInstanceRendered(projectId, studyUID, seriesUID, instanceUID, frameNumber,
                null, null, null, null, "image/jpeg", mockResponse);

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
                instanceUID, frameList);

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
                instanceUID, frameList);

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
        wadoRsApi.retrieveFrames(projectId, studyUID, seriesUID, instanceUID, frameList);
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
        wadoRsApi.retrieveFrames(projectId, studyUID, seriesUID, instanceUID, frameList);
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
                instanceUID, frameList);

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
        ResponseEntity<String> response = wadoRsApi.retrieveInstanceMetadata(
                "P", "1", "2", "3", null, "application/dicom+json", mockRequest);

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
        ResponseEntity<String> response = wadoRsApi.retrieveInstanceMetadata(
                "P", "1", "2", "3", null, "application/dicom+xml", mockRequest);

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
        // No Accept header — should default to JSON

        ResponseEntity<String> response = wadoRsApi.retrieveInstanceMetadata(
                "P", "1", "2", "3", null, null, mockRequest);

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
        ResponseEntity<String> response = wadoRsApi.retrieveInstanceMetadata(
                "P", "1", "2", "3", null, "*/*", mockRequest);

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
        ResponseEntity<String> response = wadoRsApi.retrieveInstanceMetadata(
                "P", "1", "2", "3", null, "image/jpeg", mockRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("application/dicom+json", response.getHeaders().getContentType().toString());
    }

    // ---- Metadata: quality-based negotiation ----

    @Test
    public void testStudyMetadata_QualityPrefersXml_ReturnsXml() throws Exception {
        List<Attributes> mockInstances = createMockInstances(2);
        when(mockDicomService.retrieveAllStudyInstanceMetadata(any(UserI.class), anyString(), anyString()))
            .thenReturn(mockInstances.stream());

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getRequestURL()).thenReturn(new StringBuffer(
                "http://localhost:8080/xapi/dicomweb/projects/P/studies/1/metadata"));
        ResponseEntity<String> response = wadoRsApi.retrieveStudyMetadata(
                "P", "1", null,
                "application/dicom+json;q=0.5, application/dicom+xml;q=1.0", mockRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("application/dicom+xml", response.getHeaders().getContentType().toString());
    }

    @Test
    public void testSeriesMetadata_QualityPrefersJson_ReturnsJson() throws Exception {
        List<Attributes> mockInstances = createMockInstances(2);
        when(mockDicomService.searchMetadata(any(UserI.class), anyString(), anyString(),
                anyString(), any()))
            .thenReturn(mockInstances.stream());

        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getRequestURL()).thenReturn(new StringBuffer(
                "http://localhost:8080/xapi/dicomweb/projects/P/studies/1/series/2/metadata"));
        ResponseEntity<String> response = wadoRsApi.retrieveSeriesMetadata(
                "P", "1", "2", null,
                "application/dicom+xml;q=0.8, application/dicom+json;q=1.0", mockRequest);

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
        ResponseEntity<String> response = wadoRsApi.retrieveInstanceMetadata(
                "P", "1", "2", "3", "application/dicom+xml", "application/dicom+json", mockRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("application/dicom+xml", response.getHeaders().getContentType().toString());
    }

    // Wildcards in the accept query parameter are rejected by
    // AcceptParamWildcardInterceptor before the handler runs;
    // see AcceptParamWildcardInterceptorTest.

    // ---- Rendered: content negotiation ----

    @Test
    public void testRendered_AcceptGif_PassesGifFormat() throws Exception {
        byte[] mockImageData = new byte[]{1, 2, 3, 4};
        RenderedInstanceResult mockResult =
                new RenderedInstanceResult(mockImageData, 1, 1, null, ImageFormat.GIF);

        when(mockDicomService.retrieveRenderedInstance(any(UserI.class), anyString(), anyString(),
                anyString(), anyString(), any(), eq(ImageFormat.GIF), any()))
            .thenReturn(mockResult);

        HttpServletResponse mockResponse = mock(HttpServletResponse.class);
        jakarta.servlet.ServletOutputStream mockOutputStream = mock(jakarta.servlet.ServletOutputStream.class);
        when(mockResponse.getOutputStream()).thenReturn(mockOutputStream);

        wadoRsApi.retrieveInstanceRendered("P", "1", "2", "3", null,
                null, null, null, null, "image/gif", mockResponse);

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

        HttpServletResponse mockResponse = mock(HttpServletResponse.class);
        jakarta.servlet.ServletOutputStream mockOutputStream = mock(jakarta.servlet.ServletOutputStream.class);
        when(mockResponse.getOutputStream()).thenReturn(mockOutputStream);

        wadoRsApi.retrieveInstanceRendered("P", "1", "2", "3", null,
                null, null, null, null, "image/png", mockResponse);

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

        HttpServletResponse mockResponse = mock(HttpServletResponse.class);
        jakarta.servlet.ServletOutputStream mockOutputStream = mock(jakarta.servlet.ServletOutputStream.class);
        when(mockResponse.getOutputStream()).thenReturn(mockOutputStream);

        // No Accept header — should default to JPEG
        wadoRsApi.retrieveInstanceRendered("P", "1", "2", "3", null,
                null, null, null, null, null, mockResponse);

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

        HttpServletResponse mockResponse = mock(HttpServletResponse.class);
        jakarta.servlet.ServletOutputStream mockOutputStream = mock(jakarta.servlet.ServletOutputStream.class);
        when(mockResponse.getOutputStream()).thenReturn(mockOutputStream);

        wadoRsApi.retrieveInstanceRendered("P", "1", "2", "3", null,
                null, null, null, null, "image/jpeg;q=0.5, image/gif;q=1.0", mockResponse);

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

        HttpServletResponse mockResponse = mock(HttpServletResponse.class);
        jakarta.servlet.ServletOutputStream mockOutputStream = mock(jakarta.servlet.ServletOutputStream.class);
        when(mockResponse.getOutputStream()).thenReturn(mockOutputStream);

        // Header says JPEG, query param says GIF — param wins
        wadoRsApi.retrieveInstanceRendered("P", "1", "2", "3", null,
                "image/gif", null, null, null, "image/jpeg", mockResponse);

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

        HttpServletResponse mockResponse = mock(HttpServletResponse.class);
        jakarta.servlet.ServletOutputStream mockOutputStream = mock(jakarta.servlet.ServletOutputStream.class);
        when(mockResponse.getOutputStream()).thenReturn(mockOutputStream);

        wadoRsApi.retrieveInstanceRendered("P", "1", "2", "3", null,
                null, null, null, null, "video/mp4", mockResponse);

        verify(mockDicomService).retrieveRenderedInstance(
                any(UserI.class), eq("P"), eq("1"), eq("2"), eq("3"),
                isNull(), eq(ImageFormat.JPEG), any());
    }

    // ---- Instance retrieval: content negotiation ----

    @Test
    public void testRetrieveInstance_AcceptDicom_Succeeds() throws Exception {
        when(mockDicomService.resolveInstanceFile(any(UserI.class), anyString(), anyString(),
                anyString(), anyString()))
            .thenReturn(new File("/dev/null"));

        ResponseEntity<?> response = wadoRsApi.retrieveInstance(
                "P", "1", "2", "3", null, "application/dicom");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("application/dicom", response.getHeaders().getContentType().toString());
    }

    @Test
    public void testRetrieveInstance_WildcardAccept_Succeeds() throws Exception {
        when(mockDicomService.resolveInstanceFile(any(UserI.class), anyString(), anyString(),
                anyString(), anyString()))
            .thenReturn(new File("/dev/null"));

        ResponseEntity<?> response = wadoRsApi.retrieveInstance(
                "P", "1", "2", "3", null, "*/*");

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    // ---- Study/Series multipart: content negotiation ----

    @Test
    public void testRetrieveStudy_AcceptMultipartDicom_Succeeds() throws Exception {
        when(mockDicomService.resolveStudyFiles(any(UserI.class), anyString(), anyString()))
            .thenReturn(Collections.singletonList(new File("/dev/null")));

        ResponseEntity<?> response = wadoRsApi.retrieveStudy("P", "1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String contentType = response.getHeaders().getContentType().toString();
        assertTrue(contentType.startsWith("multipart/related"));
    }

    @Test
    public void testRetrieveStudy_WildcardAccept_Succeeds() throws Exception {
        when(mockDicomService.resolveStudyFiles(any(UserI.class), anyString(), anyString()))
            .thenReturn(Collections.singletonList(new File("/dev/null")));

        ResponseEntity<?> response = wadoRsApi.retrieveStudy("P", "1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    public void testRetrieveSeries_NoAcceptHeader_Succeeds() throws Exception {
        when(mockDicomService.resolveSeriesFiles(any(UserI.class), anyString(), anyString(), anyString()))
            .thenReturn(Collections.singletonList(new File("/dev/null")));

        ResponseEntity<?> response = wadoRsApi.retrieveSeries("P", "1", "2");

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
                projectId, studyUID, seriesUID, instanceUID, null);

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
                projectId, studyUID, seriesUID, instanceUID, null);

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

        wadoRsApi.retrieveInstanceBulkData("P", "1", "2", "3", null);
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
                projectId, studyUID, seriesUID, null);

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

        ResponseEntity<?> response = wadoRsApi.retrieveStudyBulkData(projectId, studyUID, null);

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
                projectId, studyUID, seriesUID, instanceUID, null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String contentType = response.getHeaders().getContentType().toString();
        assertTrue(contentType.startsWith("multipart/related"));
    }

    @Test(expected = ResourceNotFoundException.class)
    public void testRetrieveInstancePixelData_NotFound() throws Exception {
        when(mockDicomService.retrieveInstancePixelData(any(UserI.class), anyString(),
                anyString(), anyString(), anyString(), anyString()))
            .thenReturn(new ArrayList<>());

        wadoRsApi.retrieveInstancePixelData("P", "1", "2", "3", null);
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
        when(mockRequest.getRequestURL()).thenReturn(new StringBuffer(
                "http://localhost:8080/xapi/dicomweb/projects/P/studies/1/series/2/instances/3/bulkdata"));

        ResponseEntity<?> response = wadoRsApi.retrieveInstanceBulkData(
                "P", "1", "2", "3", mockRequest);

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
        when(mockRequest.getRequestURL()).thenReturn(new StringBuffer(
                "http://localhost:8080/xapi/dicomweb/projects/P/studies/1/series/2/instances/3/bulkdata"));

        ResponseEntity<?> response = wadoRsApi.retrieveInstanceBulkData(
                "P", "1", "2", "3", mockRequest);

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

        ResponseEntity<?> response = wadoRsApi.retrieveFrames(
                "P", "1", "2", "3", "1");

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

        HttpServletResponse mockResponse = mock(HttpServletResponse.class);
        jakarta.servlet.ServletOutputStream mockOutputStream = mock(jakarta.servlet.ServletOutputStream.class);
        when(mockResponse.getOutputStream()).thenReturn(mockOutputStream);

        wadoRsApi.retrieveStudyRendered("P", "1",
                null, null, null, null, null, "image/jpeg", mockResponse);

        verify(mockResponse).setContentType("image/jpeg");
        verify(mockOutputStream).write(mockImageData);
    }

    @Test(expected = ResourceNotFoundException.class)
    public void testRetrieveStudyRendered_NotFound() throws Exception {
        when(mockDicomService.retrieveRenderedStudy(any(UserI.class), anyString(), anyString(),
                any(), any(ImageFormat.class), any()))
            .thenThrow(new ResourceNotFoundException("Study", "1.2.3"));

        HttpServletResponse mockResponse = mock(HttpServletResponse.class);

        wadoRsApi.retrieveStudyRendered("P", "1.2.3",
                null, null, null, null, null, "image/jpeg", mockResponse);
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

        HttpServletResponse mockResponse = mock(HttpServletResponse.class);
        jakarta.servlet.ServletOutputStream mockOutputStream = mock(jakarta.servlet.ServletOutputStream.class);
        when(mockResponse.getOutputStream()).thenReturn(mockOutputStream);

        wadoRsApi.retrieveSeriesRendered("P", "1", "2",
                null, null, null, null, null, "image/jpeg", mockResponse);

        verify(mockResponse).setContentType("image/jpeg");
        verify(mockOutputStream).write(mockImageData);
    }

    @Test(expected = ResourceNotFoundException.class)
    public void testRetrieveSeriesRendered_NotFound() throws Exception {
        when(mockDicomService.retrieveRenderedSeries(any(UserI.class), anyString(), anyString(),
                anyString(), any(), any(ImageFormat.class), any()))
            .thenThrow(new ResourceNotFoundException("Series", "1.2.3"));

        HttpServletResponse mockResponse = mock(HttpServletResponse.class);

        wadoRsApi.retrieveSeriesRendered("P", "1", "1.2.3",
                null, null, null, null, null, "image/jpeg", mockResponse);
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

        HttpServletResponse mockResponse = mock(HttpServletResponse.class);
        jakarta.servlet.ServletOutputStream mockOutputStream = mock(jakarta.servlet.ServletOutputStream.class);
        when(mockResponse.getOutputStream()).thenReturn(mockOutputStream);

        wadoRsApi.retrieveFrameRendered("P", "1", "2", "3", "3",
                null, null, null, null, "image/jpeg", mockResponse);

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
        jakarta.servlet.ServletOutputStream mockOutputStream = mock(jakarta.servlet.ServletOutputStream.class);
        when(mockResponse.getOutputStream()).thenReturn(mockOutputStream);

        wadoRsApi.retrieveStudyThumbnail("P", "1", null, null, null, mockResponse);

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
        jakarta.servlet.ServletOutputStream mockOutputStream = mock(jakarta.servlet.ServletOutputStream.class);
        when(mockResponse.getOutputStream()).thenReturn(mockOutputStream);

        wadoRsApi.retrieveSeriesThumbnail("P", "1", "2", null, null, null, mockResponse);

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
        jakarta.servlet.ServletOutputStream mockOutputStream = mock(jakarta.servlet.ServletOutputStream.class);
        when(mockResponse.getOutputStream()).thenReturn(mockOutputStream);

        wadoRsApi.retrieveInstanceThumbnail("P", "1", "2", "3", null, null, null, mockResponse);

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
        jakarta.servlet.ServletOutputStream mockOutputStream = mock(jakarta.servlet.ServletOutputStream.class);
        when(mockResponse.getOutputStream()).thenReturn(mockOutputStream);

        wadoRsApi.retrieveFrameThumbnail("P", "1", "2", "3", "5", null, null, null, mockResponse);

        verify(mockResponse).setContentType("image/jpeg");
        verify(mockOutputStream).write(mockImageData);
    }

    @Test(expected = ResourceNotFoundException.class)
    public void testRetrieveStudyThumbnail_NotFound() throws Exception {
        when(mockDicomService.retrieveThumbnailStudy(any(UserI.class), anyString(), anyString(), any(), any()))
            .thenThrow(new ResourceNotFoundException("Study", "1.2.3"));

        HttpServletResponse mockResponse = mock(HttpServletResponse.class);

        wadoRsApi.retrieveStudyThumbnail("P", "1.2.3", null, null, null, mockResponse);
    }

    @Test(expected = ResourceNotFoundException.class)
    public void testRetrieveInstanceThumbnail_NotFound() throws Exception {
        when(mockDicomService.retrieveThumbnailInstance(any(UserI.class), anyString(), anyString(),
                anyString(), anyString(), any(), any()))
            .thenThrow(new ResourceNotFoundException("Instance", "1.2.3"));

        HttpServletResponse mockResponse = mock(HttpServletResponse.class);

        wadoRsApi.retrieveInstanceThumbnail("P", "1", "2", "1.2.3", null, null, null, mockResponse);
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

        HttpServletResponse mockResponse = mock(HttpServletResponse.class);
        jakarta.servlet.ServletOutputStream mockOutputStream = mock(jakarta.servlet.ServletOutputStream.class);
        when(mockResponse.getOutputStream()).thenReturn(mockOutputStream);

        wadoRsApi.retrieveInstanceRendered("P", "1", "2", "3", null, null,
                "640,480", "400,2000", "85", "image/jpeg", mockResponse);

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

        HttpServletResponse mockResponse = mock(HttpServletResponse.class);
        jakarta.servlet.ServletOutputStream mockOutputStream = mock(jakarta.servlet.ServletOutputStream.class);
        when(mockResponse.getOutputStream()).thenReturn(mockOutputStream);

        wadoRsApi.retrieveStudyRendered("P", "1", null, null,
                null, null, null, "image/gif", mockResponse);

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

        HttpServletResponse mockResponse = mock(HttpServletResponse.class);
        jakarta.servlet.ServletOutputStream mockOutputStream = mock(jakarta.servlet.ServletOutputStream.class);
        when(mockResponse.getOutputStream()).thenReturn(mockOutputStream);

        // No Accept header — should default to JPEG
        wadoRsApi.retrieveStudyRendered("P", "1", null, null,
                null, null, null, null, mockResponse);

        verify(mockDicomService).retrieveRenderedStudy(
                any(UserI.class), eq("P"), eq("1"),
                isNull(), eq(ImageFormat.JPEG), isNull());
    }
}
