package org.nrg.xnat.dicomweb.rest;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import lombok.extern.slf4j.Slf4j;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.io.DicomInputStream;
import org.nrg.framework.annotations.XapiRestController;
import org.nrg.xapi.rest.AbstractXapiRestController;
import org.nrg.xapi.rest.XapiRequestMapping;
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.security.services.UserManagementServiceI;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
import static org.nrg.xnat.dicomweb.util.WadoMediaTypes.DICOM_TYPES;
import static org.nrg.xnat.dicomweb.util.WadoMediaTypes.INSTANCE_DEFAULT;
import static org.nrg.xnat.dicomweb.util.WadoMediaTypes.METADATA_DEFAULT;
import static org.nrg.xnat.dicomweb.util.WadoMediaTypes.METADATA_TYPES;
import static org.nrg.xnat.dicomweb.util.WadoMediaTypes.RENDERED_DEFAULT;
import static org.nrg.xnat.dicomweb.util.WadoMediaTypes.RENDERED_TYPES;
import static org.springframework.http.MediaType.APPLICATION_OCTET_STREAM_VALUE;

/**
 * WADO-RS (Web Access to DICOM Objects over RESTful Services)
 * Implements DICOMweb retrieve endpoints per DICOM PS 3.18 Section 10.4.
 */
@XapiRestController
@Api("DICOMweb WADO-RS API")
@Slf4j
public class WadoRsApi extends AbstractXapiRestController {

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
            produces = {APPLICATION_DICOM, MULTIPART_RELATED}
    )
    @ApiOperation(value = "Retrieve a DICOM instance (WADO-RS)", response = byte[].class)
    @ApiResponses({
            @ApiResponse(code = 200, message = "Instance retrieved"),
            @ApiResponse(code = 401, message = "Must be authenticated"),
            @ApiResponse(code = 404, message = "Instance not found"),
            @ApiResponse(code = 406, message = "Requested media type not supported"),
            @ApiResponse(code = 500, message = "Internal error")
    })
    public ResponseEntity<StreamingResponseBody> retrieveInstance(
            @PathVariable String projectId,
            @PathVariable String studyUID,
            @PathVariable String seriesUID,
            @PathVariable String instanceUID,
            @RequestParam(value = "accept", required = false) String acceptParam,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String acceptHeader) throws IOException {
        final String mediaType = MediaTypeNegotiator.negotiate(acceptHeader, acceptParam, DICOM_TYPES, INSTANCE_DEFAULT);
        final File file = dicomService.resolveInstanceFile(getSessionUser(), projectId, studyUID, seriesUID, instanceUID);

        if (MULTIPART_RELATED.equals(mediaType)) {
            final String boundary = UUID.randomUUID().toString();
            final StreamingResponseBody body = out -> streamFilesAsMultipart(out, boundary, Collections.singletonList(file), instanceUID);
            final MediaType contentType = MediaType.parseMediaType(DicomWebUtils.getMultipartContentType(boundary));
            return ResponseEntity.ok().contentType(contentType).body(body);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(APPLICATION_DICOM))
                    .body(out -> Files.copy(file.toPath(), out));
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
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String acceptHeader,
            HttpServletRequest request) {
        final String mediaType = MediaTypeNegotiator.negotiate(acceptHeader, acceptParam, METADATA_TYPES, METADATA_DEFAULT);

        Attributes attrs = dicomService.retrieveMetadata(getSessionUser(), projectId, studyUID, seriesUID, instanceUID);

        String baseUri = BulkDataHandler.extractBaseUri(request.getRequestURL().toString(), projectId);

        return buildMetadataResponse(Stream.of(attrs), mediaType, baseUri, studyUID);
    }

    // ---- Series retrieval ----

    @XapiRequestMapping(
            value = "/dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}",
            method = RequestMethod.GET,
            produces = MULTIPART_RELATED
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
            @PathVariable final String seriesUID) throws Exception {
        // Resolve the files on the request thread, where the request's ThreadLocal context
        // (Spring TransactionSynchronizationManager, XDAT user/tx stash) is still bound.
        // The StreamingResponseBody below runs on an async dispatch thread that does NOT
        // inherit that context, so we keep it pure file I/O — no XDAT calls. The empty
        // list is the 404 signal. Catalog-only walk: no DICOM headers are parsed.
        final List<File> files = dicomService.resolveSeriesFiles(getSessionUser(), projectId, studyUID, seriesUID);
        if (files.isEmpty()) {
            throw new ResourceNotFoundException("Series", seriesUID);
        }

        final String boundary = UUID.randomUUID().toString();
        final MediaType contentType = MediaType.parseMediaType(DicomWebUtils.getMultipartContentType(boundary));

        final StreamingResponseBody body = out -> streamFilesAsMultipart(out, boundary, files, seriesUID);

        return ResponseEntity.ok().contentType(contentType).body(body);
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
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String acceptHeader,
            HttpServletRequest request) {
        final String mediaType = MediaTypeNegotiator.negotiate(acceptHeader, acceptParam, METADATA_TYPES, METADATA_DEFAULT);

        log.debug("Retrieving study metadata for project={}, study={}", projectId, studyUID);

        List<Attributes> instances = dicomService.retrieveAllStudyInstanceMetadata(getSessionUser(), projectId, studyUID)
                .collect(Collectors.toList());

        if (instances.isEmpty()) {
            log.warn("No instances found for study {}", studyUID);
            throw new ResourceNotFoundException("Study", studyUID);
        }

        String baseUri = BulkDataHandler.extractBaseUri(request.getRequestURL().toString(), projectId);

        log.debug("Returning {} metadata for {} instances", mediaType, instances.size());
        return buildMetadataResponse(instances.stream(), mediaType, baseUri, studyUID);
    }

    // ---- Study retrieval ----

    @XapiRequestMapping(
            value = "/dicomweb/projects/{projectId}/studies/{studyUID}",
            method = RequestMethod.GET,
            produces = MULTIPART_RELATED
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
            @PathVariable final String studyUID) throws Exception {
        log.debug("Retrieving study instances for project={}, study={}", projectId, studyUID);

        // Resolve every file in the study on the request thread; see the comment on
        // retrieveSeries for why this is done before returning the StreamingResponseBody.
        final List<File> files = dicomService.resolveStudyFiles(getSessionUser(), projectId, studyUID);
        if (files.isEmpty()) {
            log.warn("No files found for study {}", studyUID);
            throw new ResourceNotFoundException("Study", studyUID);
        }

        final String boundary = UUID.randomUUID().toString();
        final MediaType contentType = MediaType.parseMediaType(DicomWebUtils.getMultipartContentType(boundary));

        final StreamingResponseBody body = out -> streamFilesAsMultipart(out, boundary, files, studyUID);

        return ResponseEntity.ok().contentType(contentType).body(body);
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
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String acceptHeader,
            HttpServletResponse response) throws IOException {
        final String mediaType = MediaTypeNegotiator.negotiate(acceptHeader, acceptParam, RENDERED_TYPES, RENDERED_DEFAULT);

        ImageFormat format = ImageFormat.fromMimeType(mediaType);
        RenderingParams params = RenderingParams.parse(viewport, window, quality);

        log.debug("Rendered request: Accept={}/{}, selected={}, format={}",
                acceptParam, acceptHeader, mediaType, format);

        RenderedInstanceResult result =
                dicomService.retrieveRenderedInstance(getSessionUser(), projectId, studyUID, seriesUID,
                        instanceUID, frame, format, params);

        writeRenderedResponse(result, instanceUID, response);
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
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String acceptHeader,
            HttpServletResponse response) throws IOException {
        RenderedInstanceResult result = dicomService.retrieveRenderedStudy(
                getSessionUser(), projectId, studyUID, frame,
                ImageFormat.fromMimeType(MediaTypeNegotiator.negotiate(
                        acceptHeader, acceptParam, RENDERED_TYPES, RENDERED_DEFAULT)),
                RenderingParams.parse(viewport, window, quality));

        writeRenderedResponse(result, studyUID, response);
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
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String acceptHeader,
            HttpServletResponse response) throws IOException {
        RenderedInstanceResult result = dicomService.retrieveRenderedSeries(
                getSessionUser(), projectId, studyUID, seriesUID, frame,
                ImageFormat.fromMimeType(MediaTypeNegotiator.negotiate(
                        acceptHeader, acceptParam, RENDERED_TYPES, RENDERED_DEFAULT)),
                RenderingParams.parse(viewport, window, quality));

        writeRenderedResponse(result, seriesUID, response);
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
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String acceptHeader,
            HttpServletResponse response) throws IOException {
        // Use the first frame number from the list
        Integer frameNumber = null;
        if (frameList != null && !frameList.isEmpty()) {
            try {
                frameNumber = Integer.parseInt(frameList.split(",")[0].trim());
            } catch (NumberFormatException e) {
                throw new BadRequestException("frameList", "invalid frame number");
            }
        }

        RenderedInstanceResult result = dicomService.retrieveRenderedInstance(
                getSessionUser(), projectId, studyUID, seriesUID, instanceUID, frameNumber,
                ImageFormat.fromMimeType(MediaTypeNegotiator.negotiate(
                        acceptHeader, acceptParam, RENDERED_TYPES, RENDERED_DEFAULT)),
                RenderingParams.parse(viewport, window, quality));

        writeRenderedResponse(result, instanceUID, response);
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
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String acceptHeader,
            HttpServletResponse response) throws IOException {
        RenderedInstanceResult result = dicomService.retrieveThumbnailStudy(
                getSessionUser(), projectId, studyUID,
                RenderingParams.parse(viewport, null, null),
                ImageFormat.fromMimeType(MediaTypeNegotiator.negotiate(
                        acceptHeader, acceptParam, RENDERED_TYPES, RENDERED_DEFAULT)));

        writeRenderedResponse(result, studyUID, response);
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
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String acceptHeader,
            HttpServletResponse response) throws IOException {
        RenderedInstanceResult result = dicomService.retrieveThumbnailSeries(
                getSessionUser(), projectId, studyUID, seriesUID,
                RenderingParams.parse(viewport, null, null),
                ImageFormat.fromMimeType(MediaTypeNegotiator.negotiate(
                        acceptHeader, acceptParam, RENDERED_TYPES, RENDERED_DEFAULT)));

        writeRenderedResponse(result, seriesUID, response);
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
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String acceptHeader,
            HttpServletResponse response) throws IOException {
        RenderedInstanceResult result = dicomService.retrieveThumbnailInstance(
                getSessionUser(), projectId, studyUID, seriesUID, instanceUID,
                RenderingParams.parse(viewport, null, null),
                ImageFormat.fromMimeType(MediaTypeNegotiator.negotiate(
                        acceptHeader, acceptParam, RENDERED_TYPES, RENDERED_DEFAULT)));

        writeRenderedResponse(result, instanceUID, response);
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
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String acceptHeader,
            HttpServletResponse response) throws IOException {
        RenderedInstanceResult result = dicomService.retrieveThumbnailFrame(
                getSessionUser(), projectId, studyUID, seriesUID, instanceUID, frameList,
                RenderingParams.parse(viewport, null, null),
                ImageFormat.fromMimeType(MediaTypeNegotiator.negotiate(
                        acceptHeader, acceptParam, RENDERED_TYPES, RENDERED_DEFAULT)));

        writeRenderedResponse(result, instanceUID, response);
    }

    // ---- Frame retrieval ----

    @XapiRequestMapping(
            value = "/dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}/frames/{frameList}",
            method = RequestMethod.GET,
            produces = {APPLICATION_OCTET_STREAM_VALUE, MULTIPART_RELATED}
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
            @PathVariable String frameList) throws Exception {
        // Frames support octet-stream and multipart, but the response format is
        // chosen by frame count (single = octet-stream, multiple = multipart),
        // not by client preference.
        List<byte[]> frames = dicomService.retrieveFrames(getSessionUser(), projectId, studyUID, seriesUID,
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
            HttpServletRequest request,
            HttpServletResponse response) throws Exception {
        int tagInt;
        try {
            tagInt = Integer.parseUnsignedInt(tag, 16);
        } catch (NumberFormatException e) {
            log.warn("Invalid tag format: {}", tag);
            throw new BadRequestException("tag", "must be a valid hexadecimal DICOM tag");
        }

        log.debug("Retrieving bulk data for instance {} tag {}", instanceUID, tag);

        final File file = dicomService.resolveInstanceFile(getSessionUser(), projectId, studyUID, seriesUID, instanceUID);

        byte[] bulkData;
        try (final DicomInputStream dis = new DicomInputStream(file)) {
            dis.setIncludeBulkData(DicomInputStream.IncludeBulkData.YES);
            final Attributes attrs = dis.readDataset();

            if (!attrs.contains(tagInt)) {
                log.warn("Tag {} not found in instance {}", tag, instanceUID);
                throw new ResourceNotFoundException("Bulk data tag", tag);
            }

            bulkData = attrs.getBytes(tagInt);

            if (bulkData == null || bulkData.length == 0) {
                log.warn("Tag {} has no data in instance {}", tag, instanceUID);
                throw new ResourceNotFoundException("Bulk data tag", tag);
            }

            log.debug("Retrieved bulk data for tag {}: {} bytes", tag, bulkData.length);
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

    // ---- Instance bulk data (all tags) ----

    @XapiRequestMapping(
            value = "/dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}/bulkdata",
            method = RequestMethod.GET,
            produces = MULTIPART_RELATED
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
            HttpServletRequest request) {
        String baseUri = extractBaseUri(request, projectId);
        List<BulkDataItem> items = dicomService.retrieveInstanceBulkData(
                getSessionUser(), projectId, studyUID, seriesUID, instanceUID, baseUri);

        if (items == null || items.isEmpty()) {
            throw new ResourceNotFoundException("Instance bulk data", instanceUID);
        }

        return buildMultipartBulkDataResponse(items);
    }

    // ---- Series bulk data ----

    @XapiRequestMapping(
            value = "/dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/bulkdata",
            method = RequestMethod.GET,
            produces = MULTIPART_RELATED
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
            HttpServletRequest request) {
        String baseUri = extractBaseUri(request, projectId);
        List<BulkDataItem> items = dicomService.retrieveSeriesBulkData(
                getSessionUser(), projectId, studyUID, seriesUID, baseUri);

        if (items == null || items.isEmpty()) {
            throw new ResourceNotFoundException("Series bulk data", seriesUID);
        }

        return buildMultipartBulkDataResponse(items);
    }

    // ---- Study bulk data ----

    @XapiRequestMapping(
            value = "/dicomweb/projects/{projectId}/studies/{studyUID}/bulkdata",
            method = RequestMethod.GET,
            produces = MULTIPART_RELATED
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
            HttpServletRequest request) {
        String baseUri = extractBaseUri(request, projectId);
        List<BulkDataItem> items = dicomService.retrieveStudyBulkData(
                getSessionUser(), projectId, studyUID, baseUri);

        if (items == null || items.isEmpty()) {
            throw new ResourceNotFoundException("Study bulk data", studyUID);
        }

        return buildMultipartBulkDataResponse(items);
    }

    // ---- Instance pixel data ----

    @XapiRequestMapping(
            value = "/dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}/pixeldata",
            method = RequestMethod.GET,
            produces = MULTIPART_RELATED
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
            HttpServletRequest request) {
        String baseUri = extractBaseUri(request, projectId);
        List<BulkDataItem> items = dicomService.retrieveInstancePixelData(
                getSessionUser(), projectId, studyUID, seriesUID, instanceUID, baseUri);

        if (items == null || items.isEmpty()) {
            throw new ResourceNotFoundException("Instance pixel data", instanceUID);
        }

        return buildMultipartBulkDataResponse(items);
    }

    // ---- Series pixel data ----

    @XapiRequestMapping(
            value = "/dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/pixeldata",
            method = RequestMethod.GET,
            produces = MULTIPART_RELATED
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
            HttpServletRequest request) {
        String baseUri = extractBaseUri(request, projectId);
        List<BulkDataItem> items = dicomService.retrieveSeriesPixelData(
                getSessionUser(), projectId, studyUID, seriesUID, baseUri);

        if (items == null || items.isEmpty()) {
            throw new ResourceNotFoundException("Series pixel data", seriesUID);
        }

        return buildMultipartBulkDataResponse(items);
    }

    // ---- Study pixel data ----

    @XapiRequestMapping(
            value = "/dicomweb/projects/{projectId}/studies/{studyUID}/pixeldata",
            method = RequestMethod.GET,
            produces = MULTIPART_RELATED
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
            HttpServletRequest request) {
        String baseUri = extractBaseUri(request, projectId);
        List<BulkDataItem> items = dicomService.retrieveStudyPixelData(
                getSessionUser(), projectId, studyUID, baseUri);

        if (items == null || items.isEmpty()) {
            throw new ResourceNotFoundException("Study pixel data", studyUID);
        }

        return buildMultipartBulkDataResponse(items);
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
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String acceptHeader,
            HttpServletRequest request) {
        final String mediaType = MediaTypeNegotiator.negotiate(acceptHeader, acceptParam, METADATA_TYPES, METADATA_DEFAULT);

        List<Attributes> instances = dicomService.searchMetadata(getSessionUser(), projectId, studyUID, seriesUID, null)
                .collect(Collectors.toList());
        if (instances.isEmpty()) {
            throw new ResourceNotFoundException("Series metadata", seriesUID);
        }

        String baseUri = BulkDataHandler.extractBaseUri(request.getRequestURL().toString(), projectId);

        return buildMetadataResponse(instances.stream(), mediaType, baseUri, studyUID);
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
                                                                String boundary) throws IOException {
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
