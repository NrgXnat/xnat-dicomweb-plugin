package org.nrg.xnat.dicomweb.utils;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.BulkData;
import org.dcm4che3.data.ItemPointer;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.BulkDataDescriptor;
import org.nrg.xnat.dicomweb.config.DicomWebProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Utility class for handling DICOM bulk data and generating BulkDataURI references.
 *
 * Per DICOM PS3.18 Section 6.5.6, large attributes should be replaced with BulkDataURI
 * references in metadata responses to reduce response size and improve performance.
 *
 * This is now a Spring component that uses configurable threshold values.
 */
@Component
@Slf4j
public class BulkDataHandler implements BulkDataDescriptor {
    /**
     * Default size threshold (in bytes) above which attributes should use BulkDataURI.
     * Used for static methods and as fallback.
     * Default: 1024 bytes (1KB)
     */
    private static final int DEFAULT_BULK_DATA_THRESHOLD = 1024;

    /**
     * Configured bulk data threshold
     */
    private final int bulkDataThreshold;

    /**
     * DICOM tags that should always use BulkDataURI regardless of size.
     * These are typically large binary data elements.
     */
    private static final Set<Integer> BULK_DATA_TAGS = IntStream.of(
                    Tag.PixelData,              // 7FE0,0010
                    Tag.FloatPixelData,         // 7FE0,0008
                    Tag.DoubleFloatPixelData,   // 7FE0,0009
                    Tag.OverlayData,            // 60xx,3000
                    Tag.AudioSampleData,        // 003A,0208
                    Tag.CurveData,              // 50xx,3000
                    Tag.SpectroscopyData,       // 5600,0020
                    Tag.EncapsulatedDocument,   // 0042,0011
                    Tag.WaveformData            // 5400,1010
            )
            .boxed()
            .collect(Collectors.toSet());
    /**
     * Value Representations that can contain bulk data.
     */
    private static final Set<VR> BULK_DATA_VRS = Stream.of(VR.OB, VR.OD, VR.OF, VR.OL, VR.OV, VR.OW, VR.UC, VR.UN, VR.UT)
            .collect(Collectors.toSet());

    /**
     * Constructor with configuration injection
     */
    @Autowired
    public BulkDataHandler(DicomWebProperties properties) {
        this.bulkDataThreshold = properties.getBulkData().getThreshold();
        log.debug("BulkDataHandler initialized with threshold: {} bytes", this.bulkDataThreshold);
    }

    /**
     * Process DICOM attributes and replace bulk data with BulkDataURI references.
     * Instance method that uses configured threshold.
     *
     * @param attrs DICOM attributes (may contain BulkData objects from URI mode)
     * @param baseUri Base URI for generating BulkDataURI (e.g., "http://localhost:8080/xapi/dicomweb/projects/TestProject")
     * @param studyUID Study Instance UID
     * @param seriesUID Series Instance UID
     * @param instanceUID SOP Instance UID
     * @return New Attributes with BulkDataURI strings replacing bulk data
     */
    public Attributes processBulkData(Attributes attrs, String baseUri,
                                      String studyUID, String seriesUID, String instanceUID) {
        Attributes processed = new Attributes(attrs.size());

        try {
            attrs.accept(new Attributes.Visitor() {
                @Override
                public boolean visit(Attributes attrs, int tag, VR vr, Object value) {
                    if (shouldUseBulkDataURIWithConfig(tag, vr, value)) {
                        // Generate BulkDataURI string
                        String bulkDataURI = generateBulkDataURI(baseUri, studyUID, seriesUID, instanceUID, tag);

                        // Store the URI as a special marker that toJson() will recognize
                        // We'll use a custom Attributes entry with VR.UR (URI/URL)
                        processed.setString(tag, VR.UR, bulkDataURI);

                        log.trace("Replaced tag {} with BulkDataURI: {}", String.format("%08X", tag), bulkDataURI);
                    } else {
                        // Copy non-bulk-data attributes as-is
                        processed.setValue(tag, vr, value);
                    }
                    return true;
                }
            }, false);
        } catch (Exception e) {
            log.error("Error processing bulk data", e);
            // Return original attributes if processing fails
            return attrs;
        }

        return processed;
    }

    /**
     * Determine if an attribute should use BulkDataURI.
     * Instance method that uses configured threshold.
     *
     * @param tag DICOM tag
     * @param vr Value Representation
     * @param value Attribute value
     * @return true if should use BulkDataURI, false otherwise
     */
    public boolean shouldUseBulkDataURIWithConfig(int tag, VR vr, Object value) {
        // 1. Known bulk data tags always use BulkDataURI
        if (BULK_DATA_TAGS.contains(tag)) {
            return true;
        }

        // 2. BulkData objects from URI mode should use BulkDataURI
        if (value instanceof BulkData) {
            return true;
        }

        // 3. Large byte arrays with bulk data VRs
        if (BULK_DATA_VRS.contains(vr) && value instanceof byte[]) {
            byte[] bytes = (byte[]) value;
            return bytes.length > bulkDataThreshold;
        }

        return false;
    }

    /**
     * Static version for backward compatibility.
     * Uses default threshold.
     *
     * @param tag DICOM tag
     * @param vr Value Representation
     * @param value Attribute value
     * @return true if should use BulkDataURI, false otherwise
     */
    public static boolean shouldUseBulkDataURI(int tag, VR vr, Object value) {
        if (BULK_DATA_TAGS.contains(tag)) {
            return true;
        }
        if (value instanceof BulkData) {
            return true;
        }
        if (BULK_DATA_VRS.contains(vr) && value instanceof byte[]) {
            byte[] bytes = (byte[]) value;
            return bytes.length > DEFAULT_BULK_DATA_THRESHOLD;
        }
        return false;
    }

    /**
     * Generate BulkDataURI for a specific DICOM tag.
     *
     * @param baseUri Base URI
     * @param studyUID Study Instance UID
     * @param seriesUID Series Instance UID
     * @param instanceUID SOP Instance UID
     * @param tag DICOM tag (integer)
     * @return BulkDataURI string
     */
    public static String generateBulkDataURI(String baseUri, String studyUID,
                                             String seriesUID, String instanceUID, int tag) {
        // Format tag as 8-digit hex (e.g., 7FE00010 for PixelData)
        String tagHex = String.format("%08X", tag);

        return String.format("%s/studies/%s/series/%s/instances/%s/bulkdata/%s",
                baseUri, studyUID, seriesUID, instanceUID, tagHex);
    }

    /**
     * Extract base URI from request (without path parameters).
     * Example: "http://localhost:8080/xapi/dicomweb/projects/TestProject"
     *
     * @param fullUrl Full request URL
     * @param projectId Project ID
     * @return Base URI string
     */
    public static String extractBaseUri(String fullUrl, String projectId) {
        // Find the position of "/studies" or "/series" to extract base path
        int studiesIndex = fullUrl.indexOf("/studies");
        if (studiesIndex > 0) {
            return fullUrl.substring(0, studiesIndex);
        }

        // Fallback: construct from project ID
        int projectIndex = fullUrl.indexOf("/projects/" + projectId);
        if (projectIndex > 0) {
            int endIndex = projectIndex + "/projects/".length() + projectId.length();
            return fullUrl.substring(0, endIndex);
        }

        // Last resort: return as-is
        return fullUrl;
    }

    /**
     * Check if a tag is a known bulk data tag.
     *
     * @param tag DICOM tag
     * @return true if tag is in the bulk data tags set
     */
    public static boolean isBulkDataTag(int tag) {
        return BULK_DATA_TAGS.contains(tag);
    }

    /**
     * DICOM tags that represent pixel data.
     */
    private static final Set<Integer> PIXEL_DATA_TAGS = IntStream.of(
                    Tag.PixelData,            // 7FE0,0010
                    Tag.FloatPixelData,       // 7FE0,0008
                    Tag.DoubleFloatPixelData  // 7FE0,0009
            )
            .boxed()
            .collect(Collectors.toSet());

    /**
     * Check if a tag is a pixel data tag.
     *
     * @param tag DICOM tag
     * @return true if tag is PixelData, FloatPixelData, or DoubleFloatPixelData
     */
    public static boolean isPixelDataTag(int tag) {
        return PIXEL_DATA_TAGS.contains(tag);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isBulkData(List<ItemPointer> list, String s, int tag, VR vr, int i1) {
        return BULK_DATA_VRS.contains(vr) || BULK_DATA_TAGS.contains(tag);
    }

    /**
     * A single bulk data element extracted from a DICOM instance.
     */
    @Getter
    public static class BulkDataItem {
        private final String contentLocation;
        private final byte[] data;

        public BulkDataItem(String contentLocation, byte[] data) {
            this.contentLocation = contentLocation;
            this.data = data;
        }
    }
}
