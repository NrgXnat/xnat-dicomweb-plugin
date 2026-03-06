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
import org.nrg.xnat.dicomweb.exceptions.BadRequestException;
import org.nrg.xnat.dicomweb.exceptions.DicomWebException;
import org.nrg.xnat.dicomweb.exceptions.ResourceNotFoundException;
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
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
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
        UserI user = getSessionUser();
        final InputStream stream;
        try {
            stream = dicomService.retrieveInstance(user, projectId, studyUID, seriesUID, instanceUID);
        } catch (FileNotFoundException e) {
            throw new ResourceNotFoundException("Instance", instanceUID);
        } catch (IOException e) {
            // ### FIXME: better response
            throw new DicomWebException("Instance " + instanceUID, HttpStatus.INTERNAL_SERVER_ERROR.value(), HttpStatus.INTERNAL_SERVER_ERROR.name());
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/dicom"));

        return ResponseEntity.ok()
                .headers(headers)
                .body(new InputStreamResource(stream));
    }

    /**
     * Retrieve metadata for a single instance
     * GET /dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}/metadata
     */
    @XapiRequestMapping(
            value = "/dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}/metadata",
            method = RequestMethod.GET,
            produces = {"application/dicom+json", "application/dicom+xml"}
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
                                                           HttpServletRequest request) throws Exception {
        UserI user = getSessionUser();
        Attributes attrs = dicomService.retrieveMetadata(user, projectId, studyUID, seriesUID, instanceUID);

        if (attrs == null) {
            throw new ResourceNotFoundException("Instance metadata", instanceUID);
        }

        // Extract base URI for BulkDataURI generation
        String requestUrl = request.getRequestURL().toString();
        String baseUri = BulkDataHandler.extractBaseUri(requestUrl, projectId);

        // Determine output format from Accept header
        String acceptHeader = request.getHeader("Accept");
        boolean wantsXml = acceptHeader != null && acceptHeader.contains("application/dicom+xml");

        String responseBody;
        String contentType;

        if (wantsXml) {
            // Convert to XML with BulkDataURI substitution
            String xml = DicomWebUtils.toXmlWithBulkDataURI(attrs, baseUri, studyUID, seriesUID, instanceUID);
            responseBody = xml;
            contentType = DicomWebUtils.getDicomXmlContentType();
        } else {
            // Convert to JSON with BulkDataURI substitution (default)
            String json = "[" + DicomWebUtils.toJsonWithBulkDataURI(attrs, baseUri, studyUID, seriesUID, instanceUID) + "]";
            responseBody = json;
            contentType = DicomWebUtils.getDicomJsonContentType();
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .body(responseBody);
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
                                                              @PathVariable String seriesUID) throws Exception {
        UserI user = getSessionUser();
        List<InputStream> streams = dicomService.retrieveSeries(user, projectId, studyUID, seriesUID);

        if (streams == null || streams.isEmpty()) {
            throw new ResourceNotFoundException("Series", seriesUID);
        }

        // Create multipart response
        String boundary = UUID.randomUUID().toString();
        ByteArrayOutputStream multipart = createMultipartResponse(streams, boundary);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(DicomWebUtils.getMultipartContentType(boundary)));

        return ResponseEntity.ok()
                .headers(headers)
                .body(new InputStreamResource(new ByteArrayInputStream(multipart.toByteArray())));
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
            produces = {"application/dicom+json", "application/dicom+xml"}
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
                                                         HttpServletRequest request) throws Exception {
        logger.info("=== retrieveStudyMetadata called ===");
        logger.info("Project ID: {}", projectId);
        logger.info("Study UID: {}", studyUID);

        UserI user = getSessionUser();
        logger.info("Processing study metadata request");

        // Return metadata for all instances in the study
        List<Attributes> instances = dicomService.retrieveAllStudyInstanceMetadata(user, projectId, studyUID);

        logger.info("Retrieved {} instances", instances != null ? instances.size() : 0);

        if (instances == null || instances.isEmpty()) {
            logger.warn("No instances found for study {}", studyUID);
            throw new ResourceNotFoundException("Study", studyUID);
        }

        // Extract base URI for BulkDataURI generation
        String requestUrl = request.getRequestURL().toString();
        String baseUri = BulkDataHandler.extractBaseUri(requestUrl, projectId);

        // Determine output format from Accept header
        String acceptHeader = request.getHeader("Accept");
        boolean wantsXml = acceptHeader != null && acceptHeader.contains("application/dicom+xml");

        String responseBody;
        String contentType;

        if (wantsXml) {
            // Convert to XML
            StringBuilder xmlBuilder = new StringBuilder();
            for (Attributes attrs : instances) {
                String seriesUID = attrs.getString(org.dcm4che3.data.Tag.SeriesInstanceUID);
                String instanceUID = attrs.getString(org.dcm4che3.data.Tag.SOPInstanceUID);
                xmlBuilder.append(DicomWebUtils.toXmlWithBulkDataURI(attrs, baseUri, studyUID, seriesUID, instanceUID));
            }
            responseBody = xmlBuilder.toString();
            contentType = DicomWebUtils.getDicomXmlContentType();
        } else {
            // Convert to JSON (default)
            String json = "[" + instances.stream()
                    .map(attrs -> {
                        try {
                            String seriesUID = attrs.getString(org.dcm4che3.data.Tag.SeriesInstanceUID);
                            String instanceUID = attrs.getString(org.dcm4che3.data.Tag.SOPInstanceUID);
                            return DicomWebUtils.toJsonWithBulkDataURI(attrs, baseUri, studyUID, seriesUID, instanceUID);
                        } catch (Exception e) {
                            logger.error("Error converting instance metadata to JSON", e);
                            return "{}";
                        }
                    })
                    .collect(Collectors.joining(",")) + "]";
            responseBody = json;
            contentType = DicomWebUtils.getDicomJsonContentType();
        }

        logger.info("Returning {} response with {} characters", wantsXml ? "XML" : "JSON", responseBody.length());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .body(responseBody);
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
                                                             @PathVariable String studyUID) throws Exception {
        logger.info("=== retrieveStudy called ===");
        logger.info("Project ID: {}", projectId);
        logger.info("Study UID: {}", studyUID);

        UserI user = getSessionUser();
        logger.info("Processing study retrieval request");

        // Return DICOM instances as multipart
        List<InputStream> streams = dicomService.retrieveStudy(user, projectId, studyUID);

        logger.info("Retrieved {} streams", streams != null ? streams.size() : 0);

        if (streams == null || streams.isEmpty()) {
            logger.warn("No streams found for study {}", studyUID);
            throw new ResourceNotFoundException("Study", studyUID);
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
    }

    /**
     * Retrieve rendered instance (JPEG thumbnail)
     * GET /dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}/rendered
     */
    @XapiRequestMapping(
            value = "/dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}/rendered",
            method = RequestMethod.GET,
            produces = {"image/jpeg", "image/png", "image/gif"}
    )
    @ApiOperation(value = "Retrieve rendered instance as JPEG (WADO-RS)", response = byte[].class)
    @ApiResponses({
            @ApiResponse(code = 200, message = "Rendered image retrieved"),
            @ApiResponse(code = 401, message = "Must be authenticated"),
            @ApiResponse(code = 404, message = "Instance not found"),
            @ApiResponse(code = 500, message = "Internal error")
    })
    public void retrieveInstanceRendered(@PathVariable String projectId,
                                                           @PathVariable String studyUID,
                                                           @PathVariable String seriesUID,
                                                           @PathVariable String instanceUID,
                                                           @RequestParam(required = false) Integer frame,
                                                           HttpServletRequest request,
                                                           HttpServletResponse response) throws IOException {
        UserI user = getSessionUser();

        // Determine output format from Accept header
        String acceptHeader = request.getHeader("Accept");
        org.nrg.xnat.dicomweb.service.ImageFormat format =
                org.nrg.xnat.dicomweb.service.ImageFormat.fromMimeType(acceptHeader);

        logger.debug("Accept header: {}, selected format: {}", acceptHeader, format);

        org.nrg.xnat.dicomweb.service.RenderedInstanceResult result =
                dicomService.retrieveRenderedInstance(user, projectId, studyUID, seriesUID, instanceUID, frame, format);

        if (result == null || result.getImageData() == null) {
            throw new ResourceNotFoundException("Rendered instance", instanceUID);
        }

        // Set response headers
        response.setContentType(result.getMimeType());
        response.setContentLength(result.getImageData().length);

        // Add frame metadata to response headers
        response.setHeader("X-Frame-Count", String.valueOf(result.getTotalFrames()));
        response.setHeader("X-Frame-Number", String.valueOf(result.getRenderedFrame()));
        if (result.getFrameRate() != null) {
            response.setHeader("X-Frame-Rate", String.format("%.2f", result.getFrameRate()));
        }

        // Add info to help clients understand multi-frame content
        if (result.isMultiFrame()) {
            response.setHeader("X-Multi-Frame", "true");
        }

        // Write directly to response output stream
        response.getOutputStream().write(result.getImageData());
        response.getOutputStream().flush();
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
                                                              HttpServletRequest request) throws Exception {
        UserI user = getSessionUser();
        List<byte[]> frames = dicomService.retrieveFrames(user, projectId, studyUID, seriesUID, instanceUID, frameList);

        if (frames == null || frames.isEmpty()) {
            throw new ResourceNotFoundException("Frames", frameList + " in instance " + instanceUID);
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
    public void retrieveBulkData(@PathVariable String projectId,
                                                    @PathVariable String studyUID,
                                                    @PathVariable String seriesUID,
                                                    @PathVariable String instanceUID,
                                                    @PathVariable String tag,
                                                    HttpServletResponse response) throws Exception {
        UserI user = getSessionUser();

        // Parse tag from hex string (e.g., "7FE00010" for PixelData)
        int tagInt;
        try {
            tagInt = Integer.parseUnsignedInt(tag, 16);
        } catch (NumberFormatException e) {
            logger.warn("Invalid tag format: {}", tag);
            throw new BadRequestException("tag", "must be a valid hexadecimal DICOM tag");
        }

        logger.debug("Retrieving bulk data for instance {} tag {}", instanceUID, tag);

        // Retrieve the full DICOM instance
        InputStream stream = dicomService.retrieveInstance(user, projectId, studyUID, seriesUID, instanceUID);

        if (stream == null) {
            logger.warn("Instance not found: {}", instanceUID);
            throw new ResourceNotFoundException("Instance", instanceUID);
        }

        // Read DICOM and extract the specified attribute's value
        byte[] bulkData;
        try (org.dcm4che3.io.DicomInputStream dis = new org.dcm4che3.io.DicomInputStream(stream)) {
            // Read with all bulk data included
            dis.setIncludeBulkData(org.dcm4che3.io.DicomInputStream.IncludeBulkData.YES);

            Attributes attrs = dis.readDataset();

            if (!attrs.contains(tagInt)) {
                logger.warn("Tag {} not found in instance {}", tag, instanceUID);
                throw new ResourceNotFoundException("Bulk data tag", tag);
            }

            bulkData = attrs.getBytes(tagInt);

            if (bulkData == null || bulkData.length == 0) {
                logger.warn("Tag {} has no data in instance {}", tag, instanceUID);
                throw new ResourceNotFoundException("Bulk data tag", tag);
            }

            logger.info("Retrieved bulk data for tag {}: {} bytes", tag, bulkData.length);
        }

        // Write directly to response output stream
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setContentLength(bulkData.length);
        response.getOutputStream().write(bulkData);
        response.getOutputStream().flush();
    }

    /**
     * Retrieve metadata for all instances in a series
     * GET /dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/metadata
     */
    @XapiRequestMapping(
            value = "/dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/metadata",
            method = RequestMethod.GET,
            produces = {"application/dicom+json", "application/dicom+xml"}
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
                                                         HttpServletRequest request) throws Exception {
        UserI user = getSessionUser();
        List<Attributes> instances = dicomService.searchInstances(user, projectId, studyUID, seriesUID, null);

        if (instances == null || instances.isEmpty()) {
            throw new ResourceNotFoundException("Series metadata", seriesUID);
        }

        // Extract base URI for BulkDataURI generation
        String requestUrl = request.getRequestURL().toString();
        String baseUri = BulkDataHandler.extractBaseUri(requestUrl, projectId);

        // Determine output format from Accept header
        String acceptHeader = request.getHeader("Accept");
        boolean wantsXml = acceptHeader != null && acceptHeader.contains("application/dicom+xml");

        String responseBody;
        String contentType;

        if (wantsXml) {
            // Convert to XML
            StringBuilder xmlBuilder = new StringBuilder();
            for (Attributes attrs : instances) {
                String instanceUID = attrs.getString(org.dcm4che3.data.Tag.SOPInstanceUID);
                xmlBuilder.append(DicomWebUtils.toXmlWithBulkDataURI(attrs, baseUri, studyUID, seriesUID, instanceUID));
            }
            responseBody = xmlBuilder.toString();
            contentType = DicomWebUtils.getDicomXmlContentType();
        } else {
            // Convert to JSON (default)
            String json = "[" + instances.stream()
                    .map(attrs -> {
                        try {
                            String instanceUID = attrs.getString(org.dcm4che3.data.Tag.SOPInstanceUID);
                            return DicomWebUtils.toJsonWithBulkDataURI(attrs, baseUri, studyUID, seriesUID, instanceUID);
                        } catch (Exception e) {
                            logger.error("Error converting metadata to JSON", e);
                            return "{}";
                        }
                    })
                    .collect(Collectors.joining(",")) + "]";
            responseBody = json;
            contentType = DicomWebUtils.getDicomJsonContentType();
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .body(responseBody);
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
