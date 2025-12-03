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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

    @Autowired
    public DicomWebPrefsApi(final UserManagementServiceI userManagementService,
                            final RoleHolder roleHolder,
                            final DicomWebPreferenceBean preferenceBean) {
        super(userManagementService, roleHolder);
        this.preferenceBean = preferenceBean;
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
        prefs.put("memoryThreshold", preferenceBean.getMemoryThreshold());
        prefs.put("bulkDataThreshold", preferenceBean.getBulkDataThreshold());

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

            if (preferences.containsKey("memoryThreshold")) {
                long value = getLongValue(preferences, "memoryThreshold");
                preferenceBean.setMemoryThreshold(value);
                logger.info("Updated memoryThreshold to: {}", value);
            }

            if (preferences.containsKey("bulkDataThreshold")) {
                int value = getIntValue(preferences, "bulkDataThreshold");
                preferenceBean.setBulkDataThreshold(value);
                logger.info("Updated bulkDataThreshold to: {}", value);
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
