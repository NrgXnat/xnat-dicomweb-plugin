package org.nrg.xnat.dicomweb.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * DICOMweb plugin configuration properties facade
 *
 * This class provides a convenient structured API over the XNAT preference bean.
 * Configuration can be set via:
 * - XNAT admin UI (Site Administration > Plugin Settings > DICOMweb Plugin Configuration)
 * - xnat.properties or xnat-conf.properties files
 *
 * Example configuration:
 * <pre>
 * dicomweb.defaultPageSize=100
 * dicomweb.maxPageSize=1000
 * dicomweb.memoryThreshold=10485760
 * dicomweb.bulkDataThreshold=1024
 * </pre>
 */
@Component
public class DicomWebProperties {

    private final DicomWebPreferenceBean preferenceBean;

    @Autowired
    public DicomWebProperties(final DicomWebPreferenceBean preferenceBean) {
        this.preferenceBean = preferenceBean;
    }

    public PaginationConfig getPagination() {
        return new PaginationConfig(
            preferenceBean.getDefaultPageSize(),
            preferenceBean.getMaxPageSize()
        );
    }

    public MultipartConfig getMultipart() {
        return new MultipartConfig(preferenceBean.getMemoryThreshold());
    }

    public BulkDataConfig getBulkData() {
        return new BulkDataConfig(preferenceBean.getBulkDataThreshold());
    }

    /**
     * QIDO-RS pagination configuration
     */
    public static class PaginationConfig {
        private final int defaultPageSize;
        private final int maxPageSize;

        public PaginationConfig(int defaultPageSize, int maxPageSize) {
            this.defaultPageSize = defaultPageSize;
            this.maxPageSize = maxPageSize;
        }

        public int getDefaultPageSize() {
            return defaultPageSize;
        }

        public int getMaxPageSize() {
            return maxPageSize;
        }
    }

    /**
     * Multipart request parsing configuration
     */
    public static class MultipartConfig {
        private final long memoryThreshold;

        public MultipartConfig(long memoryThreshold) {
            this.memoryThreshold = memoryThreshold;
        }

        public long getMemoryThreshold() {
            return memoryThreshold;
        }
    }

    /**
     * BulkData handling configuration
     */
    public static class BulkDataConfig {
        private final int threshold;

        public BulkDataConfig(int threshold) {
            this.threshold = threshold;
        }

        public int getThreshold() {
            return threshold;
        }
    }
}
