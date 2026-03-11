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
import org.nrg.xnat.dicomweb.service.ImageFormat;
import org.nrg.xnat.dicomweb.service.RenderedInstanceResult;
import org.nrg.xnat.dicomweb.service.XnatDicomService;
import org.nrg.xnat.dicomweb.utils.BulkDataHandler;
import org.nrg.xnat.dicomweb.utils.BulkDataHandler.BulkDataItem;
import org.nrg.xnat.dicomweb.utils.DicomWebUtils;
import org.nrg.xnat.dicomweb.utils.MediaTypeNegotiator;
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
import java.io.ByteArrayOutputStream;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * WADO-RS (Web Access to DICOM Objects over RESTful Services)
 * Implements DICOMweb retrieve endpoints per DICOM PS 3.18 Section 10.4.
 */
@XapiRestController
@Api("DICOMweb WADO-RS API")
public class WadoRsApi extends AbstractXapiRestController {

    private static final Logger logger = LoggerFactory.getLogger(WadoRsApi.class);

    // Media type constants
    private static final String MT_DICOM = "application/dicom";
    private static final String MT_DICOM_JSON = "application/dicom+json";
    private static final String MT_DICOM_XML = "application/dicom+xml";
    private static final String MT_OCTET_STREAM = "application/octet-stream";
    private static final String MT_IMAGE_JPEG = "image/jpeg";
    private static final String MT_IMAGE_PNG = "image/png";
    private static final String MT_IMAGE_GIF = "image/gif";

    // Supported types per resource category
    private static final List<String> INSTANCE_TYPES =
            Collections.singletonList(MT_DICOM);
    private static final String INSTANCE_DEFAULT = MT_DICOM;

    private static final List<String> METADATA_TYPES =
            Arrays.asList(MT_DICOM_JSON, MT_DICOM_XML);
    private static final String METADATA_DEFAULT = MT_DICOM_JSON;

    private static final List<String> RENDERED_TYPES =
            Arrays.asList(MT_IMAGE_JPEG, MT_IMAGE_PNG, MT_IMAGE_GIF);
    private static final String RENDERED_DEFAULT = MT_IMAGE_JPEG;

    private static final List<String> FRAME_TYPES =
            Arrays.asList(MT_OCTET_STREAM, "multipart/related");

    private static final List<String> BULKDATA_TYPES =
            Collections.singletonList(MT_OCTET_STREAM);

    private final XnatDicomService dicomService;

    @Autowired
    public WadoRsApi(final XnatDicomService dicomService,
                     final UserManagementServiceI userManagementService,
                     final RoleHolder roleHolder) {
        super(userManagementService, roleHolder);
        this.dicomService = dicomService;
    }

    // ---- Instance retrieval ----

    /**
     * Retrieve a single DICOM instance.
     * PS 3.18: default media type is {@code multipart/related; type="application/dicom"}.
     * We also support single-part {@code application/dicom} (the only instance in the response).
     */
    @XapiRequestMapping(
            value = "/dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}",
            method = RequestMethod.GET,
            produces = MT_DICOM
    )
    @ApiOperation(value = "Retrieve a DICOM instance (WADO-RS)", response = byte[].class)
    @ApiResponses({
            @ApiResponse(code = 200, message = "Instance retrieved"),
            @ApiResponse(code = 401, message = "Must be authenticated"),
            @ApiResponse(code = 404, message = "Instance not found"),
            @ApiResponse(code = 406, message = "Requested media type not supported"),
            @ApiResponse(code = 500, message = "Internal error")
    })
    public ResponseEntity<InputStreamResource> retrieveInstance(
            @PathVariable String projectId,
            @PathVariable String studyUID,
            @PathVariable String seriesUID,
            @PathVariable String instanceUID,
            @RequestParam(value = "accept", required = false) String acceptParam,
            HttpServletRequest request) {
        negotiateMediaType(request, acceptParam, INSTANCE_TYPES, INSTANCE_DEFAULT);

        UserI user = getSessionUser();
        final InputStream stream;
        try {
            stream = dicomService.retrieveInstance(user, projectId, studyUID, seriesUID, instanceUID);
        } catch (IOException e) {
            throw new DicomWebException("Error reading instance " + instanceUID, e,
                    HttpStatus.INTERNAL_SERVER_ERROR.value(), HttpStatus.INTERNAL_SERVER_ERROR.name());
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(MT_DICOM));

        return ResponseEntity.ok()
                .headers(headers)
                .body(new InputStreamResource(stream));
    }

    // backward-compatible overload used by tests
    public ResponseEntity<InputStreamResource> retrieveInstance(
            String projectId, String studyUID, String seriesUID, String instanceUID) {
        return retrieveInstance(projectId, studyUID, seriesUID, instanceUID, null, null);
    }

    // ---- Instance metadata ----

    @XapiRequestMapping(
            value = "/dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}/metadata",
            method = RequestMethod.GET,
            produces = {MT_DICOM_JSON, MT_DICOM_XML}
    )
    @ApiOperation(value = "Retrieve instance metadata (WADO-RS)", response = String.class)
    @ApiResponses({
            @ApiResponse(code = 200, message = "Metadata retrieved"),
            @ApiResponse(code = 401, message = "Must be authenticated"),
            @ApiResponse(code = 404, message = "Instance not found"),
            @ApiResponse(code = 406, message = "Requested media type not supported"),
            @ApiResponse(code = 500, message = "Internal error")
    })
    public ResponseEntity<String> retrieveInstanceMetadata(
            @PathVariable String projectId,
            @PathVariable String studyUID,
            @PathVariable String seriesUID,
            @PathVariable String instanceUID,
            @RequestParam(value = "accept", required = false) String acceptParam,
            HttpServletRequest request) throws Exception {
        String selected = negotiateMediaType(request, acceptParam, METADATA_TYPES, METADATA_DEFAULT);

        UserI user = getSessionUser();
        Attributes attrs = dicomService.retrieveMetadata(user, projectId, studyUID, seriesUID, instanceUID);

        String requestUrl = request.getRequestURL().toString();
        String baseUri = BulkDataHandler.extractBaseUri(requestUrl, projectId);

        return buildMetadataResponse(
                Collections.singletonList(attrs), selected, baseUri, studyUID);
    }

    // backward-compatible overload used by tests
    public ResponseEntity<String> retrieveInstanceMetadata(
            String projectId, String studyUID, String seriesUID, String instanceUID,
            HttpServletRequest request) throws Exception {
        return retrieveInstanceMetadata(projectId, studyUID, seriesUID, instanceUID, null, request);
    }

    // ---- Series retrieval ----

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
            @ApiResponse(code = 406, message = "Requested media type not supported"),
            @ApiResponse(code = 500, message = "Internal error")
    })
    public ResponseEntity<InputStreamResource> retrieveSeries(
            @PathVariable String projectId,
            @PathVariable String studyUID,
            @PathVariable String seriesUID,
            @RequestParam(value = "accept", required = false) String acceptParam,
            HttpServletRequest request) throws Exception {
        negotiateMultipartDicom(request, acceptParam);

        UserI user = getSessionUser();
        List<InputStream> streams = dicomService.retrieveSeries(user, projectId, studyUID, seriesUID);

        if (streams == null || streams.isEmpty()) {
            throw new ResourceNotFoundException("Series", seriesUID);
        }

        return buildMultipartDicomResponse(streams);
    }

    // backward-compatible overload used by tests
    public ResponseEntity<InputStreamResource> retrieveSeries(
            String projectId, String studyUID, String seriesUID) throws Exception {
        return retrieveSeries(projectId, studyUID, seriesUID, null, null);
    }

    // ---- Study metadata ----

    @XapiRequestMapping(
            value = "/dicomweb/projects/{projectId}/studies/{studyUID}/metadata",
            method = RequestMethod.GET,
            produces = {MT_DICOM_JSON, MT_DICOM_XML}
    )
    @ApiOperation(value = "Retrieve study metadata (WADO-RS)", response = String.class)
    @ApiResponses({
            @ApiResponse(code = 200, message = "Study metadata retrieved"),
            @ApiResponse(code = 401, message = "Must be authenticated"),
            @ApiResponse(code = 404, message = "Study not found"),
            @ApiResponse(code = 406, message = "Requested media type not supported"),
            @ApiResponse(code = 500, message = "Internal error")
    })
    public ResponseEntity<String> retrieveStudyMetadata(
            @PathVariable String projectId,
            @PathVariable String studyUID,
            @RequestParam(value = "accept", required = false) String acceptParam,
            HttpServletRequest request) throws Exception {
        String selected = negotiateMediaType(request, acceptParam, METADATA_TYPES, METADATA_DEFAULT);

        UserI user = getSessionUser();
        logger.debug("Retrieving study metadata for project={}, study={}", projectId, studyUID);

        List<Attributes> instances = dicomService.retrieveAllStudyInstanceMetadata(user, projectId, studyUID);

        if (instances == null || instances.isEmpty()) {
            logger.warn("No instances found for study {}", studyUID);
            throw new ResourceNotFoundException("Study", studyUID);
        }

        String requestUrl = request.getRequestURL().toString();
        String baseUri = BulkDataHandler.extractBaseUri(requestUrl, projectId);

        logger.debug("Returning {} metadata for {} instances", selected, instances.size());
        return buildMetadataResponse(instances, selected, baseUri, studyUID);
    }

    // backward-compatible overload used by tests
    public ResponseEntity<String> retrieveStudyMetadata(
            String projectId, String studyUID, HttpServletRequest request) throws Exception {
        return retrieveStudyMetadata(projectId, studyUID, null, request);
    }

    // ---- Study retrieval ----

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
            @ApiResponse(code = 406, message = "Requested media type not supported"),
            @ApiResponse(code = 500, message = "Internal error")
    })
    public ResponseEntity<InputStreamResource> retrieveStudy(
            @PathVariable String projectId,
            @PathVariable String studyUID,
            @RequestParam(value = "accept", required = false) String acceptParam,
            HttpServletRequest request) throws Exception {
        negotiateMultipartDicom(request, acceptParam);

        UserI user = getSessionUser();
        logger.debug("Retrieving study instances for project={}, study={}", projectId, studyUID);

        List<InputStream> streams = dicomService.retrieveStudy(user, projectId, studyUID);

        if (streams == null || streams.isEmpty()) {
            logger.warn("No streams found for study {}", studyUID);
            throw new ResourceNotFoundException("Study", studyUID);
        }

        logger.debug("Returning multipart response with {} instances", streams.size());
        return buildMultipartDicomResponse(streams);
    }

    // backward-compatible overload used by tests
    public ResponseEntity<InputStreamResource> retrieveStudy(
            String projectId, String studyUID) throws Exception {
        return retrieveStudy(projectId, studyUID, null, null);
    }

    // ---- Rendered instance ----

    @XapiRequestMapping(
            value = "/dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}/rendered",
            method = RequestMethod.GET,
            produces = {MT_IMAGE_JPEG, MT_IMAGE_PNG, MT_IMAGE_GIF}
    )
    @ApiOperation(value = "Retrieve rendered instance (WADO-RS)", response = byte[].class)
    @ApiResponses({
            @ApiResponse(code = 200, message = "Rendered image retrieved"),
            @ApiResponse(code = 401, message = "Must be authenticated"),
            @ApiResponse(code = 404, message = "Instance not found"),
            @ApiResponse(code = 406, message = "Requested media type not supported"),
            @ApiResponse(code = 500, message = "Internal error")
    })
    public void retrieveInstanceRendered(
            @PathVariable String projectId,
            @PathVariable String studyUID,
            @PathVariable String seriesUID,
            @PathVariable String instanceUID,
            @RequestParam(required = false) Integer frame,
            @RequestParam(value = "accept", required = false) String acceptParam,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        String selected = negotiateMediaType(request, acceptParam, RENDERED_TYPES, RENDERED_DEFAULT);

        UserI user = getSessionUser();
        ImageFormat format = ImageFormat.fromMimeType(selected);

        logger.debug("Rendered request: Accept={}, selected={}, format={}",
                request != null ? request.getHeader("Accept") : null, selected, format);

        RenderedInstanceResult result =
                dicomService.retrieveRenderedInstance(user, projectId, studyUID, seriesUID,
                        instanceUID, frame, format);

        if (result.getImageData() == null) {
            throw new ResourceNotFoundException("Rendered instance", instanceUID);
        }

        response.setContentType(result.getMimeType());
        response.setContentLength(result.getImageData().length);

        response.setHeader("X-Frame-Count", String.valueOf(result.getTotalFrames()));
        response.setHeader("X-Frame-Number", String.valueOf(result.getRenderedFrame()));
        if (result.getFrameRate() != null) {
            response.setHeader("X-Frame-Rate", String.format("%.2f", result.getFrameRate()));
        }
        if (result.isMultiFrame()) {
            response.setHeader("X-Multi-Frame", "true");
        }

        response.getOutputStream().write(result.getImageData());
        response.getOutputStream().flush();
    }

    // backward-compatible overload used by tests
    public void retrieveInstanceRendered(
            String projectId, String studyUID, String seriesUID, String instanceUID,
            Integer frame, HttpServletRequest request, HttpServletResponse response) throws IOException {
        retrieveInstanceRendered(projectId, studyUID, seriesUID, instanceUID, frame, null, request, response);
    }

    // ---- Frame retrieval ----

    @XapiRequestMapping(
            value = "/dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}/frames/{frameList}",
            method = RequestMethod.GET,
            produces = {MT_OCTET_STREAM, "multipart/related"}
    )
    @ApiOperation(value = "Retrieve frame(s) from instance (WADO-RS)", response = byte[].class)
    @ApiResponses({
            @ApiResponse(code = 200, message = "Frame(s) retrieved"),
            @ApiResponse(code = 401, message = "Must be authenticated"),
            @ApiResponse(code = 404, message = "Instance or frame not found"),
            @ApiResponse(code = 406, message = "Requested media type not supported"),
            @ApiResponse(code = 500, message = "Internal error")
    })
    public ResponseEntity<InputStreamResource> retrieveFrames(
            @PathVariable String projectId,
            @PathVariable String studyUID,
            @PathVariable String seriesUID,
            @PathVariable String instanceUID,
            @PathVariable String frameList,
            @RequestParam(value = "accept", required = false) String acceptParam,
            HttpServletRequest request) throws Exception {
        // Frames support octet-stream and multipart; we don't reject based on Accept
        // since the response format depends on how many frames are requested.
        if (request != null) {
            negotiateMediaType(request, acceptParam, FRAME_TYPES, MT_OCTET_STREAM);
        }

        UserI user = getSessionUser();
        List<byte[]> frames = dicomService.retrieveFrames(user, projectId, studyUID, seriesUID,
                instanceUID, frameList);

        if (frames == null || frames.isEmpty()) {
            throw new ResourceNotFoundException("Frames", frameList + " in instance " + instanceUID);
        }

        if (frames.size() == 1) {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);

            ByteArrayInputStream stream = new ByteArrayInputStream(frames.get(0));
            return ResponseEntity.ok()
                    .headers(headers)
                    .body(new InputStreamResource(stream));
        }

        String boundary = UUID.randomUUID().toString();
        ByteArrayOutputStream multipart = createMultipartFrameResponse(frames, boundary);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(
                "multipart/related; type=\"application/octet-stream\"; boundary=" + boundary));

        return ResponseEntity.ok()
                .headers(headers)
                .body(new InputStreamResource(new ByteArrayInputStream(multipart.toByteArray())));
    }

    // backward-compatible overload used by tests
    public ResponseEntity<InputStreamResource> retrieveFrames(
            String projectId, String studyUID, String seriesUID, String instanceUID,
            String frameList, HttpServletRequest request) throws Exception {
        return retrieveFrames(projectId, studyUID, seriesUID, instanceUID, frameList, null, request);
    }

    // ---- Bulk data retrieval ----

    @XapiRequestMapping(
            value = "/dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}/bulkdata/{tag}",
            method = RequestMethod.GET,
            produces = MT_OCTET_STREAM
    )
    @ApiOperation(value = "Retrieve bulk data for a specific DICOM attribute (WADO-RS)", response = byte[].class)
    @ApiResponses({
            @ApiResponse(code = 200, message = "Bulk data retrieved"),
            @ApiResponse(code = 401, message = "Must be authenticated"),
            @ApiResponse(code = 404, message = "Instance or attribute not found"),
            @ApiResponse(code = 400, message = "Invalid tag format"),
            @ApiResponse(code = 406, message = "Requested media type not supported"),
            @ApiResponse(code = 500, message = "Internal error")
    })
    public void retrieveBulkData(
            @PathVariable String projectId,
            @PathVariable String studyUID,
            @PathVariable String seriesUID,
            @PathVariable String instanceUID,
            @PathVariable String tag,
            @RequestParam(value = "accept", required = false) String acceptParam,
            HttpServletRequest request,
            HttpServletResponse response) throws Exception {
        if (request != null) {
            negotiateMediaType(request, acceptParam, BULKDATA_TYPES, MT_OCTET_STREAM);
        }

        UserI user = getSessionUser();

        int tagInt;
        try {
            tagInt = Integer.parseUnsignedInt(tag, 16);
        } catch (NumberFormatException e) {
            logger.warn("Invalid tag format: {}", tag);
            throw new BadRequestException("tag", "must be a valid hexadecimal DICOM tag");
        }

        logger.debug("Retrieving bulk data for instance {} tag {}", instanceUID, tag);

        InputStream stream = dicomService.retrieveInstance(user, projectId, studyUID, seriesUID, instanceUID);

        byte[] bulkData;
        try (org.dcm4che3.io.DicomInputStream dis = new org.dcm4che3.io.DicomInputStream(stream)) {
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

        String contentLocation = BulkDataHandler.generateBulkDataURI(
                request != null ? BulkDataHandler.extractBaseUri(request.getRequestURL().toString(), projectId) : "",
                studyUID, seriesUID, instanceUID, tagInt);

        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setContentLength(bulkData.length);
        response.setHeader("Content-Location", contentLocation);
        response.getOutputStream().write(bulkData);
        response.getOutputStream().flush();
    }

    // backward-compatible overload used by tests
    public void retrieveBulkData(
            String projectId, String studyUID, String seriesUID, String instanceUID,
            String tag, HttpServletResponse response) throws Exception {
        retrieveBulkData(projectId, studyUID, seriesUID, instanceUID, tag, null, null, response);
    }

    // ---- Instance bulk data (all tags) ----

    @XapiRequestMapping(
            value = "/dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}/bulkdata",
            method = RequestMethod.GET,
            produces = "multipart/related"
    )
    @ApiOperation(value = "Retrieve all bulk data from an instance (WADO-RS)", response = byte[].class)
    @ApiResponses({
            @ApiResponse(code = 200, message = "Bulk data retrieved"),
            @ApiResponse(code = 404, message = "Instance not found"),
            @ApiResponse(code = 406, message = "Requested media type not supported")
    })
    public ResponseEntity<InputStreamResource> retrieveInstanceBulkData(
            @PathVariable String projectId,
            @PathVariable String studyUID,
            @PathVariable String seriesUID,
            @PathVariable String instanceUID,
            @RequestParam(value = "accept", required = false) String acceptParam,
            HttpServletRequest request) {
        negotiateMultipartOctetStream(request, acceptParam);

        UserI user = getSessionUser();
        String baseUri = extractBaseUri(request, projectId);
        List<BulkDataItem> items = dicomService.retrieveInstanceBulkData(
                user, projectId, studyUID, seriesUID, instanceUID, baseUri);

        if (items == null || items.isEmpty()) {
            throw new ResourceNotFoundException("Instance bulk data", instanceUID);
        }

        return buildMultipartBulkDataResponse(items);
    }

    // backward-compatible overload used by tests
    public ResponseEntity<InputStreamResource> retrieveInstanceBulkData(
            String projectId, String studyUID, String seriesUID, String instanceUID) {
        return retrieveInstanceBulkData(projectId, studyUID, seriesUID, instanceUID, null, null);
    }

    // ---- Series bulk data ----

    @XapiRequestMapping(
            value = "/dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/bulkdata",
            method = RequestMethod.GET,
            produces = "multipart/related"
    )
    @ApiOperation(value = "Retrieve all bulk data from a series (WADO-RS)", response = byte[].class)
    @ApiResponses({
            @ApiResponse(code = 200, message = "Bulk data retrieved"),
            @ApiResponse(code = 404, message = "Series not found"),
            @ApiResponse(code = 406, message = "Requested media type not supported")
    })
    public ResponseEntity<InputStreamResource> retrieveSeriesBulkData(
            @PathVariable String projectId,
            @PathVariable String studyUID,
            @PathVariable String seriesUID,
            @RequestParam(value = "accept", required = false) String acceptParam,
            HttpServletRequest request) {
        negotiateMultipartOctetStream(request, acceptParam);

        UserI user = getSessionUser();
        String baseUri = extractBaseUri(request, projectId);
        List<BulkDataItem> items = dicomService.retrieveSeriesBulkData(
                user, projectId, studyUID, seriesUID, baseUri);

        if (items == null || items.isEmpty()) {
            throw new ResourceNotFoundException("Series bulk data", seriesUID);
        }

        return buildMultipartBulkDataResponse(items);
    }

    // backward-compatible overload used by tests
    public ResponseEntity<InputStreamResource> retrieveSeriesBulkData(
            String projectId, String studyUID, String seriesUID) {
        return retrieveSeriesBulkData(projectId, studyUID, seriesUID, null, null);
    }

    // ---- Study bulk data ----

    @XapiRequestMapping(
            value = "/dicomweb/projects/{projectId}/studies/{studyUID}/bulkdata",
            method = RequestMethod.GET,
            produces = "multipart/related"
    )
    @ApiOperation(value = "Retrieve all bulk data from a study (WADO-RS)", response = byte[].class)
    @ApiResponses({
            @ApiResponse(code = 200, message = "Bulk data retrieved"),
            @ApiResponse(code = 404, message = "Study not found"),
            @ApiResponse(code = 406, message = "Requested media type not supported")
    })
    public ResponseEntity<InputStreamResource> retrieveStudyBulkData(
            @PathVariable String projectId,
            @PathVariable String studyUID,
            @RequestParam(value = "accept", required = false) String acceptParam,
            HttpServletRequest request) {
        negotiateMultipartOctetStream(request, acceptParam);

        UserI user = getSessionUser();
        String baseUri = extractBaseUri(request, projectId);
        List<BulkDataItem> items = dicomService.retrieveStudyBulkData(
                user, projectId, studyUID, baseUri);

        if (items == null || items.isEmpty()) {
            throw new ResourceNotFoundException("Study bulk data", studyUID);
        }

        return buildMultipartBulkDataResponse(items);
    }

    // backward-compatible overload used by tests
    public ResponseEntity<InputStreamResource> retrieveStudyBulkData(
            String projectId, String studyUID) {
        return retrieveStudyBulkData(projectId, studyUID, null, null);
    }

    // ---- Instance pixel data ----

    @XapiRequestMapping(
            value = "/dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}/pixeldata",
            method = RequestMethod.GET,
            produces = "multipart/related"
    )
    @ApiOperation(value = "Retrieve pixel data from an instance (WADO-RS)", response = byte[].class)
    @ApiResponses({
            @ApiResponse(code = 200, message = "Pixel data retrieved"),
            @ApiResponse(code = 404, message = "Instance not found or has no pixel data"),
            @ApiResponse(code = 406, message = "Requested media type not supported")
    })
    public ResponseEntity<InputStreamResource> retrieveInstancePixelData(
            @PathVariable String projectId,
            @PathVariable String studyUID,
            @PathVariable String seriesUID,
            @PathVariable String instanceUID,
            @RequestParam(value = "accept", required = false) String acceptParam,
            HttpServletRequest request) {
        negotiateMultipartOctetStream(request, acceptParam);

        UserI user = getSessionUser();
        String baseUri = extractBaseUri(request, projectId);
        List<BulkDataItem> items = dicomService.retrieveInstancePixelData(
                user, projectId, studyUID, seriesUID, instanceUID, baseUri);

        if (items == null || items.isEmpty()) {
            throw new ResourceNotFoundException("Instance pixel data", instanceUID);
        }

        return buildMultipartBulkDataResponse(items);
    }

    // backward-compatible overload used by tests
    public ResponseEntity<InputStreamResource> retrieveInstancePixelData(
            String projectId, String studyUID, String seriesUID, String instanceUID) {
        return retrieveInstancePixelData(projectId, studyUID, seriesUID, instanceUID, null, null);
    }

    // ---- Series pixel data ----

    @XapiRequestMapping(
            value = "/dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/pixeldata",
            method = RequestMethod.GET,
            produces = "multipart/related"
    )
    @ApiOperation(value = "Retrieve pixel data from a series (WADO-RS)", response = byte[].class)
    @ApiResponses({
            @ApiResponse(code = 200, message = "Pixel data retrieved"),
            @ApiResponse(code = 404, message = "Series not found or has no pixel data"),
            @ApiResponse(code = 406, message = "Requested media type not supported")
    })
    public ResponseEntity<InputStreamResource> retrieveSeriesPixelData(
            @PathVariable String projectId,
            @PathVariable String studyUID,
            @PathVariable String seriesUID,
            @RequestParam(value = "accept", required = false) String acceptParam,
            HttpServletRequest request) {
        negotiateMultipartOctetStream(request, acceptParam);

        UserI user = getSessionUser();
        String baseUri = extractBaseUri(request, projectId);
        List<BulkDataItem> items = dicomService.retrieveSeriesPixelData(
                user, projectId, studyUID, seriesUID, baseUri);

        if (items == null || items.isEmpty()) {
            throw new ResourceNotFoundException("Series pixel data", seriesUID);
        }

        return buildMultipartBulkDataResponse(items);
    }

    // backward-compatible overload used by tests
    public ResponseEntity<InputStreamResource> retrieveSeriesPixelData(
            String projectId, String studyUID, String seriesUID) {
        return retrieveSeriesPixelData(projectId, studyUID, seriesUID, null, null);
    }

    // ---- Study pixel data ----

    @XapiRequestMapping(
            value = "/dicomweb/projects/{projectId}/studies/{studyUID}/pixeldata",
            method = RequestMethod.GET,
            produces = "multipart/related"
    )
    @ApiOperation(value = "Retrieve pixel data from a study (WADO-RS)", response = byte[].class)
    @ApiResponses({
            @ApiResponse(code = 200, message = "Pixel data retrieved"),
            @ApiResponse(code = 404, message = "Study not found or has no pixel data"),
            @ApiResponse(code = 406, message = "Requested media type not supported")
    })
    public ResponseEntity<InputStreamResource> retrieveStudyPixelData(
            @PathVariable String projectId,
            @PathVariable String studyUID,
            @RequestParam(value = "accept", required = false) String acceptParam,
            HttpServletRequest request) {
        negotiateMultipartOctetStream(request, acceptParam);

        UserI user = getSessionUser();
        String baseUri = extractBaseUri(request, projectId);
        List<BulkDataItem> items = dicomService.retrieveStudyPixelData(
                user, projectId, studyUID, baseUri);

        if (items == null || items.isEmpty()) {
            throw new ResourceNotFoundException("Study pixel data", studyUID);
        }

        return buildMultipartBulkDataResponse(items);
    }

    // backward-compatible overload used by tests
    public ResponseEntity<InputStreamResource> retrieveStudyPixelData(
            String projectId, String studyUID) {
        return retrieveStudyPixelData(projectId, studyUID, null, null);
    }

    // ---- Series metadata ----

    @XapiRequestMapping(
            value = "/dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/metadata",
            method = RequestMethod.GET,
            produces = {MT_DICOM_JSON, MT_DICOM_XML}
    )
    @ApiOperation(value = "Retrieve metadata for all instances in a series (WADO-RS)", response = String.class)
    @ApiResponses({
            @ApiResponse(code = 200, message = "Metadata retrieved"),
            @ApiResponse(code = 401, message = "Must be authenticated"),
            @ApiResponse(code = 404, message = "Series not found"),
            @ApiResponse(code = 406, message = "Requested media type not supported"),
            @ApiResponse(code = 500, message = "Internal error")
    })
    public ResponseEntity<String> retrieveSeriesMetadata(
            @PathVariable String projectId,
            @PathVariable String studyUID,
            @PathVariable String seriesUID,
            @RequestParam(value = "accept", required = false) String acceptParam,
            HttpServletRequest request) throws Exception {
        String selected = negotiateMediaType(request, acceptParam, METADATA_TYPES, METADATA_DEFAULT);

        UserI user = getSessionUser();
        List<Attributes> instances = dicomService.searchInstances(user, projectId, studyUID, seriesUID, null);

        if (instances == null || instances.isEmpty()) {
            throw new ResourceNotFoundException("Series metadata", seriesUID);
        }

        String requestUrl = request.getRequestURL().toString();
        String baseUri = BulkDataHandler.extractBaseUri(requestUrl, projectId);

        return buildMetadataResponse(instances, selected, baseUri, studyUID);
    }

    // backward-compatible overload used by tests
    public ResponseEntity<String> retrieveSeriesMetadata(
            String projectId, String studyUID, String seriesUID,
            HttpServletRequest request) throws Exception {
        return retrieveSeriesMetadata(projectId, studyUID, seriesUID, null, request);
    }

    // ==================== Helper methods ====================

    /**
     * Run content negotiation for a request.
     *
     * @return the selected media type string
     */
    private String negotiateMediaType(HttpServletRequest request, String acceptParam,
                                      List<String> supported, String defaultType) {
        String acceptHeader = request != null ? request.getHeader("Accept") : null;
        return MediaTypeNegotiator.negotiate(acceptHeader, acceptParam, supported, defaultType);
    }

    /**
     * Negotiate for multipart/related DICOM instance responses.
     * Accepts multipart/related, application/dicom, or wildcards.
     */
    private void negotiateMultipartDicom(HttpServletRequest request, String acceptParam) {
        if (request == null) return;
        String acceptHeader = request.getHeader("Accept");
        if (acceptHeader == null && acceptParam == null) return;

        // For study/series retrieval, we support multipart/related with type=application/dicom
        // and also accept bare application/dicom or wildcards.
        List<String> supported = Arrays.asList("multipart/related", MT_DICOM);
        MediaTypeNegotiator.negotiate(acceptHeader, acceptParam, supported, "multipart/related");
    }

    /**
     * Build a metadata response (JSON or XML) from a list of instance attributes.
     */
    private ResponseEntity<String> buildMetadataResponse(
            List<Attributes> instances, String mediaType, String baseUri, String studyUID) {
        boolean wantsXml = MT_DICOM_XML.equals(mediaType);
        String responseBody;
        String contentType;

        if (wantsXml) {
            StringBuilder xmlBuilder = new StringBuilder();
            for (Attributes attrs : instances) {
                String seriesUID = attrs.getString(org.dcm4che3.data.Tag.SeriesInstanceUID);
                String instanceUID = attrs.getString(org.dcm4che3.data.Tag.SOPInstanceUID);
                try {
                    xmlBuilder.append(DicomWebUtils.toXmlWithBulkDataURI(
                            attrs, baseUri, studyUID, seriesUID, instanceUID));
                } catch (Exception e) {
                    logger.error("Error converting instance metadata to XML", e);
                }
            }
            responseBody = xmlBuilder.toString();
            contentType = MT_DICOM_XML;
        } else {
            String json = "[" + instances.stream()
                    .map(attrs -> {
                        try {
                            String seriesUID = attrs.getString(org.dcm4che3.data.Tag.SeriesInstanceUID);
                            String instanceUID = attrs.getString(org.dcm4che3.data.Tag.SOPInstanceUID);
                            return DicomWebUtils.toJsonWithBulkDataURI(
                                    attrs, baseUri, studyUID, seriesUID, instanceUID);
                        } catch (Exception e) {
                            logger.error("Error converting instance metadata to JSON", e);
                            return "{}";
                        }
                    })
                    .collect(Collectors.joining(",")) + "]";
            responseBody = json;
            contentType = MT_DICOM_JSON;
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .body(responseBody);
    }

    /**
     * Build a multipart/related response containing DICOM instances.
     */
    private ResponseEntity<InputStreamResource> buildMultipartDicomResponse(
            List<InputStream> streams) throws Exception {
        String boundary = UUID.randomUUID().toString();
        ByteArrayOutputStream multipart = createMultipartResponse(streams, boundary);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(
                DicomWebUtils.getMultipartContentType(boundary)));

        return ResponseEntity.ok()
                .headers(headers)
                .body(new InputStreamResource(new ByteArrayInputStream(multipart.toByteArray())));
    }

    private ByteArrayOutputStream createMultipartResponse(List<InputStream> streams,
                                                           String boundary) throws Exception {
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
     * Negotiate for multipart/related bulk data responses.
     * Accepts multipart/related, application/octet-stream, or wildcards.
     */
    private void negotiateMultipartOctetStream(HttpServletRequest request, String acceptParam) {
        if (request == null) return;
        String acceptHeader = request.getHeader("Accept");
        if (acceptHeader == null && acceptParam == null) return;

        List<String> supported = Arrays.asList("multipart/related", MT_OCTET_STREAM);
        MediaTypeNegotiator.negotiate(acceptHeader, acceptParam, supported, "multipart/related");
    }

    /**
     * Extract base URI from request for BulkDataURI generation.
     */
    private String extractBaseUri(HttpServletRequest request, String projectId) {
        if (request == null) return "";
        return BulkDataHandler.extractBaseUri(request.getRequestURL().toString(), projectId);
    }

    /**
     * Build a multipart/related response containing bulk data items with Content-Location headers.
     */
    private ResponseEntity<InputStreamResource> buildMultipartBulkDataResponse(List<BulkDataItem> items) {
        String boundary = UUID.randomUUID().toString();

        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();

            for (BulkDataItem item : items) {
                output.write(("--" + boundary + "\r\n").getBytes());
                output.write(("Content-Type: application/octet-stream\r\n").getBytes());
                output.write(("Content-Location: " + item.getContentLocation() + "\r\n").getBytes());
                output.write("\r\n".getBytes());
                output.write(item.getData());
                output.write("\r\n".getBytes());
            }

            output.write(("--" + boundary + "--\r\n").getBytes());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(
                    "multipart/related; type=\"application/octet-stream\"; boundary=" + boundary));

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(new InputStreamResource(new ByteArrayInputStream(output.toByteArray())));
        } catch (IOException e) {
            throw new DicomWebException("Error building multipart bulk data response", e,
                    HttpStatus.INTERNAL_SERVER_ERROR.value(), HttpStatus.INTERNAL_SERVER_ERROR.name());
        }
    }

    private ByteArrayOutputStream createMultipartFrameResponse(List<byte[]> frames,
                                                                String boundary) throws Exception {
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
