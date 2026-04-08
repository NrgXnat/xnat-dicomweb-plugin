package org.nrg.xnat.dicomweb.config;

import org.nrg.framework.configuration.ConfigPaths;
import org.nrg.framework.utilities.OrderedProperties;
import org.nrg.prefs.annotations.NrgPreference;
import org.nrg.prefs.annotations.NrgPreferenceBean;
import org.nrg.prefs.beans.AbstractPreferenceBean;
import org.nrg.prefs.exceptions.InvalidPreferenceName;
import org.nrg.prefs.services.NrgPreferenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * XNAT Preference Bean for DICOMweb plugin configuration
 *
 * This provides configuration through XNAT's preference system, allowing:
 * - Configuration via XNAT admin UI (Site Administration > Plugin Settings)
 * - Configuration via xnat.properties or xnat-conf.properties
 * - Runtime modification without restart
 *
 * Example configuration in xnat.properties:
 * <pre>
 * dicomweb.defaultPageSize=100
 * dicomweb.maxPageSize=1000
 * dicomweb.memoryThreshold=10485760
 * dicomweb.bulkDataThreshold=1024
 * </pre>
 */
@NrgPreferenceBean(
    toolId = "dicomweb",
    toolName = "DICOMweb Plugin Configuration",
    description = "Configuration settings for the DICOMweb plugin including pagination, upload, and bulk data thresholds",
    properties = "config/dicomweb/dicomweb.properties"
)
public class DicomWebPreferenceBean extends AbstractPreferenceBean {

    private static final Logger logger = LoggerFactory.getLogger(DicomWebPreferenceBean.class);

    @Autowired
    public DicomWebPreferenceBean(final NrgPreferenceService preferenceService,
                                   final ConfigPaths configPaths,
                                   final OrderedProperties initPrefs) {
        super(preferenceService, configPaths, initPrefs);
        logger.info("DicomWebPreferenceBean initialized with toolId: dicomweb");
    }

    /**
     * Default page size for QIDO-RS queries when limit parameter is not specified
     *
     * @return Default number of results per page (default: 100)
     */
    @NrgPreference(property = "dicomweb.defaultPageSize")
    public int getDefaultPageSize() {
        return getIntegerValue("dicomweb.defaultPageSize");
    }

    public void setDefaultPageSize(final int defaultPageSize) throws InvalidPreferenceName {
        setIntegerValue(defaultPageSize, "dicomweb.defaultPageSize");
    }

    /**
     * Maximum allowed page size for QIDO-RS queries
     * Requests exceeding this value will be capped to this maximum
     *
     * @return Maximum number of results per page (default: 1000)
     */
    @NrgPreference(property = "dicomweb.maxPageSize")
    public int getMaxPageSize() {
        return getIntegerValue("dicomweb.maxPageSize");
    }

    public void setMaxPageSize(final int maxPageSize) throws InvalidPreferenceName {
        setIntegerValue(maxPageSize, "dicomweb.maxPageSize");
    }

    /**
     * Memory threshold in bytes for STOW-RS multipart upload processing
     * Files larger than this will be written to disk instead of kept in memory
     *
     * @return Memory threshold in bytes (default: 10485760 = 10MB)
     */
    @NrgPreference(property = "dicomweb.memoryThreshold")
    public long getMemoryThreshold() {
        return getLongValue("dicomweb.memoryThreshold");
    }

    public void setMemoryThreshold(final long memoryThreshold) throws InvalidPreferenceName {
        setLongValue(memoryThreshold, "dicomweb.memoryThreshold");
    }

    /**
     * BulkData threshold in bytes for WADO-RS metadata responses
     * DICOM attributes with binary data larger than this will use BulkDataURI references
     * instead of inline base64 encoding
     *
     * @return BulkData threshold in bytes (default: 1024 = 1KB)
     */
    @NrgPreference(property = "dicomweb.bulkDataThreshold")
    public int getBulkDataThreshold() {
        return getIntegerValue("dicomweb.bulkDataThreshold");
    }

    public void setBulkDataThreshold(final int bulkDataThreshold) throws InvalidPreferenceName {
        setIntegerValue(bulkDataThreshold, "dicomweb.bulkDataThreshold");
    }

    /**
     * Default import strategy for STOW-RS uploads
     * - DirectArchive: Direct archive writing, bypasses prearchive (default)
     * - GradualDicomImporter: Full XNAT import pipeline with prearchive
     *
     * @return Default strategy name (default: DirectArchive)
     */
    @NrgPreference(property = "dicomweb.defaultStrategy")
    public String getDefaultStrategy() {
        return getValue("dicomweb.defaultStrategy");
    }

    public void setDefaultStrategy(final String defaultStrategy) throws InvalidPreferenceName {
        set(defaultStrategy, "dicomweb.defaultStrategy");
    }

    /**
     * Build delay for DirectArchive multi-batch STOW-RS uploads (milliseconds).
     * When greater than 0, DirectArchive defers the build/archive step until no new files
     * arrive for a study within this delay window. Allows multi-request uploads to be
     * grouped into a single session build.
     * Set to 0 for immediate build (original behavior).
     *
     * @return Build delay in milliseconds (default: 5000)
     */
    @NrgPreference(property = "dicomweb.buildDelayMs")
    public long getBuildDelayMs() {
        return getLongValue("dicomweb.buildDelayMs");
    }

    public void setBuildDelayMs(final long buildDelayMs) throws InvalidPreferenceName {
        setLongValue(buildDelayMs, "dicomweb.buildDelayMs");
    }

    /**
     * Base URL for DICOMweb requests. Used to build URLs in responses. If empty, defaults to the Site URL
     * @return DICOMweb base URL
     */
    @NrgPreference(property = "dicomweb.baseUrl")
    public String getBaseUrl() { return getValue("dicomweb.baseUrl"); }

    public void setBaseUrl(final String baseUrl) throws InvalidPreferenceName {
        set(baseUrl, "dicomweb.baseUrl");
    }

    /**
     * Master toggle for site-wide DICOMweb querying.
     * When false, site-wide endpoints return 404.
     *
     * @return true if site-wide querying is enabled
     */
    @NrgPreference(property = "dicomweb.siteWideEnabled")
    public boolean getSiteWideEnabled() {
        return getBooleanValue("dicomweb.siteWideEnabled");
    }

    public void setSiteWideEnabled(final boolean siteWideEnabled) throws InvalidPreferenceName {
        setBooleanValue(siteWideEnabled, "dicomweb.siteWideEnabled");
    }

    /**
     * Project filter mode for site-wide queries.
     * "blacklist" = include all projects except those in projectList.
     * "whitelist" = exclude all projects except those in projectList.
     *
     * @return "blacklist" or "whitelist"
     */
    @NrgPreference(property = "dicomweb.filterMode")
    public String getFilterMode() {
        return getValue("dicomweb.filterMode");
    }

    public void setFilterMode(final String filterMode) throws InvalidPreferenceName {
        set(filterMode, "dicomweb.filterMode");
    }

    /**
     * Comma-separated list of project IDs for the site-wide filter.
     * Interpretation depends on filterMode.
     *
     * @return comma-separated project IDs
     */
    @NrgPreference(property = "dicomweb.projectList")
    public String getProjectList() {
        return getValue("dicomweb.projectList");
    }

    public void setProjectList(final String projectList) throws InvalidPreferenceName {
        set(projectList, "dicomweb.projectList");
    }
}
