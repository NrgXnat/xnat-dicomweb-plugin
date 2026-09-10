package org.nrg.xnat.dicomweb.helpers;

import lombok.extern.slf4j.Slf4j;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.io.DicomInputStream;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.stream.Stream;

@Slf4j
public class InstanceResource {
    // Must be sorted in ascending tag order: dcm4che's
    // Attributes(other, selection) constructor binary-searches
    // the selection array and silently drops entries that aren't
    // found. An unsorted array causes some requested tags to
    // vanish from the filtered result.
    private static final int[] attributes = {
            Tag.SOPClassUID,           // 0x00080016
            Tag.SOPInstanceUID,        // 0x00080018
            Tag.TimezoneOffsetFromUTC, // 0x00080201
            Tag.InstanceNumber,        // 0x00200013
            Tag.NumberOfFrames,        // 0x00280008
            Tag.Rows,                  // 0x00280010
            Tag.Columns,               // 0x00280011
            Tag.BitsAllocated          // 0x00280100
    };
    private static final int stopTag = 1 + Arrays.stream(attributes).max().getAsInt();

    static {
        // Guard against future edits reintroducing the sort-order bug.
        for (int i = 1; i < attributes.length; i++) {
            if (Integer.compareUnsigned(attributes[i - 1], attributes[i]) >= 0) {
                throw new IllegalStateException(
                        "InstanceResource.attributes must be sorted in ascending "
                                + "tag order; entry at index " + i + " is out of order");
            }
        }
    }

    /**
     * Extract an Instance Resource
     * @param file File containing a DICOM instance
     * @return Stream containing a single Instance Resource, or empty if unable to read DICOM instance
     * @see <a href="https://dicom.nema.org/medical/dicom/current/output/html/part18.html#sect_10.6.3.3.3">
     *     PS3.18 10.6.3.3.3 Instance Resources</a>
     */
    public static Stream<Attributes> fromFile(final File file) {
        try (DicomInputStream dis = new DicomInputStream(file)) {
            dis.setIncludeBulkData(DicomInputStream.IncludeBulkData.NO);
            final Attributes original = dis.readDataset(stopTag);
            return Stream.of(new Attributes(original, attributes));
        } catch (IOException e) {
            log.debug("Error reading DICOM candidate {}", file.getAbsolutePath(), e);
            return Stream.of();
        }
    }
}