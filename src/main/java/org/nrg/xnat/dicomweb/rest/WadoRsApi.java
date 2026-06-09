package org.nrg.xnat.dicomweb.rest;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import lombok.extern.slf4j.Slf4j;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
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
import org.nrg.xnat.dicomweb.service.RenderingParams;
import org.nrg.xnat.dicomweb.service.XnatDicomService;
import org.nrg.xnat.dicomweb.util.BulkDataHandler;
import org.nrg.xnat.dicomweb.util.BulkDataHandler.BulkDataItem;
import org.nrg.xnat.dicomweb.util.DicomMultipartWriter;
import org.nrg.xnat.dicomweb.util.DicomWebUtils;
import org.nrg.xnat.dicomweb.util.MediaTypeNegotiator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.dcm4che3.ws.rs.MediaTypes.*;
import static org.springframework.http.MediaType.APPLICATION_OCTET_STREAM_VALUE;

/**
 * WADO-RS (Web Access to DICOM Objects over RESTful Services)
 * Implements DICOMweb retrieve endpoints per DICOM PS 3.18 Section 10.4.
 */
@XapiRestController
@Api("DICOMweb WADO-RS API")
@Slf4j
public class WadoRsApi extends AbstractXapiRestController {
    // Supported types per resource category
    private static final List<String> INSTANCE_TYPES =
            Collections.singletonList(APPLICATION_DICOM);
    private static final String INSTANCE_DEFAULT = APPLICATION_DICOM;

    private static final List<String> METADATA_TYPES =
            Arrays.asList(APPLICATION_DICOM_JSON, APPLICATION_DICOM_XML);
    private static final String METADATA_DEFAULT = APPLICATION_DICOM_JSON;

    private static final List<String> RENDERED_TYPES =
            Arrays.asList(IMAGE_JPEG, IMAGE_PNG, IMAGE_GIF);
    private static final String RENDERED_DEFAULT = IMAGE_JPEG;

    private static final List<String> FRAME_TYPES =
            Arrays.asList(APPLICATION_OCTET_STREAM_VALUE, "multipart/related");

    private static final List<String> BULKDATA_TYPES =
            Collections.singletonList(APPLICATION_OCTET_STREAM_VALUE);

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
            produces = APPLICATION_DICOM
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
        headers.setContentType(MediaType.parseMediaType(APPLICATION_DICOM));

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
            produces = {APPLICATION_DICOM_JSON, APPLICATION_DICOM_XML}
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

        return buildMetadataResponse(Stream.of(attrs), selected, baseUri, studyUID);
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
    public ResponseEntity<StreamingResponseBody> retrieveSeries(
            @PathVariable final String projectId,
            @PathVariable final String studyUID,
            @PathVariable final String seriesUID,
            @RequestParam(value = "accept", required = false) String acceptParam,
            HttpServletRequest request) throws Exception {
        negotiateMultipartDicom(request, acceptParam);

        final UserI user = getSessionUser();

        // Resolve the files on the request thread, where the request's ThreadLocal context
        // (Spring TransactionSynchronizationManager, XDAT user/tx stash) is still bound.
        // The StreamingResponseBody below runs on an async dispatch thread that does NOT
        // inherit that context, so we keep it pure file I/O — no XDAT calls. The empty
        // list is the 404 signal. Catalog-only walk: no DICOM headers are parsed.
        final List<File> files = dicomService.resolveSeriesFiles(user, projectId, studyUID, seriesUID);
        if (files.isEmpty()) {
            throw new ResourceNotFoundException("Series", seriesUID);
        }

        final String boundary = UUID.randomUUID().toString();
        final MediaType contentType = MediaType.parseMediaType(DicomWebUtils.getMultipartContentType(boundary));

        final StreamingResponseBody body = out -> streamFilesAsMultipart(out, boundary, files, seriesUID);

        return ResponseEntity.ok().contentType(contentType).body(body);
    }

    // backward-compatible overload used by tests
    public ResponseEntity<StreamingResponseBody> retrieveSeries(
            String projectId, String studyUID, String seriesUID) throws Exception {
        return retrieveSeries(projectId, studyUID, seriesUID, null, null);
    }

    // ---- Study metadata ----

    @XapiRequestMapping(
            value = "/dicomweb/projects/{projectId}/studies/{studyUID}/metadata",
            method = RequestMethod.GET,
            produces = {APPLICATION_DICOM_JSON, APPLICATION_DICOM_XML}
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
            HttpServletRequest request) {
        String selected = negotiateMediaType(request, acceptParam, METADATA_TYPES, METADATA_DEFAULT);

        UserI user = getSessionUser();
        log.debug("Retrieving study metadata for project={}, study={}", projectId, studyUID);

        List<Attributes> instances = dicomService.retrieveAllStudyInstanceMetadata(user, projectId, studyUID)
                .collect(Collectors.toList());

        if (instances.isEmpty()) {
            log.warn("No instances found for study {}", studyUID);
            throw new ResourceNotFoundException("Study", studyUID);
        }

        String requestUrl = request.getRequestURL().toString();
        String baseUri = BulkDataHandler.extractBaseUri(requestUrl, projectId);

        log.debug("Returning {} metadata for {} instances", selected, instances.size());
        return buildMetadataResponse(instances.stream(), selected, baseUri, studyUID);
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
    public ResponseEntity<StreamingResponseBody> retrieveStudy(
            @PathVariable final String projectId,
            @PathVariable final String studyUID,
            @RequestParam(value = "accept", required = false) String acceptParam,
            HttpServletRequest request) throws Exception {
        negotiateMultipartDicom(request, acceptParam);

        final UserI user = getSessionUser();
        log.debug("Retrieving study instances for project={}, study={}", projectId, studyUID);

        // Resolve every file in the study on the request thread; see the comment on
        // retrieveSeries for why this is done before returning the StreamingResponseBody.
        final List<File> files = dicomService.resolveStudyFiles(user, projectId, studyUID);
        if (files.isEmpty()) {
            log.warn("No files found for study {}", studyUID);
            throw new ResourceNotFoundException("Study", studyUID);
        }

        final String boundary = UUID.randomUUID().toString();
        final MediaType contentType = MediaType.parseMediaType(DicomWebUtils.getMultipartContentType(boundary));

        final StreamingResponseBody body = out -> streamFilesAsMultipart(out, boundary, files, studyUID);

        return ResponseEntity.ok().contentType(contentType).body(body);
    }

    // backward-compatible overload used by tests
    public ResponseEntity<StreamingResponseBody> retrieveStudy(
            String projectId, String studyUID) throws Exception {
        return retrieveStudy(projectId, studyUID, null, null);
    }

    // ---- Rendered instance ----

    @XapiRequestMapping(
            value = "/dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}/rendered",
            method = RequestMethod.GET,
            produces = {IMAGE_JPEG, IMAGE_PNG, IMAGE_GIF}
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
            @RequestParam(required = false) String viewport,
            @RequestParam(required = false) String window,
            @RequestParam(required = false) String quality,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        String selected = negotiateMediaType(request, acceptParam, RENDERED_TYPES, RENDERED_DEFAULT);

        UserI user = getSessionUser();
        ImageFormat format = ImageFormat.fromMimeType(selected);
        RenderingParams params = RenderingParams.parse(viewport, window, quality);

        log.debug("Rendered request: Accept={}, selected={}, format={}",
                request != null ? request.getHeader("Accept") : null, selected, format);

        RenderedInstanceResult result =
                dicomService.retrieveRenderedInstance(user, projectId, studyUID, seriesUID,
                        instanceUID, frame, format, params);

        writeRenderedResponse(result, instanceUID, response);
    }

    // backward-compatible overload used by tests
    public void retrieveInstanceRendered(
            String projectId, String studyUID, String seriesUID, String instanceUID,
            Integer frame, HttpServletRequest request, HttpServletResponse response) throws IOException {
        retrieveInstanceRendered(projectId, studyUID, seriesUID, instanceUID, frame, null,
                null, null, null, request, response);
    }

    // ---- Study rendered ----

    @XapiRequestMapping(
            value = "/dicomweb/projects/{projectId}/studies/{studyUID}/rendered",
            method = RequestMethod.GET,
            produces = {IMAGE_JPEG, IMAGE_PNG, IMAGE_GIF}
    )
    @ApiOperation(value = "Retrieve rendered study image (WADO-RS)", response = byte[].class)
    @ApiResponses({
            @ApiResponse(code = 200, message = "Rendered image retrieved"),
            @ApiResponse(code = 404, message = "Study not found"),
            @ApiResponse(code = 406, message = "Requested media type not supported")
    })
    public void retrieveStudyRendered(
            @PathVariable String projectId,
            @PathVariable String studyUID,
            @RequestParam(required = false) Integer frame,
            @RequestParam(value = "accept", required = false) String acceptParam,
            @RequestParam(required = false) String viewport,
            @RequestParam(required = false) String window,
            @RequestParam(required = false) String quality,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        String selected = negotiateMediaType(request, acceptParam, RENDERED_TYPES, RENDERED_DEFAULT);

        UserI user = getSessionUser();
        ImageFormat format = ImageFormat.fromMimeType(selected);
        RenderingParams params = RenderingParams.parse(viewport, window, quality);

        RenderedInstanceResult result =
                dicomService.retrieveRenderedStudy(user, projectId, studyUID, frame, format, params);

        writeRenderedResponse(result, studyUID, response);
    }

    // backward-compatible overload used by tests
    public void retrieveStudyRendered(
            String projectId, String studyUID,
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        retrieveStudyRendered(projectId, studyUID, null, null, null, null, null, request, response);
    }

    // ---- Series rendered ----

    @XapiRequestMapping(
            value = "/dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/rendered",
            method = RequestMethod.GET,
            produces = {IMAGE_JPEG, IMAGE_PNG, IMAGE_GIF}
    )
    @ApiOperation(value = "Retrieve rendered series image (WADO-RS)", response = byte[].class)
    @ApiResponses({
            @ApiResponse(code = 200, message = "Rendered image retrieved"),
            @ApiResponse(code = 404, message = "Series not found"),
            @ApiResponse(code = 406, message = "Requested media type not supported")
    })
    public void retrieveSeriesRendered(
            @PathVariable String projectId,
            @PathVariable String studyUID,
            @PathVariable String seriesUID,
            @RequestParam(required = false) Integer frame,
            @RequestParam(value = "accept", required = false) String acceptParam,
            @RequestParam(required = false) String viewport,
            @RequestParam(required = false) String window,
            @RequestParam(required = false) String quality,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        String selected = negotiateMediaType(request, acceptParam, RENDERED_TYPES, RENDERED_DEFAULT);

        UserI user = getSessionUser();
        ImageFormat format = ImageFormat.fromMimeType(selected);
        RenderingParams params = RenderingParams.parse(viewport, window, quality);

        RenderedInstanceResult result =
                dicomService.retrieveRenderedSeries(user, projectId, studyUID, seriesUID,
                        frame, format, params);

        writeRenderedResponse(result, seriesUID, response);
    }

    // backward-compatible overload used by tests
    public void retrieveSeriesRendered(
            String projectId, String studyUID, String seriesUID,
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        retrieveSeriesRendered(projectId, studyUID, seriesUID, null, null,
                null, null, null, request, response);
    }

    // ---- Frame rendered ----

    @XapiRequestMapping(
            value = "/dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}/frames/{frameList}/rendered",
            method = RequestMethod.GET,
            produces = {IMAGE_JPEG, IMAGE_PNG, IMAGE_GIF}
    )
    @ApiOperation(value = "Retrieve rendered frame (WADO-RS)", response = byte[].class)
    @ApiResponses({
            @ApiResponse(code = 200, message = "Rendered frame retrieved"),
            @ApiResponse(code = 404, message = "Instance or frame not found"),
            @ApiResponse(code = 406, message = "Requested media type not supported")
    })
    public void retrieveFrameRendered(
            @PathVariable String projectId,
            @PathVariable String studyUID,
            @PathVariable String seriesUID,
            @PathVariable String instanceUID,
            @PathVariable String frameList,
            @RequestParam(value = "accept", required = false) String acceptParam,
            @RequestParam(required = false) String viewport,
            @RequestParam(required = false) String window,
            @RequestParam(required = false) String quality,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        String selected = negotiateMediaType(request, acceptParam, RENDERED_TYPES, RENDERED_DEFAULT);

        UserI user = getSessionUser();
        ImageFormat format = ImageFormat.fromMimeType(selected);
        RenderingParams params = RenderingParams.parse(viewport, window, quality);

        // Use the first frame number from the list
        Integer frameNumber = null;
        if (frameList != null && !frameList.isEmpty()) {
            try {
                frameNumber = Integer.parseInt(frameList.split(",")[0].trim());
            } catch (NumberFormatException e) {
                throw new BadRequestException("frameList", "invalid frame number");
            }
        }

        RenderedInstanceResult result =
                dicomService.retrieveRenderedInstance(user, projectId, studyUID, seriesUID,
                        instanceUID, frameNumber, format, params);

        writeRenderedResponse(result, instanceUID, response);
    }

    // backward-compatible overload used by tests
    public void retrieveFrameRendered(
            String projectId, String studyUID, String seriesUID,
            String instanceUID, String frameList,
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        retrieveFrameRendered(projectId, studyUID, seriesUID, instanceUID, frameList,
                null, null, null, null, request, response);
    }

    // ---- Study thumbnail ----

    @XapiRequestMapping(
            value = "/dicomweb/projects/{projectId}/studies/{studyUID}/thumbnail",
            method = RequestMethod.GET,
            produces = {IMAGE_JPEG, IMAGE_PNG, IMAGE_GIF}
    )
    @ApiOperation(value = "Retrieve study thumbnail (WADO-RS)", response = byte[].class)
    @ApiResponses({
            @ApiResponse(code = 200, message = "Thumbnail retrieved"),
            @ApiResponse(code = 404, message = "Study not found")
    })
    public void retrieveStudyThumbnail(
            @PathVariable String projectId,
            @PathVariable String studyUID,
            @RequestParam(required = false) String viewport,
            @RequestParam(value = "accept", required = false) String acceptParam,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        String selected = negotiateMediaType(request, acceptParam, RENDERED_TYPES, RENDERED_DEFAULT);

        UserI user = getSessionUser();
        ImageFormat format = ImageFormat.fromMimeType(selected);
        RenderingParams params = RenderingParams.parse(viewport, null, null);

        RenderedInstanceResult result =
                dicomService.retrieveThumbnailStudy(user, projectId, studyUID, params, format);

        writeRenderedResponse(result, studyUID, response);
    }

    // backward-compatible overload used by tests
    public void retrieveStudyThumbnail(
            String projectId, String studyUID,
            HttpServletResponse response) throws IOException {
        retrieveStudyThumbnail(projectId, studyUID, null, null, null, response);
    }

    // ---- Series thumbnail ----

    @XapiRequestMapping(
            value = "/dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/thumbnail",
            method = RequestMethod.GET,
            produces = {IMAGE_JPEG, IMAGE_PNG, IMAGE_GIF}
    )
    @ApiOperation(value = "Retrieve series thumbnail (WADO-RS)", response = byte[].class)
    @ApiResponses({
            @ApiResponse(code = 200, message = "Thumbnail retrieved"),
            @ApiResponse(code = 404, message = "Series not found")
    })
    public void retrieveSeriesThumbnail(
            @PathVariable String projectId,
            @PathVariable String studyUID,
            @PathVariable String seriesUID,
            @RequestParam(required = false) String viewport,
            @RequestParam(value = "accept", required = false) String acceptParam,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        String selected = negotiateMediaType(request, acceptParam, RENDERED_TYPES, RENDERED_DEFAULT);

        UserI user = getSessionUser();
        ImageFormat format = ImageFormat.fromMimeType(selected);
        RenderingParams params = RenderingParams.parse(viewport, null, null);

        RenderedInstanceResult result =
                dicomService.retrieveThumbnailSeries(user, projectId, studyUID, seriesUID,
                        params, format);

        writeRenderedResponse(result, seriesUID, response);
    }

    // backward-compatible overload used by tests
    public void retrieveSeriesThumbnail(
            String projectId, String studyUID, String seriesUID,
            HttpServletResponse response) throws IOException {
        retrieveSeriesThumbnail(projectId, studyUID, seriesUID, null, null, null, response);
    }

    // ---- Instance thumbnail ----

    @XapiRequestMapping(
            value = "/dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}/thumbnail",
            method = RequestMethod.GET,
            produces = {IMAGE_JPEG, IMAGE_PNG, IMAGE_GIF}
    )
    @ApiOperation(value = "Retrieve instance thumbnail (WADO-RS)", response = byte[].class)
    @ApiResponses({
            @ApiResponse(code = 200, message = "Thumbnail retrieved"),
            @ApiResponse(code = 404, message = "Instance not found")
    })
    public void retrieveInstanceThumbnail(
            @PathVariable String projectId,
            @PathVariable String studyUID,
            @PathVariable String seriesUID,
            @PathVariable String instanceUID,
            @RequestParam(required = false) String viewport,
            @RequestParam(value = "accept", required = false) String acceptParam,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        String selected = negotiateMediaType(request, acceptParam, RENDERED_TYPES, RENDERED_DEFAULT);

        UserI user = getSessionUser();
        ImageFormat format = ImageFormat.fromMimeType(selected);
        RenderingParams params = RenderingParams.parse(viewport, null, null);

        RenderedInstanceResult result =
                dicomService.retrieveThumbnailInstance(user, projectId, studyUID, seriesUID,
                        instanceUID, params, format);

        writeRenderedResponse(result, instanceUID, response);
    }

    // backward-compatible overload used by tests
    public void retrieveInstanceThumbnail(
            String projectId, String studyUID, String seriesUID, String instanceUID,
            HttpServletResponse response) throws IOException {
        retrieveInstanceThumbnail(projectId, studyUID, seriesUID, instanceUID,
                null, null, null, response);
    }

    // ---- Frame thumbnail ----

    @XapiRequestMapping(
            value = "/dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}/frames/{frameList}/thumbnail",
            method = RequestMethod.GET,
            produces = {IMAGE_JPEG, IMAGE_PNG, IMAGE_GIF}
    )
    @ApiOperation(value = "Retrieve frame thumbnail (WADO-RS)", response = byte[].class)
    @ApiResponses({
            @ApiResponse(code = 200, message = "Thumbnail retrieved"),
            @ApiResponse(code = 404, message = "Instance or frame not found")
    })
    public void retrieveFrameThumbnail(
            @PathVariable String projectId,
            @PathVariable String studyUID,
            @PathVariable String seriesUID,
            @PathVariable String instanceUID,
            @PathVariable String frameList,
            @RequestParam(required = false) String viewport,
            @RequestParam(value = "accept", required = false) String acceptParam,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        String selected = negotiateMediaType(request, acceptParam, RENDERED_TYPES, RENDERED_DEFAULT);

        UserI user = getSessionUser();
        ImageFormat format = ImageFormat.fromMimeType(selected);
        RenderingParams params = RenderingParams.parse(viewport, null, null);

        RenderedInstanceResult result =
                dicomService.retrieveThumbnailFrame(user, projectId, studyUID, seriesUID,
                        instanceUID, frameList, params, format);

        writeRenderedResponse(result, instanceUID, response);
    }

    // backward-compatible overload used by tests
    public void retrieveFrameThumbnail(
            String projectId, String studyUID, String seriesUID,
            String instanceUID, String frameList,
            HttpServletResponse response) throws IOException {
        retrieveFrameThumbnail(projectId, studyUID, seriesUID, instanceUID, frameList,
                null, null, null, response);
    }

    // ---- Frame retrieval ----

    @XapiRequestMapping(
            value = "/dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}/frames/{frameList}",
            method = RequestMethod.GET,
            produces = {APPLICATION_OCTET_STREAM_VALUE, "multipart/related"}
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
            negotiateMediaType(request, acceptParam, FRAME_TYPES, APPLICATION_OCTET_STREAM_VALUE);
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
            produces = APPLICATION_OCTET_STREAM_VALUE
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
            negotiateMediaType(request, acceptParam, BULKDATA_TYPES, APPLICATION_OCTET_STREAM_VALUE);
        }

        UserI user = getSessionUser();

        int tagInt;
        try {
            tagInt = Integer.parseUnsignedInt(tag, 16);
        } catch (NumberFormatException e) {
            log.warn("Invalid tag format: {}", tag);
            throw new BadRequestException("tag", "must be a valid hexadecimal DICOM tag");
        }

        log.debug("Retrieving bulk data for instance {} tag {}", instanceUID, tag);

        InputStream stream = dicomService.retrieveInstance(user, projectId, studyUID, seriesUID, instanceUID);

        byte[] bulkData;
        try (org.dcm4che3.io.DicomInputStream dis = new org.dcm4che3.io.DicomInputStream(stream)) {
            dis.setIncludeBulkData(org.dcm4che3.io.DicomInputStream.IncludeBulkData.YES);

            Attributes attrs = dis.readDataset();

            if (!attrs.contains(tagInt)) {
                log.warn("Tag {} not found in instance {}", tag, instanceUID);
                throw new ResourceNotFoundException("Bulk data tag", tag);
            }

            bulkData = attrs.getBytes(tagInt);

            if (bulkData == null || bulkData.length == 0) {
                log.warn("Tag {} has no data in instance {}", tag, instanceUID);
                throw new ResourceNotFoundException("Bulk data tag", tag);
            }

            log.info("Retrieved bulk data for tag {}: {} bytes", tag, bulkData.length);
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
            produces = {APPLICATION_DICOM_JSON, APPLICATION_DICOM_XML}
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
        List<Attributes> instances = dicomService.searchMetadata(user, projectId, studyUID, seriesUID, null)
                .collect(Collectors.toList());
        if (instances.isEmpty()) {
            throw new ResourceNotFoundException("Series metadata", seriesUID);
        }

        String requestUrl = request.getRequestURL().toString();
        String baseUri = BulkDataHandler.extractBaseUri(requestUrl, projectId);

        return buildMetadataResponse(instances.stream(), selected, baseUri, studyUID);
    }

    // ==================== Helper methods ====================

    /**
     * Write a rendered image result to the HTTP response with appropriate headers.
     */
    private void writeRenderedResponse(RenderedInstanceResult result, String identifier,
                                        HttpServletResponse response) throws IOException {
        if (result == null || result.getImageData() == null) {
            throw new ResourceNotFoundException("Rendered resource", identifier);
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
        List<String> supported = Arrays.asList("multipart/related", APPLICATION_DICOM);
        MediaTypeNegotiator.negotiate(acceptHeader, acceptParam, supported, "multipart/related");
    }

    /**
     * Build a metadata response (JSON or XML) from a list of instance attributes.
     */
    private ResponseEntity<String> buildMetadataResponse(Stream<Attributes> instances, String mediaType, String baseUri, String studyUID) {
        final boolean wantsXml = APPLICATION_DICOM_XML.equals(mediaType);
        final String responseBody;
        final String contentType;

        if (wantsXml) {
            final StringBuilder xmlBuilder = new StringBuilder();
            instances.forEach(attrs -> {
                try {
                    DicomWebUtils.replaceBulkDataWithURI(attrs, baseUri,
                            studyUID, attrs.getString(Tag.SeriesInstanceUID), attrs.getString(Tag.SOPInstanceUID));
                    xmlBuilder.append(DicomWebUtils.toXml(attrs));
                } catch (Exception e) {
                    log.error("Error converting instance metadata to XML", e);
                }
            });
            responseBody = xmlBuilder.toString();
            contentType = APPLICATION_DICOM_XML;
        } else {
            responseBody = instances
                    .map(attrs -> {
                        try {
                            DicomWebUtils.replaceBulkDataWithURI(attrs, baseUri, studyUID,
                                    attrs.getString(Tag.SeriesInstanceUID), attrs.getString(Tag.SOPInstanceUID));
                            return DicomWebUtils.toJson(attrs);
                        } catch (Exception e) {
                            log.error("Error converting instance metadata to JSON", e);
                            return "{}";
                        }
                    })
                    .collect(Collectors.joining(",", "[", "]"));
            contentType = APPLICATION_DICOM_JSON;
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .body(responseBody);
    }

    /**
     * Stream a pre-resolved list of DICOM files as a multipart/related body. Pure file I/O
     * — no XDAT calls — so this is safe to run on the {@code StreamingResponseBody} async
     * dispatch thread, which does not inherit the request thread's ThreadLocal context.
     *
     * <p>Per-part flushes keep the read timer fresh on the client and on any intervening
     * load balancer. Per-file errors are logged at WARN and the response continues with
     * the next part; status + headers are already committed by the time this runs, so
     * aborting mid-write would produce a truncated multipart that's worse than a missing
     * part.
     */
    private void streamFilesAsMultipart(final OutputStream out,
                                        final String boundary,
                                        final List<File> files,
                                        final String resourceId) throws IOException {
        try (DicomMultipartWriter writer = new DicomMultipartWriter(out, boundary, APPLICATION_DICOM)) {
            for (File file : files) {
                try (InputStream in = Files.newInputStream(file.toPath())) {
                    writer.writePart(in);
                } catch (Exception e) {
                    log.warn("Skipping file {} during multipart retrieval of {} ({}: {}); "
                            + "response will be missing this part",
                            file, resourceId, e.getClass().getSimpleName(), e.getMessage());
                }
            }
        }
    }

    /**
     * Negotiate for multipart/related bulk data responses.
     * Accepts multipart/related, application/octet-stream, or wildcards.
     */
    private void negotiateMultipartOctetStream(HttpServletRequest request, String acceptParam) {
        if (request == null) return;
        String acceptHeader = request.getHeader("Accept");
        if (acceptHeader == null && acceptParam == null) return;

        List<String> supported = Arrays.asList("multipart/related", APPLICATION_OCTET_STREAM_VALUE);
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
