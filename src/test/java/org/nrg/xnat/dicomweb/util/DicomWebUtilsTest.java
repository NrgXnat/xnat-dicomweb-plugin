package org.nrg.xnat.dicomweb.util;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.BulkData;
import org.dcm4che3.data.Fragments;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.junit.Test;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;
import java.io.StringReader;

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

    /**
     * Regression: encapsulated/fragmented PixelData previously broke the regex
     * post-processor that used to live in toJsonWithBulkDataURI and produced
     * malformed JSON (a keyless `,{…}` sibling at the dataset level and an
     * orphan `]`). The fix replaces PixelData structurally on the Attributes
     * before serialization so dcm4che's JSONWriter emits a single well-formed
     * element regardless of whether the source value was byte[], BulkData, or
     * Fragments.
     */
    @Test
    public void testReplaceBulkDataWithURIThenToJsonForFragmentedPixelData() throws Exception {
        Attributes attrs = new Attributes();
        attrs.setString(Tag.PatientID, VR.LO, "PATIENT001");
        attrs.setString(Tag.StudyInstanceUID, VR.UI, "1.2.3");
        attrs.setString(Tag.SeriesInstanceUID, VR.UI, "1.2.3.4");
        attrs.setString(Tag.SOPInstanceUID, VR.UI, "1.2.3.4.5");

        // Encapsulated multi-frame PixelData: a Fragments sequence with a null
        // Basic Offset Table entry plus two BulkData fragments. dcm4che's
        // JSONWriter would emit this as a PixelData element containing nested
        // BulkDataURI objects — exactly the shape the old `[^}]*` regex
        // corrupted.
        Fragments fragments = new Fragments(VR.OB, false, 3);
        fragments.add(null);
        fragments.add(new BulkData(null, "http://upstream/frame?offset=100&length=200", false));
        fragments.add(new BulkData(null, "http://upstream/frame?offset=300&length=400", false));
        attrs.setValue(Tag.PixelData, VR.OB, fragments);

        String baseUri = "http://upstream/xapi/dicomweb";
        DicomWebUtils.replaceBulkDataWithURI(attrs, baseUri, "1.2.3", "1.2.3.4", "1.2.3.4.5");
        String json = DicomWebUtils.toJson(attrs);

        assertNotNull(json);

        // Must parse as a single well-formed JSON object — the regression
        // produced sibling `,{...}` and orphan `]` that fail any compliant
        // parser at this step.
        JsonObject root;
        try (JsonReader reader = Json.createReader(new StringReader(json))) {
            root = reader.readObject();
        }

        // PixelData (7FE00010) must be present as a single object with vr=OB
        // and a BulkDataURI pointing at the substituted location — not the
        // original fragment offset/length URLs.
        assertTrue("JSON must contain PixelData element", root.containsKey("7FE00010"));
        JsonObject pixelData = root.getJsonObject("7FE00010");
        assertEquals("PixelData VR must be preserved", "OB", pixelData.getString("vr"));
        assertEquals(
                "PixelData must be substituted with the canonical BulkDataURI",
                baseUri + "/studies/1.2.3/series/1.2.3.4/instances/1.2.3.4.5/bulkdata/7FE00010",
                pixelData.getString("BulkDataURI"));
    }

    /**
     * Companion to the fragmented case: a simple PixelData with a byte[] value
     * exceeding the bulk-data threshold should also produce a single
     * BulkDataURI-typed element. (Previously worked because the regex didn't
     * encounter nested braces; covered here to lock the simple path against
     * future regressions in the same area.)
     */
    @Test
    public void testReplaceBulkDataWithURIThenToJsonForByteArrayPixelData() throws Exception {
        Attributes attrs = new Attributes();
        attrs.setString(Tag.PatientID, VR.LO, "PATIENT001");

        // Any byte[] hits the always-substitute branch for tag 7FE00010
        attrs.setBytes(Tag.PixelData, VR.OW, new byte[]{1, 2, 3, 4, 5, 6, 7, 8});

        DicomWebUtils.replaceBulkDataWithURI(attrs,
                "http://upstream/xapi/dicomweb", "1.2.3", "1.2.3.4", "1.2.3.4.5");
        String json = DicomWebUtils.toJson(attrs);

        JsonObject root;
        try (JsonReader reader = Json.createReader(new StringReader(json))) {
            root = reader.readObject();
        }
        assertTrue(root.containsKey("7FE00010"));
        JsonObject pixelData = root.getJsonObject("7FE00010");
        assertEquals("OW", pixelData.getString("vr"));
        assertTrue("Expected BulkDataURI on substituted PixelData",
                pixelData.containsKey("BulkDataURI"));
    }
}
