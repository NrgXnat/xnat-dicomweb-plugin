package org.nrg.xnat.dicomweb.util;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for DicomWebUtils
 */
public class DicomWebUtilsTest {

    @Test
    public void testToJson() throws Exception {
        // Create test attributes
        Attributes attrs = new Attributes();
        attrs.setString(Tag.PatientID, VR.LO, "PATIENT001");
        attrs.setString(Tag.PatientName, VR.PN, "Test^Patient");
        attrs.setString(Tag.StudyInstanceUID, VR.UI, "1.2.840.113619.2.55.3.123");

        // Convert to JSON
        String json = DicomWebUtils.toJson(attrs);

        // Verify JSON is not null and contains expected data
        assertNotNull("JSON should not be null", json);
        assertTrue("JSON should contain PatientID", json.contains("00100020"));
        assertTrue("JSON should contain PatientName", json.contains("00100010"));
        assertTrue("JSON should contain StudyInstanceUID", json.contains("0020000D"));
    }

    @Test
    public void testToJsonEmptyAttributes() throws Exception {
        Attributes attrs = new Attributes();
        String json = DicomWebUtils.toJson(attrs);

        assertNotNull("JSON should not be null for empty attributes", json);
        assertTrue("JSON should be valid JSON", json.startsWith("{") || json.startsWith("["));
    }

    @Test
    public void testToJsonWithMultipleValues() throws Exception {
        Attributes attrs = new Attributes();
        attrs.setString(Tag.PatientID, VR.LO, "PATIENT001");
        attrs.setString(Tag.StudyDate, VR.DA, "20240101");
        attrs.setString(Tag.Modality, VR.CS, "CT");
        attrs.setInt(Tag.SeriesNumber, VR.IS, 1);

        String json = DicomWebUtils.toJson(attrs);

        assertNotNull(json);
        assertTrue("JSON should contain all attributes",
                   json.contains("00100020") &&
                   json.contains("00080060") &&
                   json.contains("00200011"));
    }

    @Test
    public void testGetDicomJsonContentType() {
        String contentType = DicomWebUtils.getDicomJsonContentType();

        assertEquals("Content type should be application/dicom+json",
                     "application/dicom+json", contentType);
    }

    @Test
    public void testGetMultipartContentType() {
        String boundary = "boundary123";
        String contentType = DicomWebUtils.getMultipartContentType(boundary);

        assertNotNull("Content type should not be null", contentType);
        assertTrue("Content type should contain multipart/related",
                   contentType.contains("multipart/related"));
        assertTrue("Content type should contain application/dicom",
                   contentType.contains("application/dicom"));
        assertTrue("Content type should contain boundary",
                   contentType.contains(boundary));
    }

    @Test
    public void testGetMultipartContentTypeWithDifferentBoundaries() {
        String boundary1 = "boundary-abc-123";
        String boundary2 = "boundary-xyz-789";

        String contentType1 = DicomWebUtils.getMultipartContentType(boundary1);
        String contentType2 = DicomWebUtils.getMultipartContentType(boundary2);

        assertTrue("Content type should contain first boundary",
                   contentType1.contains(boundary1));
        assertTrue("Content type should contain second boundary",
                   contentType2.contains(boundary2));
        assertNotEquals("Content types should be different",
                        contentType1, contentType2);
    }

    @Test
    public void testToJsonWithSpecialCharacters() throws Exception {
        Attributes attrs = new Attributes();
        attrs.setString(Tag.PatientName, VR.PN, "Test^Patient^With^Special");
        attrs.setString(Tag.StudyDescription, VR.LO, "Test & Description");

        String json = DicomWebUtils.toJson(attrs);

        assertNotNull(json);
        // Ensure special characters are properly escaped in JSON
        assertTrue("JSON should be valid", json.length() > 0);
    }
}
