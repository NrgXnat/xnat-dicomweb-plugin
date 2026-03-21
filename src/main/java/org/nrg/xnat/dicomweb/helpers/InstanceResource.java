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
    private static final int[] attributes = {
            Tag.SOPClassUID,
            Tag.SOPInstanceUID,
            Tag.InstanceNumber,
            Tag.TimezoneOffsetFromUTC,
            Tag.Rows,
            Tag.Columns,
            Tag.BitsAllocated,
            Tag.NumberOfFrames
    };
    private static final int stopTag = 1 + Arrays.stream(attributes).max().getAsInt();

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