package org.nrg.xnat.dicomweb.rest;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import org.dcm4che3.data.Attributes;
import org.nrg.framework.annotations.XapiRestController;
import org.nrg.xapi.rest.AbstractXapiRestController;
import org.nrg.xapi.rest.XapiRequestMapping;
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.dicomweb.service.XnatDicomService;
import org.nrg.xnat.dicomweb.utils.BulkDataHandler;
import org.nrg.xnat.dicomweb.utils.DicomWebUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

import javax.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * WADO-RS (Web Access to DICOM Objects over RESTful Services)
 * Implements DICOMweb retrieve endpoints
 */
@XapiRestController
@Api("DICOMweb WADO-RS API")
public class WadoRsApi extends AbstractXapiRestController {

    private static final Logger logger = LoggerFactory.getLogger(WadoRsApi.class);

    private final XnatDicomService dicomService;

    @Autowired
    public WadoRsApi(final XnatDicomService dicomService,
                     final UserManagementServiceI userManagementService,
                     final RoleHolder roleHolder) {
        super(userManagementService, roleHolder);
        this.dicomService = dicomService;
    }

    /**
     * Retrieve a single DICOM instance
     * GET /dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}
     */
    @XapiRequestMapping(
            value = "/dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}",
            method = RequestMethod.GET,
            produces = "application/dicom"
    )
    @ApiOperation(value = "Retrieve a DICOM instance (WADO-RS)", response = byte[].class)
    @ApiResponses({
            @ApiResponse(code = 200, message = "Instance retrieved"),
            @ApiResponse(code = 401, message = "Must be authenticated"),
            @ApiResponse(code = 404, message = "Instance not found"),
            @ApiResponse(code = 500, message = "Internal error")
    })
    public ResponseEntity<InputStreamResource> retrieveInstance(@PathVariable String projectId,
                                                                @PathVariable String studyUID,
                                                                @PathVariable String seriesUID,
                                                                @PathVariable String instanceUID) {
        try {
            UserI user = getSessionUser();
            InputStream stream = dicomService.retrieveInstance(user, projectId, studyUID, seriesUID, instanceUID);

            if (stream == null) {
                return ResponseEntity.notFound().build();
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("application/dicom"));

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(new InputStreamResource(stream));

        } catch (Exception e) {
            logger.error("Error retrieving instance: " + instanceUID, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Retrieve metadata for a single instance
     * GET /dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}/metadata
     */
    @XapiRequestMapping(
            value = "/dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}/metadata",
            method = RequestMethod.GET,
            produces = "application/dicom+json"
    )
    @ApiOperation(value = "Retrieve instance metadata (WADO-RS)", response = String.class)
    @ApiResponses({
            @ApiResponse(code = 200, message = "Metadata retrieved"),
            @ApiResponse(code = 401, message = "Must be authenticated"),
            @ApiResponse(code = 404, message = "Instance not found"),
            @ApiResponse(code = 500, message = "Internal error")
    })
    public ResponseEntity<String> retrieveInstanceMetadata(@PathVariable String projectId,
                                                           @PathVariable String studyUID,
                                                           @PathVariable String seriesUID,
                                                           @PathVariable String instanceUID,
                                                           HttpServletRequest request) {
        try {
            UserI user = getSessionUser();
            Attributes attrs = dicomService.retrieveMetadata(user, projectId, studyUID, seriesUID, instanceUID);

            if (attrs == null) {
                return ResponseEntity.notFound().build();
            }

            // Extract base URI for BulkDataURI generation
            String requestUrl = request.getRequestURL().toString();
            String baseUri = BulkDataHandler.extractBaseUri(requestUrl, projectId);

            // Convert to JSON with BulkDataURI substitution
            String json = "[" + DicomWebUtils.toJsonWithBulkDataURI(attrs, baseUri, studyUID, seriesUID, instanceUID) + "]";

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(DicomWebUtils.getDicomJsonContentType()))
                    .body(json);

        } catch (Exception e) {
            logger.error("Error retrieving metadata for instance: " + instanceUID, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Retrieve all instances in a series as multipart
     * GET /dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}
     */
    @XapiRequestMapping(
            value = "/dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}",
            method = RequestMethod.GET,
            produces = "multipart/related"
    )
    @ApiOperation(value = "Retrieve all instances in a series (WADO-RS)", response = byte[].class)
    @ApiResponses({
            @ApiResponse(code = 200, message = "Series retrieved"),
            @ApiResponse(code = 401, message = "Must be authenticated"),
            @ApiResponse(code = 404, message = "Series not found"),
            @ApiResponse(code = 500, message = "Internal error")
    })
    public ResponseEntity<InputStreamResource> retrieveSeries(@PathVariable String projectId,
                                                              @PathVariable String studyUID,
                                                              @PathVariable String seriesUID) {
        try {
            UserI user = getSessionUser();
            List<InputStream> streams = dicomService.retrieveSeries(user, projectId, studyUID, seriesUID);

            if (streams == null || streams.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            // Create multipart response
            String boundary = UUID.randomUUID().toString();
            ByteArrayOutputStream multipart = createMultipartResponse(streams, boundary);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(DicomWebUtils.getMultipartContentType(boundary)));

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(new InputStreamResource(new ByteArrayInputStream(multipart.toByteArray())));

        } catch (Exception e) {
            logger.error("Error retrieving series: " + seriesUID, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Retrieve study metadata
     * GET /dicomweb/projects/{projectId}/studies/{studyUID}/metadata
     *
     * Returns metadata for all instances in the study (per DICOM PS3.18 spec)
     */
    @XapiRequestMapping(
            value = "/dicomweb/projects/{projectId}/studies/{studyUID}/metadata",
            method = RequestMethod.GET,
            produces = "application/dicom+json"
    )
    @ApiOperation(value = "Retrieve study metadata (WADO-RS)", response = String.class)
    @ApiResponses({
            @ApiResponse(code = 200, message = "Study metadata retrieved"),
            @ApiResponse(code = 401, message = "Must be authenticated"),
            @ApiResponse(code = 404, message = "Study not found"),
            @ApiResponse(code = 500, message = "Internal error")
    })
    public ResponseEntity<String> retrieveStudyMetadata(@PathVariable String projectId,
                                                         @PathVariable String studyUID,
                                                         HttpServletRequest request) {
        logger.info("=== retrieveStudyMetadata called ===");
        logger.info("Project ID: {}", projectId);
        logger.info("Study UID: {}", studyUID);

        try {
            UserI user = getSessionUser();
            logger.info("Processing study metadata request");

            // Return metadata for all instances in the study
            List<Attributes> instances = dicomService.retrieveAllStudyInstanceMetadata(user, projectId, studyUID);

            logger.info("Retrieved {} instances", instances != null ? instances.size() : 0);

            if (instances == null || instances.isEmpty()) {
                logger.warn("No instances found for study {}", studyUID);
                return ResponseEntity.notFound().build();
            }

            // Extract base URI for BulkDataURI generation
            String requestUrl = request.getRequestURL().toString();
            String baseUri = BulkDataHandler.extractBaseUri(requestUrl, projectId);

            String json = "[" + instances.stream()
                    .map(attrs -> {
                        try {
                            // Extract SeriesInstanceUID and SOPInstanceUID from attributes
                            String seriesUID = attrs.getString(org.dcm4che3.data.Tag.SeriesInstanceUID);
                            String instanceUID = attrs.getString(org.dcm4che3.data.Tag.SOPInstanceUID);

                            // Convert with BulkDataURI substitution
                            return DicomWebUtils.toJsonWithBulkDataURI(attrs, baseUri, studyUID, seriesUID, instanceUID);
                        } catch (Exception e) {
                            logger.error("Error converting instance metadata to JSON", e);
                            return "{}";
                        }
                    })
                    .collect(Collectors.joining(",")) + "]";

            logger.info("Returning JSON response with {} characters", json.length());
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(DicomWebUtils.getDicomJsonContentType()))
                    .body(json);

        } catch (Exception e) {
            logger.error("Error retrieving study metadata: " + studyUID, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Retrieve all instances in a study as multipart
     * GET /dicomweb/projects/{projectId}/studies/{studyUID}
     */
    @XapiRequestMapping(
            value = "/dicomweb/projects/{projectId}/studies/{studyUID}",
            method = RequestMethod.GET,
            produces = "multipart/related"
    )
    @ApiOperation(value = "Retrieve all instances in a study (WADO-RS)", response = byte[].class)
    @ApiResponses({
            @ApiResponse(code = 200, message = "Study retrieved"),
            @ApiResponse(code = 401, message = "Must be authenticated"),
            @ApiResponse(code = 404, message = "Study not found"),
            @ApiResponse(code = 500, message = "Internal error")
    })
    public ResponseEntity<InputStreamResource> retrieveStudy(@PathVariable String projectId,
                                                             @PathVariable String studyUID) {
        logger.info("=== retrieveStudy called ===");
        logger.info("Project ID: {}", projectId);
        logger.info("Study UID: {}", studyUID);

        try {
            UserI user = getSessionUser();
            logger.info("Processing study retrieval request");

            // Return DICOM instances as multipart
            List<InputStream> streams = dicomService.retrieveStudy(user, projectId, studyUID);

            logger.info("Retrieved {} streams", streams != null ? streams.size() : 0);

            if (streams == null || streams.isEmpty()) {
                logger.warn("No streams found for study {}", studyUID);
                return ResponseEntity.notFound().build();
            }

            // Create multipart response
            String boundary = UUID.randomUUID().toString();
            ByteArrayOutputStream multipart = createMultipartResponse(streams, boundary);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(DicomWebUtils.getMultipartContentType(boundary)));

            logger.info("Returning multipart response with {} bytes", multipart.size());
            return ResponseEntity.ok()
                    .headers(headers)
                    .body(new InputStreamResource(new ByteArrayInputStream(multipart.toByteArray())));

        } catch (Exception e) {
            logger.error("Error retrieving study: " + studyUID, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Retrieve rendered instance (JPEG thumbnail)
     * GET /dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}/rendered
     */
    @XapiRequestMapping(
            value = "/dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}/rendered",
            method = RequestMethod.GET,
            produces = {"image/jpeg", "image/png"}
    )
    @ApiOperation(value = "Retrieve rendered instance as JPEG (WADO-RS)", response = byte[].class)
    @ApiResponses({
            @ApiResponse(code = 200, message = "Rendered image retrieved"),
            @ApiResponse(code = 401, message = "Must be authenticated"),
            @ApiResponse(code = 404, message = "Instance not found"),
            @ApiResponse(code = 500, message = "Internal error")
    })
    public ResponseEntity<byte[]> retrieveInstanceRendered(@PathVariable String projectId,
                                                           @PathVariable String studyUID,
                                                           @PathVariable String seriesUID,
                                                           @PathVariable String instanceUID,
                                                           @RequestParam(required = false) Integer frame) {
        try {
            UserI user = getSessionUser();
            org.nrg.xnat.dicomweb.service.RenderedInstanceResult result =
                    dicomService.retrieveRenderedInstance(user, projectId, studyUID, seriesUID, instanceUID, frame);

            if (result == null || result.getImageData() == null) {
                return ResponseEntity.notFound().build();
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.IMAGE_JPEG);

            // Add frame metadata to response headers
            headers.add("X-Frame-Count", String.valueOf(result.getTotalFrames()));
            headers.add("X-Frame-Number", String.valueOf(result.getRenderedFrame()));
            if (result.getFrameRate() != null) {
                headers.add("X-Frame-Rate", String.format("%.2f", result.getFrameRate()));
            }

            // Add info to help clients understand multi-frame content
            if (result.isMultiFrame()) {
                headers.add("X-Multi-Frame", "true");
            }

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(result.getImageData());

        } catch (Exception e) {
            logger.error("Error retrieving rendered instance: " + instanceUID, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Retrieve specific frame(s) from an instance
     * GET /dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}/frames/{frameList}
     */
    @XapiRequestMapping(
            value = "/dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}/frames/{frameList}",
            method = RequestMethod.GET,
            produces = {"application/octet-stream", "multipart/related"}
    )
    @ApiOperation(value = "Retrieve frame(s) from instance (WADO-RS)", response = byte[].class)
    @ApiResponses({
            @ApiResponse(code = 200, message = "Frame(s) retrieved"),
            @ApiResponse(code = 401, message = "Must be authenticated"),
            @ApiResponse(code = 404, message = "Instance or frame not found"),
            @ApiResponse(code = 500, message = "Internal error")
    })
    public ResponseEntity<InputStreamResource> retrieveFrames(@PathVariable String projectId,
                                                              @PathVariable String studyUID,
                                                              @PathVariable String seriesUID,
                                                              @PathVariable String instanceUID,
                                                              @PathVariable String frameList,
                                                              HttpServletRequest request) {
        try {
            UserI user = getSessionUser();
            List<byte[]> frames = dicomService.retrieveFrames(user, projectId, studyUID, seriesUID, instanceUID, frameList);

            if (frames == null || frames.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            // Single frame - return as application/octet-stream
            if (frames.size() == 1) {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);

                ByteArrayInputStream stream = new ByteArrayInputStream(frames.get(0));
                return ResponseEntity.ok()
                        .headers(headers)
                        .body(new InputStreamResource(stream));
            }

            // Multiple frames - return as multipart/related
            String boundary = UUID.randomUUID().toString();
            ByteArrayOutputStream multipart = createMultipartFrameResponse(frames, boundary);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(
                    "multipart/related; type=\"application/octet-stream\"; boundary=" + boundary));

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(new InputStreamResource(new ByteArrayInputStream(multipart.toByteArray())));

        } catch (Exception e) {
            logger.error("Error retrieving frames from instance: " + instanceUID, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Retrieve bulk data for a specific DICOM attribute
     * GET /dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}/bulkdata/{tag}
     *
     * Per DICOM PS3.18 Section 6.5.8 - Retrieve Bulk Data
     * Returns raw bytes for large attributes (PixelData, OverlayData, etc.)
     */
    @XapiRequestMapping(
            value = "/dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}/bulkdata/{tag}",
            method = RequestMethod.GET,
            produces = "application/octet-stream"
    )
    @ApiOperation(value = "Retrieve bulk data for a specific DICOM attribute (WADO-RS)", response = byte[].class)
    @ApiResponses({
            @ApiResponse(code = 200, message = "Bulk data retrieved"),
            @ApiResponse(code = 401, message = "Must be authenticated"),
            @ApiResponse(code = 404, message = "Instance or attribute not found"),
            @ApiResponse(code = 400, message = "Invalid tag format"),
            @ApiResponse(code = 500, message = "Internal error")
    })
    public ResponseEntity<byte[]> retrieveBulkData(@PathVariable String projectId,
                                                    @PathVariable String studyUID,
                                                    @PathVariable String seriesUID,
                                                    @PathVariable String instanceUID,
                                                    @PathVariable String tag) {
        try {
            UserI user = getSessionUser();

            // Parse tag from hex string (e.g., "7FE00010" for PixelData)
            int tagInt;
            try {
                tagInt = Integer.parseUnsignedInt(tag, 16);
            } catch (NumberFormatException e) {
                logger.warn("Invalid tag format: {}", tag);
                return ResponseEntity.badRequest().build();
            }

            logger.debug("Retrieving bulk data for instance {} tag {}", instanceUID, tag);

            // Retrieve the full DICOM instance
            InputStream stream = dicomService.retrieveInstance(user, projectId, studyUID, seriesUID, instanceUID);

            if (stream == null) {
                logger.warn("Instance not found: {}", instanceUID);
                return ResponseEntity.notFound().build();
            }

            // Read DICOM and extract the specified attribute's value
            byte[] bulkData;
            try (org.dcm4che3.io.DicomInputStream dis = new org.dcm4che3.io.DicomInputStream(stream)) {
                // Read with all bulk data included
                dis.setIncludeBulkData(org.dcm4che3.io.DicomInputStream.IncludeBulkData.YES);

                Attributes attrs = dis.readDataset();

                if (!attrs.contains(tagInt)) {
                    logger.warn("Tag {} not found in instance {}", tag, instanceUID);
                    return ResponseEntity.notFound().build();
                }

                bulkData = attrs.getBytes(tagInt);

                if (bulkData == null || bulkData.length == 0) {
                    logger.warn("Tag {} has no data in instance {}", tag, instanceUID);
                    return ResponseEntity.notFound().build();
                }

                logger.info("Retrieved bulk data for tag {}: {} bytes", tag, bulkData.length);
            }

            // Return raw bytes
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.setContentLength(bulkData.length);

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(bulkData);

        } catch (Exception e) {
            logger.error("Error retrieving bulk data for instance {} tag {}: {}",
                    instanceUID, tag, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Retrieve metadata for all instances in a series
     * GET /dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/metadata
     */
    @XapiRequestMapping(
            value = "/dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/metadata",
            method = RequestMethod.GET,
            produces = "application/dicom+json"
    )
    @ApiOperation(value = "Retrieve metadata for all instances in a series (WADO-RS)", response = String.class)
    @ApiResponses({
            @ApiResponse(code = 200, message = "Metadata retrieved"),
            @ApiResponse(code = 401, message = "Must be authenticated"),
            @ApiResponse(code = 404, message = "Series not found"),
            @ApiResponse(code = 500, message = "Internal error")
    })
    public ResponseEntity<String> retrieveSeriesMetadata(@PathVariable String projectId,
                                                         @PathVariable String studyUID,
                                                         @PathVariable String seriesUID,
                                                         HttpServletRequest request) {
        try {
            UserI user = getSessionUser();
            List<Attributes> instances = dicomService.searchInstances(user, projectId, studyUID, seriesUID, null);

            if (instances == null || instances.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            // Extract base URI for BulkDataURI generation
            String requestUrl = request.getRequestURL().toString();
            String baseUri = BulkDataHandler.extractBaseUri(requestUrl, projectId);

            String json = "[" + instances.stream()
                    .map(attrs -> {
                        try {
                            // Extract SOPInstanceUID from attributes
                            String instanceUID = attrs.getString(org.dcm4che3.data.Tag.SOPInstanceUID);

                            // Convert with BulkDataURI substitution
                            return DicomWebUtils.toJsonWithBulkDataURI(attrs, baseUri, studyUID, seriesUID, instanceUID);
                        } catch (Exception e) {
                            logger.error("Error converting metadata to JSON", e);
                            return "{}";
                        }
                    })
                    .collect(Collectors.joining(",")) + "]";

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(DicomWebUtils.getDicomJsonContentType()))
                    .body(json);

        } catch (Exception e) {
            logger.error("Error retrieving metadata for series: " + seriesUID, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Create a multipart/related response with DICOM instances
     */
    private ByteArrayOutputStream createMultipartResponse(List<InputStream> streams, String boundary) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        for (InputStream stream : streams) {
            output.write(("--" + boundary + "\r\n").getBytes());
            output.write("Content-Type: application/dicom\r\n".getBytes());
            output.write("\r\n".getBytes());

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = stream.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
            }

            output.write("\r\n".getBytes());
            stream.close();
        }

        output.write(("--" + boundary + "--\r\n").getBytes());

        return output;
    }

    /**
     * Create a multipart/related response with frame data
     */
    private ByteArrayOutputStream createMultipartFrameResponse(List<byte[]> frames, String boundary) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        for (byte[] frameData : frames) {
            output.write(("--" + boundary + "\r\n").getBytes());
            output.write("Content-Type: application/octet-stream\r\n".getBytes());
            output.write("\r\n".getBytes());
            output.write(frameData);
            output.write("\r\n".getBytes());
        }

        output.write(("--" + boundary + "--\r\n").getBytes());

        return output;
    }
}
