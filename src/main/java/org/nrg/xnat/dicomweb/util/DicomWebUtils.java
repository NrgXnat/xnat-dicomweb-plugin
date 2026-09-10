package org.nrg.xnat.dicomweb.util;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.BulkData;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomInputStream;
import org.dcm4che3.io.SAXWriter;
import org.dcm4che3.json.JSONWriter;

import javax.json.Json;
import javax.json.stream.JsonGenerator;
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
     * Replace PixelData / FloatPixelData on the given Attributes with BulkDataURI
     * references per DICOM PS3.18 Section 6.5.6.
     *
     * <p><b>Mutates {@code attrs} in place.</b> The original pixel data values are
     * overwritten with {@link BulkData} stubs pointing at the canonical BulkDataURI.
     * Callers that need to preserve the original values must clone first
     * (e.g. {@code new Attributes(attrs)}).
     *
     * @param attrs DICOM attributes to mutate
     * @param baseUri Base URI for BulkDataURI generation
     * @param studyUID Study Instance UID
     * @param seriesUID Series Instance UID
     * @param instanceUID SOP Instance UID
     */
    public static void replaceBulkDataWithURI(Attributes attrs, String baseUri,
                                              String studyUID, String seriesUID, String instanceUID) {
        if (attrs.contains(Tag.PixelData)) {
            VR vr = attrs.getVR(Tag.PixelData);
            Object value = attrs.getValue(Tag.PixelData);
            if (vr != null && BulkDataHandler.shouldUseBulkDataURI(Tag.PixelData, vr, value)) {
                String bulkDataURI = BulkDataHandler.generateBulkDataURI(baseUri, studyUID, seriesUID, instanceUID, Tag.PixelData);
                attrs.setValue(Tag.PixelData, vr, new BulkData(null, bulkDataURI, false));
            }
        }

        if (attrs.contains(Tag.FloatPixelData)) {
            VR vr = attrs.getVR(Tag.FloatPixelData);
            Object value = attrs.getValue(Tag.FloatPixelData);
            if (vr != null && BulkDataHandler.shouldUseBulkDataURI(Tag.FloatPixelData, vr, value)) {
                String bulkDataURI = BulkDataHandler.generateBulkDataURI(baseUri, studyUID, seriesUID, instanceUID, Tag.FloatPixelData);
                attrs.setValue(Tag.FloatPixelData, vr, new BulkData(null, bulkDataURI, false));
            }
        }
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
