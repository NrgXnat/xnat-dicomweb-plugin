package org.nrg.xnat.dicomweb.rest;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.nrg.framework.annotations.XapiRestController;
import org.nrg.prefs.exceptions.InvalidPreferenceName;
import org.nrg.xapi.rest.AbstractXapiRestController;
import org.nrg.xapi.rest.XapiRequestMapping;
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xnat.dicomweb.config.DicomWebPreferenceBean;
import org.nrg.xnat.dicomweb.service.DicomWebProjectConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMethod;

import java.util.HashMap;
import java.util.Map;

import static org.nrg.xdat.security.helpers.AccessLevel.Admin;

/**
 * REST API for DICOMweb plugin preferences
 */
@Api("DICOMweb Plugin Preferences API")
@XapiRestController
public class DicomWebPrefsApi extends AbstractXapiRestController {

    private static final Logger logger = LoggerFactory.getLogger(DicomWebPrefsApi.class);

    private final DicomWebPreferenceBean preferenceBean;
    private final DicomWebProjectConfig projectConfig;

    @Autowired
    public DicomWebPrefsApi(final UserManagementServiceI userManagementService,
                            final RoleHolder roleHolder,
                            final DicomWebPreferenceBean preferenceBean,
                            final DicomWebProjectConfig projectConfig) {
        super(userManagementService, roleHolder);
        this.preferenceBean = preferenceBean;
        this.projectConfig = projectConfig;
    }

    /**
     * Get current DICOMweb plugin preferences
     */
    @ApiOperation(value = "Get DICOMweb plugin preferences", response = Map.class)
    @XapiRequestMapping(value = "/dicomweb/prefs", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE, restrictTo = Admin)
    public ResponseEntity<Map<String, Object>> getPreferences() {
        logger.debug("Getting DICOMweb preferences");

        Map<String, Object> prefs = new HashMap<>();
        prefs.put("defaultPageSize", preferenceBean.getDefaultPageSize());
        prefs.put("maxPageSize", preferenceBean.getMaxPageSize());
        prefs.put("bulkDataThreshold", preferenceBean.getBulkDataThreshold());
        prefs.put("defaultStrategy", preferenceBean.getDefaultStrategy());
        prefs.put("buildDelayMs", preferenceBean.getBuildDelayMs());
        prefs.put("siteWideEnabled", String.valueOf(preferenceBean.getSiteWideEnabled()));
        prefs.put("filterMode", preferenceBean.getFilterMode());
        prefs.put("projectList", preferenceBean.getProjectList());
        prefs.put("baseUrl", preferenceBean.getBaseUrl());
        // Note: memoryThreshold is not exposed here as it requires restart to take effect

        return new ResponseEntity<>(prefs, HttpStatus.OK);
    }

    /**
     * Update DICOMweb plugin preferences
     */
    @ApiOperation(value = "Update DICOMweb plugin preferences")
    @XapiRequestMapping(value = "/dicomweb/prefs", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE, restrictTo = Admin)
    public ResponseEntity<Void> updatePreferences(@RequestBody Map<String, Object> preferences) {
        logger.info("Updating DICOMweb preferences: {}", preferences);

        try {
            if (preferences.containsKey("defaultPageSize")) {
                int value = getIntValue(preferences, "defaultPageSize");
                preferenceBean.setDefaultPageSize(value);
                logger.info("Updated defaultPageSize to: {}", value);
            }

            if (preferences.containsKey("maxPageSize")) {
                int value = getIntValue(preferences, "maxPageSize");
                preferenceBean.setMaxPageSize(value);
                logger.info("Updated maxPageSize to: {}", value);
            }

            // Note: memoryThreshold is not updatable via API as it requires restart to take effect

            if (preferences.containsKey("bulkDataThreshold")) {
                int value = getIntValue(preferences, "bulkDataThreshold");
                preferenceBean.setBulkDataThreshold(value);
                logger.info("Updated bulkDataThreshold to: {}", value);
            }

            if (preferences.containsKey("defaultStrategy")) {
                String value = (String) preferences.get("defaultStrategy");
                // Validate strategy name
                if ("GradualDicomImporter".equals(value) || "DirectArchive".equals(value)) {
                    preferenceBean.setDefaultStrategy(value);
                    logger.info("Updated defaultStrategy to: {}", value);
                } else {
                    logger.warn("Invalid strategy name: {}. Must be 'GradualDicomImporter' or 'DirectArchive'", value);
                    return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
                }
            }

            if (preferences.containsKey("buildDelayMs")) {
                long value = getLongValue(preferences, "buildDelayMs");
                if (value < 0) {
                    logger.warn("Invalid buildDelayMs: {}. Must be >= 0", value);
                    return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
                }
                preferenceBean.setBuildDelayMs(value);
                logger.info("Updated buildDelayMs to: {}", value);
            }

            if (preferences.containsKey("siteWideEnabled")) {
                boolean value = Boolean.parseBoolean(preferences.get("siteWideEnabled").toString());
                preferenceBean.setSiteWideEnabled(value);
                logger.info("Updated siteWideEnabled to: {}", value);
            }

            if (preferences.containsKey("filterMode")) {
                String value = (String) preferences.get("filterMode");
                if ("blacklist".equals(value) || "whitelist".equals(value)) {
                    preferenceBean.setFilterMode(value);
                    logger.info("Updated filterMode to: {}", value);
                } else {
                    logger.warn("Invalid filterMode: {}. Must be 'blacklist' or 'whitelist'", value);
                    return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
                }
            }

            if (preferences.containsKey("projectList")) {
                String value = (String) preferences.get("projectList");
                preferenceBean.setProjectList(value);
                logger.info("Updated projectList to: {}", value);
            }

            if (preferences.containsKey("baseUrl")) {
                String value = (String) preferences.get("baseUrl");
                preferenceBean.setBaseUrl(value);
                logger.info("Updated baseUrl to: {}", value);
            }

            return new ResponseEntity<>(HttpStatus.OK);

        } catch (InvalidPreferenceName e) {
            logger.error("Invalid preference name", e);
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        } catch (Exception e) {
            logger.error("Error updating preferences", e);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Get project-level site-wide opt-out status
     */
    @ApiOperation(value = "Get project site-wide opt-out status", response = Map.class)
    @XapiRequestMapping(value = "/dicomweb/projects/{projectId}/config/site-wide", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE, restrictTo = Admin)
    public ResponseEntity<Map<String, Object>> getProjectSiteWideConfig(@PathVariable String projectId) {
        Map<String, Object> config = new HashMap<>();
        config.put("excludeFromSiteWide", projectConfig.isProjectExcludedFromSiteWide(projectId));
        return new ResponseEntity<>(config, HttpStatus.OK);
    }

    /**
     * Set project-level site-wide opt-out status
     */
    @ApiOperation(value = "Set project site-wide opt-out status")
    @XapiRequestMapping(value = "/dicomweb/projects/{projectId}/config/site-wide", method = RequestMethod.PUT, consumes = MediaType.APPLICATION_JSON_VALUE, restrictTo = Admin)
    public ResponseEntity<Void> setProjectSiteWideConfig(@PathVariable String projectId,
                                                          @RequestBody Map<String, Object> config) {
        try {
            boolean excluded = Boolean.parseBoolean(config.getOrDefault("excludeFromSiteWide", false).toString());
            projectConfig.setProjectExcludedFromSiteWide(projectId, excluded, getSessionUser());
            return new ResponseEntity<>(HttpStatus.OK);
        } catch (Exception e) {
            logger.error("Error updating project site-wide config for {}", projectId, e);
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private int getIntValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return Integer.parseInt(value.toString());
    }

    private long getLongValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return Long.parseLong(value.toString());
    }
}
