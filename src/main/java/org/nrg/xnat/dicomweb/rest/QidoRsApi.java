package org.nrg.xnat.dicomweb.rest;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.nrg.framework.annotations.XapiRestController;
import org.nrg.xapi.rest.AbstractXapiRestController;
import org.nrg.xapi.rest.XapiRequestMapping;
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.dicomweb.service.XnatDicomService;
import org.nrg.xnat.dicomweb.utils.DicomWebUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * QIDO-RS (Query based on ID for DICOM Objects over RESTful Services)
 * Implements DICOMweb search endpoints
 */
@XapiRestController
@Api("DICOMweb QIDO-RS API")
public class QidoRsApi extends AbstractXapiRestController {

    private static final Logger logger = LoggerFactory.getLogger(QidoRsApi.class);

    // Pagination constants
    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final int MAX_PAGE_SIZE = 1000;

    private final XnatDicomService dicomService;

    @Autowired
    public QidoRsApi(final XnatDicomService dicomService,
                     final UserManagementServiceI userManagementService,
                     final RoleHolder roleHolder) {
        super(userManagementService, roleHolder);
        this.dicomService = dicomService;
    }

    /**
     * Extract limit parameter from query params, with validation
     */
    private int getLimit(Map<String, String> queryParams) {
        if (queryParams == null || !queryParams.containsKey("limit")) {
            return DEFAULT_PAGE_SIZE;
        }

        try {
            int limit = Integer.parseInt(queryParams.get("limit"));
            if (limit <= 0) {
                logger.warn("Invalid limit value: {}. Using default: {}", limit, DEFAULT_PAGE_SIZE);
                return DEFAULT_PAGE_SIZE;
            }
            if (limit > MAX_PAGE_SIZE) {
                logger.warn("Limit {} exceeds maximum {}. Using maximum.", limit, MAX_PAGE_SIZE);
                return MAX_PAGE_SIZE;
            }
            return limit;
        } catch (NumberFormatException e) {
            logger.warn("Invalid limit parameter: {}. Using default: {}", queryParams.get("limit"), DEFAULT_PAGE_SIZE);
            return DEFAULT_PAGE_SIZE;
        }
    }

    /**
     * Extract offset parameter from query params, with validation
     */
    private int getOffset(Map<String, String> queryParams) {
        if (queryParams == null || !queryParams.containsKey("offset")) {
            return 0;
        }

        try {
            int offset = Integer.parseInt(queryParams.get("offset"));
            if (offset < 0) {
                logger.warn("Invalid offset value: {}. Using 0.", offset);
                return 0;
            }
            return offset;
        } catch (NumberFormatException e) {
            logger.warn("Invalid offset parameter: {}. Using 0.", queryParams.get("offset"));
            return 0;
        }
    }

    /**
     * Apply pagination to a list of results
     */
    private <T> List<T> applyPagination(List<T> results, int offset, int limit) {
        if (results == null || results.isEmpty()) {
            return results;
        }

        int total = results.size();
        int fromIndex = Math.min(offset, total);
        int toIndex = Math.min(offset + limit, total);

        return results.subList(fromIndex, toIndex);
    }

    /**
     * Search for studies in a project
     * GET /dicomweb/projects/{projectId}/studies
     */
    @XapiRequestMapping(
            value = "/dicomweb/projects/{projectId}/studies",
            method = RequestMethod.GET,
            produces = MediaType.ALL_VALUE
    )
    @ApiOperation(value = "Search for studies in a project (QIDO-RS)", response = String.class)
    @ApiResponses({
            @ApiResponse(code = 200, message = "Studies found"),
            @ApiResponse(code = 401, message = "Must be authenticated"),
            @ApiResponse(code = 404, message = "Project not found"),
            @ApiResponse(code = 500, message = "Internal error")
    })
    public ResponseEntity<String> searchStudies(@PathVariable String projectId,
                                                @RequestParam(required = false) Map<String, String> queryParams) {
        try {
            UserI user = getSessionUser();

            // Convert query parameters to DICOM Attributes for filtering
            Attributes queryAttributes = parseQueryParameters(queryParams);

            // Get all studies matching the query
            List<Attributes> allStudies = dicomService.searchStudies(user, projectId, queryAttributes);

            // Get pagination parameters
            int offset = getOffset(queryParams);
            int limit = getLimit(queryParams);

            // Apply pagination
            List<Attributes> paginatedStudies = applyPagination(allStudies, offset, limit);

            // Convert to JSON array
            String json = "[" + paginatedStudies.stream()
                    .map(attrs -> {
                        try {
                            return DicomWebUtils.toJson(attrs);
                        } catch (Exception e) {
                            logger.error("Error converting study to JSON", e);
                            return "{}";
                        }
                    })
                    .collect(Collectors.joining(",")) + "]";

            // Return with X-Total-Count header
            return ResponseEntity.ok()
                    .header("X-Total-Count", String.valueOf(allStudies.size()))
                    .contentType(MediaType.parseMediaType(DicomWebUtils.getDicomJsonContentType()))
                    .body(json);

        } catch (Exception e) {
            logger.error("Error searching studies in project: " + projectId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Search for series in a study
     * GET /dicomweb/projects/{projectId}/studies/{studyUID}/series
     */
    @XapiRequestMapping(
            value = "/dicomweb/projects/{projectId}/studies/{studyUID}/series",
            method = RequestMethod.GET,
            produces = MediaType.ALL_VALUE
    )
    @ApiOperation(value = "Search for series in a study (QIDO-RS)", response = String.class)
    @ApiResponses({
            @ApiResponse(code = 200, message = "Series found"),
            @ApiResponse(code = 401, message = "Must be authenticated"),
            @ApiResponse(code = 404, message = "Study not found"),
            @ApiResponse(code = 500, message = "Internal error")
    })
    public ResponseEntity<String> searchSeries(@PathVariable String projectId,
                                               @PathVariable String studyUID,
                                               @RequestParam(required = false) Map<String, String> queryParams) {
        try {
            UserI user = getSessionUser();

            // Convert query parameters to DICOM Attributes for filtering
            Attributes queryAttributes = parseQueryParameters(queryParams);

            // Get all series matching the query
            List<Attributes> allSeries = dicomService.searchSeries(user, projectId, studyUID, queryAttributes);

            // Get pagination parameters
            int offset = getOffset(queryParams);
            int limit = getLimit(queryParams);

            // Apply pagination
            List<Attributes> paginatedSeries = applyPagination(allSeries, offset, limit);

            String json = "[" + paginatedSeries.stream()
                    .map(attrs -> {
                        try {
                            return DicomWebUtils.toJson(attrs);
                        } catch (Exception e) {
                            logger.error("Error converting series to JSON", e);
                            return "{}";
                        }
                    })
                    .collect(Collectors.joining(",")) + "]";

            // Return with X-Total-Count header
            return ResponseEntity.ok()
                    .header("X-Total-Count", String.valueOf(allSeries.size()))
                    .contentType(MediaType.parseMediaType(DicomWebUtils.getDicomJsonContentType()))
                    .body(json);

        } catch (Exception e) {
            logger.error("Error searching series in study: " + studyUID, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Search for instances in a series
     * GET /dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/instances
     */
    @XapiRequestMapping(
            value = "/dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/instances",
            method = RequestMethod.GET,
            produces = MediaType.ALL_VALUE
    )
    @ApiOperation(value = "Search for instances in a series (QIDO-RS)", response = String.class)
    @ApiResponses({
            @ApiResponse(code = 200, message = "Instances found"),
            @ApiResponse(code = 401, message = "Must be authenticated"),
            @ApiResponse(code = 404, message = "Series not found"),
            @ApiResponse(code = 500, message = "Internal error")
    })
    public ResponseEntity<String> searchInstances(@PathVariable String projectId,
                                                  @PathVariable String studyUID,
                                                  @PathVariable String seriesUID,
                                                  @RequestParam(required = false) Map<String, String> queryParams) {
        try {
            UserI user = getSessionUser();

            // Convert query parameters to DICOM Attributes for filtering
            Attributes queryAttributes = parseQueryParameters(queryParams);

            // Get all instances matching the query
            List<Attributes> allInstances = dicomService.searchInstances(user, projectId, studyUID, seriesUID, queryAttributes);

            // Get pagination parameters
            int offset = getOffset(queryParams);
            int limit = getLimit(queryParams);

            // Apply pagination
            List<Attributes> paginatedInstances = applyPagination(allInstances, offset, limit);

            String json = "[" + paginatedInstances.stream()
                    .map(attrs -> {
                        try {
                            return DicomWebUtils.toJson(attrs);
                        } catch (Exception e) {
                            logger.error("Error converting instance to JSON", e);
                            return "{}";
                        }
                    })
                    .collect(Collectors.joining(",")) + "]";

            // Return with X-Total-Count header
            return ResponseEntity.ok()
                    .header("X-Total-Count", String.valueOf(allInstances.size()))
                    .contentType(MediaType.parseMediaType(DicomWebUtils.getDicomJsonContentType()))
                    .body(json);

        } catch (Exception e) {
            logger.error("Error searching instances in series: " + seriesUID, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Parse HTTP query parameters into DICOM Attributes for filtering
     * Supports common DICOMweb query parameters
     */
    private Attributes parseQueryParameters(Map<String, String> queryParams) {
        Attributes attrs = new Attributes();

        if (queryParams == null || queryParams.isEmpty()) {
            return attrs;
        }

        // Map common query parameter names to DICOM tags
        for (Map.Entry<String, String> entry : queryParams.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            if (value == null || value.isEmpty()) {
                continue;
            }

            // Map query parameter names to DICOM tags
            switch (key.toLowerCase()) {
                case "patientname":
                    attrs.setString(Tag.PatientName, org.dcm4che3.data.VR.PN, value);
                    break;
                case "patientid":
                    attrs.setString(Tag.PatientID, org.dcm4che3.data.VR.LO, value);
                    break;
                case "studydate":
                    attrs.setString(Tag.StudyDate, org.dcm4che3.data.VR.DA, value);
                    break;
                case "studytime":
                    attrs.setString(Tag.StudyTime, org.dcm4che3.data.VR.TM, value);
                    break;
                case "studyinstanceuid":
                    attrs.setString(Tag.StudyInstanceUID, org.dcm4che3.data.VR.UI, value);
                    break;
                case "accessionnumber":
                    attrs.setString(Tag.AccessionNumber, org.dcm4che3.data.VR.SH, value);
                    break;
                case "modality":
                case "modalitiesinst study":
                    attrs.setString(Tag.Modality, org.dcm4che3.data.VR.CS, value);
                    break;
                case "seriesdescription":
                    attrs.setString(Tag.SeriesDescription, org.dcm4che3.data.VR.LO, value);
                    break;
                case "seriesinstanceuid":
                    attrs.setString(Tag.SeriesInstanceUID, org.dcm4che3.data.VR.UI, value);
                    break;
                case "seriesnumber":
                    attrs.setString(Tag.SeriesNumber, org.dcm4che3.data.VR.IS, value);
                    break;
                case "sopinstanceuid":
                    attrs.setString(Tag.SOPInstanceUID, org.dcm4che3.data.VR.UI, value);
                    break;
                case "sopclassuid":
                    attrs.setString(Tag.SOPClassUID, org.dcm4che3.data.VR.UI, value);
                    break;
                case "instancenumber":
                    attrs.setString(Tag.InstanceNumber, org.dcm4che3.data.VR.IS, value);
                    break;
                default:
                    logger.debug("Unsupported query parameter: {}", key);
                    break;
            }
        }

        logger.debug("Parsed {} query parameters into DICOM attributes", attrs.size());
        return attrs;
    }
}
