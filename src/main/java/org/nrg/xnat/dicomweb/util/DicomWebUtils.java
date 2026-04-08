package org.nrg.xnat.dicomweb.util;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.BulkData;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomInputStream;
import org.dcm4che3.io.SAXWriter;
import org.dcm4che3.json.JSONWriter;

import jakarta.json.Json;
import jakarta.json.stream.JsonGenerator;
import javax.xml.transform.sax.SAXTransformerFactory;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;

/**
 * Utility class for DICOMweb operations
 */
public class DicomWebUtils {

    /**
     * Convert DICOM Attributes to JSON string (without BulkDataURI substitution)
     */
    public static String toJson(Attributes attrs) throws IOException {
        StringWriter sw = new StringWriter();
        try (JsonGenerator gen = Json.createGenerator(sw)) {
            JSONWriter writer = new JSONWriter(gen);
            writer.write(attrs);
        }
        return sw.toString();
    }

    /**
     * Convert DICOM Attributes to JSON string with BulkDataURI substitution.
     *
     * Large attributes (PixelData, etc.) will be replaced with BulkDataURI references
     * per DICOM PS3.18 Section 6.5.6.
     *
     * @param attrs DICOM attributes
     * @param baseUri Base URI for BulkDataURI generation (e.g., "http://localhost:8080/xapi/dicomweb/projects/TestProject")
     * @param studyUID Study Instance UID
     * @param seriesUID Series Instance UID
     * @param instanceUID SOP Instance UID
     * @return JSON string with BulkDataURI references
     * @throws IOException if conversion fails
     */
    public static String toJsonWithBulkDataURI(Attributes attrs, String baseUri,
                                                String studyUID, String seriesUID, String instanceUID) throws IOException {
        // First convert to normal JSON
        String normalJson = toJson(attrs);

        // Post-process JSON to replace BulkData with BulkDataURI
        // This is a simple approach: manually construct BulkDataURI for known bulk data tags
        return replaceBulkDataWithURI(normalJson, attrs, baseUri, studyUID, seriesUID, instanceUID);
    }

    /**
     * Replace bulk data representations in JSON with BulkDataURI references.
     * This processes the JSON string and replaces large binary data attributes.
     */
    private static String replaceBulkDataWithURI(String json, Attributes attrs, String baseUri,
                                                  String studyUID, String seriesUID, String instanceUID) {
        String result = json;

        // Check for PixelData and other bulk data tags
        if (attrs.contains(Tag.PixelData)) {
            VR vr = attrs.getVR(Tag.PixelData);
            Object value = attrs.getValue(Tag.PixelData);

            if (vr != null && BulkDataHandler.shouldUseBulkDataURI(Tag.PixelData, vr, value)) {
                String bulkDataURI = BulkDataHandler.generateBulkDataURI(baseUri, studyUID, seriesUID, instanceUID, Tag.PixelData);

                // Replace PixelData entry with BulkDataURI
                // Pattern: "7FE00010":{...} -> "7FE00010":{"vr":"OW","BulkDataURI":"..."}
                result = result.replaceAll(
                    "\"7FE00010\"\\s*:\\s*\\{[^}]*\\}",
                    String.format("\"7FE00010\":{\"vr\":\"%s\",\"BulkDataURI\":\"%s\"}", vr.toString(), bulkDataURI)
                );
            }
        }

        // Add more bulk data tags as needed
        // FloatPixelData
        if (attrs.contains(Tag.FloatPixelData)) {
            VR vr = attrs.getVR(Tag.FloatPixelData);
            Object value = attrs.getValue(Tag.FloatPixelData);

            if (vr != null && BulkDataHandler.shouldUseBulkDataURI(Tag.FloatPixelData, vr, value)) {
                String bulkDataURI = BulkDataHandler.generateBulkDataURI(baseUri, studyUID, seriesUID, instanceUID, Tag.FloatPixelData);

                result = result.replaceAll(
                    "\"7FE00008\"\\s*:\\s*\\{[^}]*\\}",
                    String.format("\"7FE00008\":{\"vr\":\"%s\",\"BulkDataURI\":\"%s\"}", vr.toString(), bulkDataURI)
                );
            }
        }

        return result;
    }

    /**
     * Read DICOM attributes from input stream (includes all bulk data by default)
     *
     * @param is Input stream containing DICOM data
     * @return DICOM attributes including all bulk data (PixelData, etc.) and FileMetaInformation
     * @throws IOException if reading fails
     */
    public static Attributes readDicom(InputStream is) throws IOException {
        return readDicom(is, true);
    }

    /**
     * Read DICOM attributes from input stream with option to exclude bulk data
     *
     * <p>Note: "Bulk Data" in DICOM refers to large binary attributes with VRs: OB, OD, OF, OL, OW, UN, UC, UR, UT.
     * The most common bulk data is PixelData (7FE0,0010), which typically comprises 95%+ of file size.
     * Other bulk data includes: OverlayData, WaveformData, AudioSampleData, EncapsulatedDocument, etc.</p>
     *
     * <p>When includePixelData=false, bulk data is replaced with URI references (BulkData objects)
     * containing offset/length information, which can save 96-99% memory while preserving data location.</p>
     *
     * @param is Input stream containing DICOM data
     * @param includePixelData If false, all bulk data will be replaced with URI references to save memory.
     *                         If true, all bulk data will be included as actual binary content.
     * @return DICOM attributes including FileMetaInformation
     * @throws IOException if reading fails
     * @see DicomInputStream.IncludeBulkData
     */
    public static Attributes readDicom(InputStream is, boolean includePixelData) throws IOException {
        try (DicomInputStream dis = new DicomInputStream(is)) {
            // Configure whether to include bulk data (PixelData, etc.)
            if (includePixelData) {
                dis.setIncludeBulkData(DicomInputStream.IncludeBulkData.YES);
            } else {
                // Use URI mode instead of NO to preserve bulk data location info (offset/length)
                // This allows accessing bulk data later if needed, while saving memory now
                dis.setIncludeBulkData(DicomInputStream.IncludeBulkData.URI);
            }

            // Read FileMetaInformation
            Attributes fmi = dis.readFileMetaInformation();

            // Read dataset
            Attributes dataset = dis.readDataset();

            // Merge FileMetaInformation into dataset
            if (fmi != null) {
                dataset.addAll(fmi);
            }

            return dataset;
        }
    }

    /**
     * Convert DICOM Attributes to XML string (Native DICOM Model format per PS3.19)
     */
    public static String toXml(Attributes attrs) throws Exception {
        StringWriter sw = new StringWriter();
        SAXTransformerFactory tf = (SAXTransformerFactory) SAXTransformerFactory.newInstance();
        TransformerHandler th = tf.newTransformerHandler();
        th.setResult(new StreamResult(sw));

        SAXWriter writer = new SAXWriter(th);
        writer.setIncludeNamespaceDeclaration(true);
        writer.write(attrs);

        return sw.toString();
    }

    /**
     * Convert DICOM Attributes to XML string with BulkDataURI substitution.
     *
     * Large attributes (PixelData, etc.) will be replaced with BulkDataURI references
     * per DICOM PS3.18 Section 6.5.6.
     *
     * @param attrs DICOM attributes
     * @param baseUri Base URI for BulkDataURI generation
     * @param studyUID Study Instance UID
     * @param seriesUID Series Instance UID
     * @param instanceUID SOP Instance UID
     * @return XML string with BulkDataURI references
     * @throws Exception if conversion fails
     */
    public static String toXmlWithBulkDataURI(Attributes attrs, String baseUri,
                                               String studyUID, String seriesUID, String instanceUID) throws Exception {
        // For XML, we need to clone the attributes and replace bulk data with BulkDataURI
        Attributes modified = new Attributes(attrs);

        // Replace PixelData with BulkDataURI
        if (modified.contains(Tag.PixelData)) {
            VR vr = modified.getVR(Tag.PixelData);
            Object value = modified.getValue(Tag.PixelData);

            if (vr != null && BulkDataHandler.shouldUseBulkDataURI(Tag.PixelData, vr, value)) {
                String bulkDataURI = BulkDataHandler.generateBulkDataURI(baseUri, studyUID, seriesUID, instanceUID, Tag.PixelData);
                // Replace with BulkData object containing URI
                modified.setValue(Tag.PixelData, vr, new BulkData(null, bulkDataURI, false));
            }
        }

        // Replace FloatPixelData with BulkDataURI
        if (modified.contains(Tag.FloatPixelData)) {
            VR vr = modified.getVR(Tag.FloatPixelData);
            Object value = modified.getValue(Tag.FloatPixelData);

            if (vr != null && BulkDataHandler.shouldUseBulkDataURI(Tag.FloatPixelData, vr, value)) {
                String bulkDataURI = BulkDataHandler.generateBulkDataURI(baseUri, studyUID, seriesUID, instanceUID, Tag.FloatPixelData);
                modified.setValue(Tag.FloatPixelData, vr, new BulkData(null, bulkDataURI, false));
            }
        }

        return toXml(modified);
    }

    /**
     * Get content type for DICOM JSON
     */
    public static String getDicomJsonContentType() {
        return "application/dicom+json";
    }

    /**
     * Get content type for DICOM XML
     */
    public static String getDicomXmlContentType() {
        return "application/dicom+xml";
    }

    /**
     * Get content type for multipart related DICOM
     */
    public static String getMultipartContentType(String boundary) {
        return "multipart/related; type=\"application/dicom\"; boundary=" + boundary;
    }
}
