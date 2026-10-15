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
import org.nrg.xft.security.UserI;
import org.nrg.xnat.dicomweb.exceptions.BadRequestException;
import org.nrg.xnat.dicomweb.exceptions.DicomWebException;
import org.nrg.xnat.dicomweb.exceptions.ResourceNotFoundException;
import org.nrg.xnat.dicomweb.service.ImageFormat;
import org.nrg.xnat.dicomweb.service.RenderedInstanceResult;
import org.nrg.xnat.dicomweb.service.RenderingParams;
import org.nrg.xnat.dicomweb.service.SiteWideProjectFilter;
import org.nrg.xnat.dicomweb.service.XnatDicomService;
import org.nrg.xnat.dicomweb.util.BulkDataHandler;
import org.nrg.xnat.dicomweb.util.BulkDataHandler.BulkDataItem;
import org.nrg.xnat.dicomweb.util.DicomMultipartWriter;
import org.nrg.xnat.dicomweb.util.DicomWebUtils;
import org.nrg.xnat.dicomweb.util.MediaTypeNegotiator;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
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

@XapiRestController
@Api("DICOMweb Site-Wide WADO-RS API")
@Slf4j
public class SiteWideWadoRsApi extends AbstractXapiRestController {

    private final XnatDicomService dicomService;
    private final SiteWideProjectFilter siteWideProjectFilter;

    @Autowired
    public SiteWideWadoRsApi(final XnatDicomService dicomService,
                             final SiteWideProjectFilter siteWideProjectFilter,
                             final UserManagementServiceI userManagementService,
                             final RoleHolder roleHolder) {
        super(userManagementService, roleHolder);
        this.dicomService = dicomService;
        this.siteWideProjectFilter = siteWideProjectFilter;
    }

    // ---- Helper: check site-wide enabled ----

    private ResponseEntity<?> checkSiteWideEnabled() {
        if (!siteWideProjectFilter.isSiteWideEnabled()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Site-wide DICOMweb querying is not enabled");
        }
        return null;
    }

    /**
     * Throw-style site-wide check, for handlers that return
     * {@code ResponseEntity<StreamingResponseBody>}. Returning a
     * {@code ResponseEntity<String>} from those would break Spring's
     * generic-parameter inspection in {@code ResponseBodyEmitterReturnValueHandler},
     * which uses the declared generic to decide whether to invoke the
     * streaming handler.
     */
    private void requireSiteWideEnabled() {
        if (!siteWideProjectFilter.isSiteWideEnabled()) {
            throw new DicomWebException(
                    "Site-wide DICOMweb querying is not enabled",
                    HttpStatus.NOT_FOUND.value(),
                    "SiteWideNotEnabled");
        }
    }

    // ---- Retrieve Instance ----

    @ApiOperation(value = "Retrieve a DICOM instance (site-wide)")
    @ApiResponses({
            @ApiResponse(code = 200, message = "Instance retrieved"),
            @ApiResponse(code = 404, message = "Not found")
    })
    @XapiRequestMapping(
            value = "/dicomweb/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}",
            method = RequestMethod.GET,
            produces = {APPLICATION_DICOM, MULTIPART_RELATED})
    public ResponseEntity<StreamingResponseBody> retrieveInstance(
            @PathVariable String studyUID,
            @PathVariable String seriesUID,
            @PathVariable String instanceUID,
            @RequestParam(value = "accept", required = false) String acceptParam,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String acceptHeader) throws DicomWebException, IOException {

        requireSiteWideEnabled();

        final String mediaType = MediaTypeNegotiator.negotiate(acceptHeader, acceptParam, DICOM_TYPES, INSTANCE_DEFAULT);
        final File file = dicomService.resolveInstanceFile(getSessionUser(), null, studyUID, seriesUID, instanceUID);

        if (MULTIPART_RELATED.equals(mediaType)) {
            final String boundary = UUID.randomUUID().toString();
            final StreamingResponseBody body = out -> streamFilesAsMultipart(out, boundary, Collections.singletonList(file), instanceUID);
            final MediaType contentType = MediaType.parseMediaType(DicomWebUtils.getMultipartContentType(boundary));
            return ResponseEntity.ok().contentType(contentType).body(body);
        }
        final StreamingResponseBody body = out -> Files.copy(file.toPath(), out);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(APPLICATION_DICOM))
                .body(body);
    }

    // ---- Instance Metadata ----

    @ApiOperation(value = "Retrieve instance metadata (site-wide)")
    @ApiResponses({
            @ApiResponse(code = 200, message = "Metadata retrieved"),
            @ApiResponse(code = 404, message = "Not found")
    })
    @XapiRequestMapping(
            value = "/dicomweb/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}/metadata",
            method = RequestMethod.GET,
            produces = {APPLICATION_DICOM_JSON, APPLICATION_DICOM_XML})
    public ResponseEntity<?> retrieveInstanceMetadata(
            @PathVariable String studyUID,
            @PathVariable String seriesUID,
            @PathVariable String instanceUID,
            @RequestParam(value = "accept", required = false) String acceptParam,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String acceptHeader,
            HttpServletRequest request) throws DicomWebException, IOException {

        ResponseEntity<?> check = checkSiteWideEnabled();
        if (check != null) return check;

        String mediaType = MediaTypeNegotiator.negotiate(acceptHeader, acceptParam, METADATA_TYPES, METADATA_DEFAULT);
        String baseUri = BulkDataHandler.extractBaseUri(request.getRequestURL().toString(), null);

        Attributes attrs = dicomService.retrieveMetadata(getSessionUser(), null, studyUID, seriesUID, instanceUID);

        return buildMetadataResponse(Stream.of(attrs), mediaType, baseUri, studyUID);
    }

    // ---- Retrieve Series (multipart) ----

    @ApiOperation(value = "Retrieve all instances in a series (site-wide)")
    @ApiResponses({
            @ApiResponse(code = 200, message = "Series retrieved"),
            @ApiResponse(code = 404, message = "Not found")
    })
    @XapiRequestMapping(
            value = "/dicomweb/studies/{studyUID}/series/{seriesUID}",
            method = RequestMethod.GET)
    public void retrieveSeries(
            @PathVariable final String studyUID,
            @PathVariable final String seriesUID,
            final HttpServletResponse response) throws DicomWebException, IOException {

        ResponseEntity<?> check = checkSiteWideEnabled();
        if (check != null) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            response.getWriter().write("Site-wide DICOMweb querying is not enabled");
            return;
        }

        // Resolve the files on the request thread, where the request's ThreadLocal context
        // (Spring TransactionSynchronizationManager, XDAT user/tx stash) is still bound.
        // The streaming write loop below is pure file I/O — no XDAT calls — so it's safe
        // even though the async dispatch thread doesn't inherit that context. Empty list
        // = 404, and the catalog walk parses no DICOM headers.
        final List<File> files = dicomService.resolveSeriesFiles(getSessionUser(), null, studyUID, seriesUID);
        if (files.isEmpty()) {
            throw new ResourceNotFoundException("Series", seriesUID);
        }

        final String boundary = UUID.randomUUID().toString();
        response.setContentType("multipart/related; type=\"" + APPLICATION_DICOM + "\"; boundary=" + boundary);
        response.setStatus(HttpStatus.OK.value());
        response.flushBuffer(); // commit headers immediately so the client begins reading

        streamFilesAsMultipart(response.getOutputStream(), boundary, files,
                "site-wide series " + seriesUID);
    }

    // ---- Study Metadata ----

    @ApiOperation(value = "Retrieve study metadata (site-wide)")
    @ApiResponses({
            @ApiResponse(code = 200, message = "Metadata retrieved"),
            @ApiResponse(code = 404, message = "Not found")
    })
    @XapiRequestMapping(
            value = "/dicomweb/studies/{studyUID}/metadata",
            method = RequestMethod.GET,
            produces = {APPLICATION_DICOM_JSON, APPLICATION_DICOM_XML})
    public ResponseEntity<?> retrieveStudyMetadata(
            @PathVariable String studyUID,
            @RequestParam(value = "accept", required = false) String acceptParam,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String acceptHeader,
            HttpServletRequest request) throws DicomWebException, IOException {

        ResponseEntity<?> check = checkSiteWideEnabled();
        if (check != null) return check;

        String mediaType = MediaTypeNegotiator.negotiate(acceptHeader, acceptParam, METADATA_TYPES, METADATA_DEFAULT);
        String baseUri = BulkDataHandler.extractBaseUri(request.getRequestURL().toString(), null);

        List<Attributes> instances = dicomService.retrieveAllStudyInstanceMetadata(getSessionUser(), null, studyUID)
                .collect(Collectors.toList());

        if (instances.isEmpty()) {
            throw new ResourceNotFoundException("Study", studyUID);
        }

        return buildMetadataResponse(instances.stream(), mediaType, baseUri, studyUID);
    }

    // ---- Retrieve Study (multipart) ----

    @ApiOperation(value = "Retrieve all instances in a study (site-wide)")
    @ApiResponses({
            @ApiResponse(code = 200, message = "Study retrieved"),
            @ApiResponse(code = 404, message = "Not found")
    })
    @XapiRequestMapping(
            value = "/dicomweb/studies/{studyUID}",
            method = RequestMethod.GET)
    public void retrieveStudy(
            @PathVariable final String studyUID,
            HttpServletResponse response) throws DicomWebException, IOException {

        ResponseEntity<?> check = checkSiteWideEnabled();
        if (check != null) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            response.getWriter().write("Site-wide DICOMweb querying is not enabled");
            return;
        }

        // Resolve every file in the study on the request thread; see retrieveSeries above
        // for the ThreadLocal-context rationale.
        final List<File> files = dicomService.resolveStudyFiles(getSessionUser(), null, studyUID);
        if (files.isEmpty()) {
            throw new ResourceNotFoundException("Study", studyUID);
        }

        final String boundary = UUID.randomUUID().toString();
        response.setContentType("multipart/related; type=\"" + APPLICATION_DICOM + "\"; boundary=" + boundary);
        response.setStatus(HttpStatus.OK.value());
        response.flushBuffer();

        streamFilesAsMultipart(response.getOutputStream(), boundary, files,
                "site-wide study " + studyUID);
    }

    /**
     * Stream a pre-resolved list of DICOM files as multipart/related parts to the given
     * output stream. Pure file I/O — no XDAT calls — so this is safe to run on a thread
     * that doesn't inherit the request thread's ThreadLocal context (e.g. the servlet
     * container's async-dispatch worker). Per-file errors are logged at WARN and the
     * response continues with the next part; status + headers are already committed by
     * the time this runs, so aborting mid-write would produce a truncated multipart
     * that's worse than a missing part.
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

    // ---- Rendered Instance ----

    @ApiOperation(value = "Retrieve a rendered instance (site-wide)")
    @ApiResponses({
            @ApiResponse(code = 200, message = "Rendered instance retrieved"),
            @ApiResponse(code = 404, message = "Not found")
    })
    @XapiRequestMapping(
            value = "/dicomweb/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}/rendered",
            method = RequestMethod.GET,
            produces = {IMAGE_JPEG, IMAGE_PNG, IMAGE_GIF})
    public void retrieveRenderedInstance(
            @PathVariable String studyUID,
            @PathVariable String seriesUID,
            @PathVariable String instanceUID,
            @RequestParam(required = false) String viewport,
            @RequestParam(required = false) String window,
            @RequestParam(required = false) String quality,
            @RequestParam(value = "accept", required = false) String acceptParam,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String acceptHeader,
            HttpServletResponse response) throws DicomWebException, IOException {

        ResponseEntity<?> check = checkSiteWideEnabled();
        if (check != null) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return;
        }

        RenderedInstanceResult result = dicomService.retrieveRenderedInstance(
                getSessionUser(), null, studyUID, seriesUID, instanceUID, null,
                ImageFormat.fromMimeType(MediaTypeNegotiator.negotiate(
                        acceptHeader, acceptParam, RENDERED_TYPES, RENDERED_DEFAULT)),
                RenderingParams.parse(viewport, window, quality));

        writeRenderedResponse(result, instanceUID, response);
    }

    // ---- Rendered Study ----

    @ApiOperation(value = "Retrieve a rendered study (site-wide)")
    @ApiResponses({
            @ApiResponse(code = 200, message = "Rendered study retrieved"),
            @ApiResponse(code = 404, message = "Not found")
    })
    @XapiRequestMapping(
            value = "/dicomweb/studies/{studyUID}/rendered",
            method = RequestMethod.GET,
            produces = {IMAGE_JPEG, IMAGE_PNG, IMAGE_GIF})
    public void retrieveRenderedStudy(
            @PathVariable String studyUID,
            @RequestParam(required = false) String viewport,
            @RequestParam(required = false) String window,
            @RequestParam(required = false) String quality,
            @RequestParam(value = "accept", required = false) String acceptParam,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String acceptHeader,
            HttpServletResponse response) throws DicomWebException, IOException {

        ResponseEntity<?> check = checkSiteWideEnabled();
        if (check != null) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return;
        }

        RenderedInstanceResult result = dicomService.retrieveRenderedStudy(
                getSessionUser(), null, studyUID, null,
                ImageFormat.fromMimeType(MediaTypeNegotiator.negotiate(
                        acceptHeader, acceptParam, RENDERED_TYPES, RENDERED_DEFAULT)),
                RenderingParams.parse(viewport, window, quality));

        writeRenderedResponse(result, studyUID, response);
    }

    // ---- Rendered Series ----

    @ApiOperation(value = "Retrieve a rendered series (site-wide)")
    @ApiResponses({
            @ApiResponse(code = 200, message = "Rendered series retrieved"),
            @ApiResponse(code = 404, message = "Not found")
    })
    @XapiRequestMapping(
            value = "/dicomweb/studies/{studyUID}/series/{seriesUID}/rendered",
            method = RequestMethod.GET,
            produces = {IMAGE_JPEG, IMAGE_PNG, IMAGE_GIF})
    public void retrieveRenderedSeries(
            @PathVariable String studyUID,
            @PathVariable String seriesUID,
            @RequestParam(required = false) String viewport,
            @RequestParam(required = false) String window,
            @RequestParam(required = false) String quality,
            @RequestParam(value = "accept", required = false) String acceptParam,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String acceptHeader,
            HttpServletResponse response) throws DicomWebException, IOException {

        ResponseEntity<?> check = checkSiteWideEnabled();
        if (check != null) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return;
        }

        RenderedInstanceResult result = dicomService.retrieveRenderedSeries(
                getSessionUser(), null, studyUID, seriesUID, null,
                ImageFormat.fromMimeType(MediaTypeNegotiator.negotiate(
                        acceptHeader, acceptParam, RENDERED_TYPES, RENDERED_DEFAULT)),
                RenderingParams.parse(viewport, window, quality));

        writeRenderedResponse(result, seriesUID, response);
    }

    // ---- Rendered Frame ----

    @ApiOperation(value = "Retrieve a rendered frame (site-wide)")
    @ApiResponses({
            @ApiResponse(code = 200, message = "Rendered frame retrieved"),
            @ApiResponse(code = 404, message = "Not found")
    })
    @XapiRequestMapping(
            value = "/dicomweb/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}/frames/{frameList}/rendered",
            method = RequestMethod.GET,
            produces = {IMAGE_JPEG, IMAGE_PNG, IMAGE_GIF})
    public void retrieveRenderedFrame(
            @PathVariable String studyUID,
            @PathVariable String seriesUID,
            @PathVariable String instanceUID,
            @PathVariable String frameList,
            @RequestParam(required = false) String viewport,
            @RequestParam(required = false) String window,
            @RequestParam(required = false) String quality,
            @RequestParam(value = "accept", required = false) String acceptParam,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String acceptHeader,
            HttpServletResponse response) throws DicomWebException, IOException {

        ResponseEntity<?> check = checkSiteWideEnabled();
        if (check != null) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return;
        }

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
                getSessionUser(), null, studyUID, seriesUID, instanceUID, frameNumber,
                ImageFormat.fromMimeType(MediaTypeNegotiator.negotiate(
                        acceptHeader, acceptParam, RENDERED_TYPES, RENDERED_DEFAULT)),
                RenderingParams.parse(viewport, window, quality));

        writeRenderedResponse(result, instanceUID, response);
    }

    // ---- Study Thumbnail ----

    @ApiOperation(value = "Retrieve a study thumbnail (site-wide)")
    @ApiResponses({
            @ApiResponse(code = 200, message = "Thumbnail retrieved"),
            @ApiResponse(code = 404, message = "Not found")
    })
    @XapiRequestMapping(
            value = "/dicomweb/studies/{studyUID}/thumbnail",
            method = RequestMethod.GET,
            produces = {IMAGE_JPEG, IMAGE_PNG, IMAGE_GIF})
    public void retrieveStudyThumbnail(
            @PathVariable String studyUID,
            @RequestParam(required = false) String viewport,
            @RequestParam(value = "accept", required = false) String acceptParam,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String acceptHeader,
            HttpServletResponse response) throws DicomWebException, IOException {

        ResponseEntity<?> check = checkSiteWideEnabled();
        if (check != null) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return;
        }

        RenderedInstanceResult result = dicomService.retrieveThumbnailStudy(
                getSessionUser(), null, studyUID,
                RenderingParams.parse(viewport, null, null),
                ImageFormat.fromMimeType(MediaTypeNegotiator.negotiate(
                        acceptHeader, acceptParam, RENDERED_TYPES, RENDERED_DEFAULT)));

        writeRenderedResponse(result, studyUID, response);
    }

    // ---- Series Thumbnail ----

    @ApiOperation(value = "Retrieve a series thumbnail (site-wide)")
    @ApiResponses({
            @ApiResponse(code = 200, message = "Thumbnail retrieved"),
            @ApiResponse(code = 404, message = "Not found")
    })
    @XapiRequestMapping(
            value = "/dicomweb/studies/{studyUID}/series/{seriesUID}/thumbnail",
            method = RequestMethod.GET,
            produces = {IMAGE_JPEG, IMAGE_PNG, IMAGE_GIF})
    public void retrieveSeriesThumbnail(
            @PathVariable String studyUID,
            @PathVariable String seriesUID,
            @RequestParam(required = false) String viewport,
            @RequestParam(value = "accept", required = false) String acceptParam,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String acceptHeader,
            HttpServletResponse response) throws DicomWebException, IOException {

        ResponseEntity<?> check = checkSiteWideEnabled();
        if (check != null) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return;
        }

        RenderedInstanceResult result = dicomService.retrieveThumbnailSeries(
                getSessionUser(), null, studyUID, seriesUID,
                RenderingParams.parse(viewport, null, null),
                ImageFormat.fromMimeType(MediaTypeNegotiator.negotiate(
                        acceptHeader, acceptParam, RENDERED_TYPES, RENDERED_DEFAULT)));

        writeRenderedResponse(result, seriesUID, response);
    }

    // ---- Instance Thumbnail ----

    @ApiOperation(value = "Retrieve an instance thumbnail (site-wide)")
    @ApiResponses({
            @ApiResponse(code = 200, message = "Thumbnail retrieved"),
            @ApiResponse(code = 404, message = "Not found")
    })
    @XapiRequestMapping(
            value = "/dicomweb/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}/thumbnail",
            method = RequestMethod.GET,
            produces = {IMAGE_JPEG, IMAGE_PNG, IMAGE_GIF})
    public void retrieveInstanceThumbnail(
            @PathVariable String studyUID,
            @PathVariable String seriesUID,
            @PathVariable String instanceUID,
            @RequestParam(required = false) String viewport,
            @RequestParam(value = "accept", required = false) String acceptParam,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String acceptHeader,
            HttpServletResponse response) throws DicomWebException, IOException {

        ResponseEntity<?> check = checkSiteWideEnabled();
        if (check != null) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return;
        }

        RenderedInstanceResult result = dicomService.retrieveThumbnailInstance(
                getSessionUser(), null, studyUID, seriesUID, instanceUID,
                RenderingParams.parse(viewport, null, null),
                ImageFormat.fromMimeType(MediaTypeNegotiator.negotiate(
                        acceptHeader, acceptParam, RENDERED_TYPES, RENDERED_DEFAULT)));

        writeRenderedResponse(result, instanceUID, response);
    }

    // ---- Frame Thumbnail ----

    @ApiOperation(value = "Retrieve a frame thumbnail (site-wide)")
    @ApiResponses({
            @ApiResponse(code = 200, message = "Thumbnail retrieved"),
            @ApiResponse(code = 404, message = "Not found")
    })
    @XapiRequestMapping(
            value = "/dicomweb/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}/frames/{frameList}/thumbnail",
            method = RequestMethod.GET,
            produces = {IMAGE_JPEG, IMAGE_PNG, IMAGE_GIF})
    public void retrieveFrameThumbnail(
            @PathVariable String studyUID,
            @PathVariable String seriesUID,
            @PathVariable String instanceUID,
            @PathVariable String frameList,
            @RequestParam(required = false) String viewport,
            @RequestParam(value = "accept", required = false) String acceptParam,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String acceptHeader,
            HttpServletResponse response) throws DicomWebException, IOException {

        ResponseEntity<?> check = checkSiteWideEnabled();
        if (check != null) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return;
        }

        RenderedInstanceResult result = dicomService.retrieveThumbnailFrame(
                getSessionUser(), null, studyUID, seriesUID, instanceUID, frameList,
                RenderingParams.parse(viewport, null, null),
                ImageFormat.fromMimeType(MediaTypeNegotiator.negotiate(
                        acceptHeader, acceptParam, RENDERED_TYPES, RENDERED_DEFAULT)));

        writeRenderedResponse(result, instanceUID, response);
    }

    // ---- Frame Retrieval ----

    @ApiOperation(value = "Retrieve specific frames from an instance (site-wide)")
    @ApiResponses({
            @ApiResponse(code = 200, message = "Frames retrieved"),
            @ApiResponse(code = 404, message = "Not found")
    })
    @XapiRequestMapping(
            value = "/dicomweb/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}/frames/{frameList}",
            method = RequestMethod.GET)
    public void retrieveFrames(
            @PathVariable String studyUID,
            @PathVariable String seriesUID,
            @PathVariable String instanceUID,
            @PathVariable String frameList,
            HttpServletResponse response) throws DicomWebException, IOException {

        ResponseEntity<?> check = checkSiteWideEnabled();
        if (check != null) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            response.getWriter().write("Site-wide DICOMweb querying is not enabled");
            return;
        }

        List<byte[]> frames = dicomService.retrieveFrames(
                getSessionUser(), null, studyUID, seriesUID, instanceUID, frameList);

        if (frames == null || frames.isEmpty()) {
            throw new ResourceNotFoundException("Frames", frameList + " in instance " + instanceUID);
        }

        createMultipartFrameResponse(response, frames);
    }

    // ---- Bulk Data by Tag ----

    @ApiOperation(value = "Retrieve bulk data for a specific tag (site-wide)")
    @ApiResponses({
            @ApiResponse(code = 200, message = "Bulk data retrieved"),
            @ApiResponse(code = 404, message = "Not found")
    })
    @XapiRequestMapping(
            value = "/dicomweb/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}/bulkdata/{tag}",
            method = RequestMethod.GET,
            produces = APPLICATION_OCTET_STREAM_VALUE)
    public void retrieveBulkDataByTag(
            @PathVariable String studyUID,
            @PathVariable String seriesUID,
            @PathVariable String instanceUID,
            @PathVariable String tag,
            HttpServletRequest request,
            HttpServletResponse response) throws DicomWebException, IOException {

        ResponseEntity<?> check = checkSiteWideEnabled();
        if (check != null) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return;
        }

        int tagInt;
        try {
            tagInt = Integer.parseUnsignedInt(tag, 16);
        } catch (NumberFormatException e) {
            throw new BadRequestException("tag", "must be a valid hexadecimal DICOM tag");
        }

        final File file = dicomService.resolveInstanceFile(getSessionUser(), null, studyUID, seriesUID, instanceUID);

        byte[] bulkData;
        try (DicomInputStream dis = new DicomInputStream(file)) {
            dis.setIncludeBulkData(DicomInputStream.IncludeBulkData.YES);
            final Attributes attrs = dis.readDataset();

            if (!attrs.contains(tagInt)) {
                throw new ResourceNotFoundException("Bulk data tag", tag);
            }

            bulkData = attrs.getBytes(tagInt);

            if (bulkData == null || bulkData.length == 0) {
                throw new ResourceNotFoundException("Bulk data tag", tag);
            }
        }

        String contentLocation = BulkDataHandler.generateBulkDataURI(
                BulkDataHandler.extractBaseUri(request.getRequestURL().toString(), null),
                studyUID, seriesUID, instanceUID, tagInt);

        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setContentLength(bulkData.length);
        response.setHeader("Content-Location", contentLocation);
        response.getOutputStream().write(bulkData);
        response.getOutputStream().flush();
    }

    // ---- Instance Bulk Data ----

    @ApiOperation(value = "Retrieve all bulk data for an instance (site-wide)")
    @ApiResponses({
            @ApiResponse(code = 200, message = "Bulk data retrieved"),
            @ApiResponse(code = 404, message = "Not found")
    })
    @XapiRequestMapping(
            value = "/dicomweb/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}/bulkdata",
            method = RequestMethod.GET)
    public void retrieveInstanceBulkData(
            @PathVariable String studyUID,
            @PathVariable String seriesUID,
            @PathVariable String instanceUID,
            HttpServletRequest request,
            HttpServletResponse response) throws DicomWebException, IOException {

        ResponseEntity<?> check = checkSiteWideEnabled();
        if (check != null) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            response.getWriter().write("Site-wide DICOMweb querying is not enabled");
            return;
        }

        String baseUri = BulkDataHandler.extractBaseUri(request.getRequestURL().toString(), null);

        List<BulkDataItem> bulkDataItems = dicomService.retrieveInstanceBulkData(
                getSessionUser(), null, studyUID, seriesUID, instanceUID, baseUri);

        buildMultipartBulkDataResponse(response, bulkDataItems);
    }

    // ---- Series Bulk Data ----

    @ApiOperation(value = "Retrieve all bulk data for a series (site-wide)")
    @ApiResponses({
            @ApiResponse(code = 200, message = "Bulk data retrieved"),
            @ApiResponse(code = 404, message = "Not found")
    })
    @XapiRequestMapping(
            value = "/dicomweb/studies/{studyUID}/series/{seriesUID}/bulkdata",
            method = RequestMethod.GET)
    public void retrieveSeriesBulkData(
            @PathVariable String studyUID,
            @PathVariable String seriesUID,
            HttpServletRequest request,
            HttpServletResponse response) throws DicomWebException, IOException {

        ResponseEntity<?> check = checkSiteWideEnabled();
        if (check != null) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            response.getWriter().write("Site-wide DICOMweb querying is not enabled");
            return;
        }

        String baseUri = BulkDataHandler.extractBaseUri(request.getRequestURL().toString(), null);

        List<BulkDataItem> bulkDataItems = dicomService.retrieveSeriesBulkData(
                getSessionUser(), null, studyUID, seriesUID, baseUri);

        buildMultipartBulkDataResponse(response, bulkDataItems);
    }

    // ---- Study Bulk Data ----

    @ApiOperation(value = "Retrieve all bulk data for a study (site-wide)")
    @ApiResponses({
            @ApiResponse(code = 200, message = "Bulk data retrieved"),
            @ApiResponse(code = 404, message = "Not found")
    })
    @XapiRequestMapping(
            value = "/dicomweb/studies/{studyUID}/bulkdata",
            method = RequestMethod.GET)
    public void retrieveStudyBulkData(
            @PathVariable String studyUID,
            HttpServletRequest request,
            HttpServletResponse response) throws DicomWebException, IOException {

        ResponseEntity<?> check = checkSiteWideEnabled();
        if (check != null) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            response.getWriter().write("Site-wide DICOMweb querying is not enabled");
            return;
        }

        String baseUri = BulkDataHandler.extractBaseUri(request.getRequestURL().toString(), null);

        List<BulkDataItem> bulkDataItems = dicomService.retrieveStudyBulkData(
                getSessionUser(), null, studyUID, baseUri);

        buildMultipartBulkDataResponse(response, bulkDataItems);
    }

    // ---- Instance Pixel Data ----

    @ApiOperation(value = "Retrieve pixel data for an instance (site-wide)")
    @ApiResponses({
            @ApiResponse(code = 200, message = "Pixel data retrieved"),
            @ApiResponse(code = 404, message = "Not found")
    })
    @XapiRequestMapping(
            value = "/dicomweb/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}/pixeldata",
            method = RequestMethod.GET)
    public void retrieveInstancePixelData(
            @PathVariable String studyUID,
            @PathVariable String seriesUID,
            @PathVariable String instanceUID,
            HttpServletRequest request,
            HttpServletResponse response) throws DicomWebException, IOException {

        ResponseEntity<?> check = checkSiteWideEnabled();
        if (check != null) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            response.getWriter().write("Site-wide DICOMweb querying is not enabled");
            return;
        }

        String baseUri = BulkDataHandler.extractBaseUri(request.getRequestURL().toString(), null);

        List<BulkDataItem> items = dicomService.retrieveInstancePixelData(
                getSessionUser(), null, studyUID, seriesUID, instanceUID, baseUri);

        buildMultipartBulkDataResponse(response, items);
    }

    // ---- Series Pixel Data ----

    @ApiOperation(value = "Retrieve pixel data for a series (site-wide)")
    @ApiResponses({
            @ApiResponse(code = 200, message = "Pixel data retrieved"),
            @ApiResponse(code = 404, message = "Not found")
    })
    @XapiRequestMapping(
            value = "/dicomweb/studies/{studyUID}/series/{seriesUID}/pixeldata",
            method = RequestMethod.GET)
    public void retrieveSeriesPixelData(
            @PathVariable String studyUID,
            @PathVariable String seriesUID,
            HttpServletRequest request,
            HttpServletResponse response) throws DicomWebException, IOException {

        ResponseEntity<?> check = checkSiteWideEnabled();
        if (check != null) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            response.getWriter().write("Site-wide DICOMweb querying is not enabled");
            return;
        }

        String baseUri = BulkDataHandler.extractBaseUri(request.getRequestURL().toString(), null);

        List<BulkDataItem> items = dicomService.retrieveSeriesPixelData(
                getSessionUser(), null, studyUID, seriesUID, baseUri);

        buildMultipartBulkDataResponse(response, items);
    }

    // ---- Study Pixel Data ----

    @ApiOperation(value = "Retrieve pixel data for a study (site-wide)")
    @ApiResponses({
            @ApiResponse(code = 200, message = "Pixel data retrieved"),
            @ApiResponse(code = 404, message = "Not found")
    })
    @XapiRequestMapping(
            value = "/dicomweb/studies/{studyUID}/pixeldata",
            method = RequestMethod.GET)
    public void retrieveStudyPixelData(
            @PathVariable String studyUID,
            HttpServletRequest request,
            HttpServletResponse response) throws DicomWebException, IOException {

        ResponseEntity<?> check = checkSiteWideEnabled();
        if (check != null) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            response.getWriter().write("Site-wide DICOMweb querying is not enabled");
            return;
        }

        String baseUri = BulkDataHandler.extractBaseUri(request.getRequestURL().toString(), null);

        List<BulkDataItem> items = dicomService.retrieveStudyPixelData(
                getSessionUser(), null, studyUID, baseUri);

        buildMultipartBulkDataResponse(response, items);
    }

    // ---- Series Metadata ----

    @ApiOperation(value = "Retrieve series metadata (site-wide)")
    @ApiResponses({
            @ApiResponse(code = 200, message = "Metadata retrieved"),
            @ApiResponse(code = 404, message = "Not found")
    })
    @XapiRequestMapping(
            value = "/dicomweb/studies/{studyUID}/series/{seriesUID}/metadata",
            method = RequestMethod.GET,
            produces = {APPLICATION_DICOM_JSON, APPLICATION_DICOM_XML})
    public ResponseEntity<?> retrieveSeriesMetadata(
            @PathVariable String studyUID,
            @PathVariable String seriesUID,
            @RequestParam(value = "accept", required = false) String acceptParam,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String acceptHeader,
            HttpServletRequest request) throws DicomWebException, IOException {

        ResponseEntity<?> check = checkSiteWideEnabled();
        if (check != null) return check;

        String mediaType = MediaTypeNegotiator.negotiate(acceptHeader, acceptParam, METADATA_TYPES, METADATA_DEFAULT);
        String baseUri = BulkDataHandler.extractBaseUri(request.getRequestURL().toString(), null);

        List<Attributes> instances = dicomService.searchMetadata(getSessionUser(), null, studyUID, seriesUID, null)
                .collect(Collectors.toList());
        if (instances.isEmpty()) {
            throw new ResourceNotFoundException("Series metadata", seriesUID);
        }

        return buildMetadataResponse(instances.stream(), mediaType, baseUri, studyUID);
    }

    // ---- Helper Methods ----

    /**
     * Write a rendered image result to the HTTP response with appropriate headers.
     * Copied from WadoRsApi.
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
     * Build a metadata response (JSON or XML) from a stream of instance attributes.
     * Copied from WadoRsApi.
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

    private void buildMultipartBulkDataResponse(HttpServletResponse response,
                                                List<BulkDataItem> bulkDataItems) throws IOException {
        String boundary = UUID.randomUUID().toString();
        response.setContentType("multipart/related; type=\"" + APPLICATION_OCTET_STREAM_VALUE + "\"; boundary=" + boundary);
        response.setStatus(HttpStatus.OK.value());

        for (BulkDataItem item : bulkDataItems) {
            response.getOutputStream().write(("--" + boundary + "\r\n").getBytes());
            response.getOutputStream().write(("Content-Type: application/octet-stream\r\n").getBytes());
            response.getOutputStream().write(("Content-Location: " + item.getContentLocation() + "\r\n\r\n").getBytes());
            response.getOutputStream().write(item.getData());
            response.getOutputStream().write("\r\n".getBytes());
        }
        response.getOutputStream().write(("--" + boundary + "--\r\n").getBytes());
        response.getOutputStream().flush();
    }

    private void createMultipartFrameResponse(HttpServletResponse response,
                                              List<byte[]> frames) throws IOException {
        String boundary = UUID.randomUUID().toString();
        response.setContentType("multipart/related; type=\"" + APPLICATION_OCTET_STREAM_VALUE + "\"; boundary=" + boundary);
        response.setStatus(HttpStatus.OK.value());

        for (byte[] frameData : frames) {
            response.getOutputStream().write(("--" + boundary + "\r\n").getBytes());
            response.getOutputStream().write(("Content-Type: application/octet-stream\r\n\r\n").getBytes());
            response.getOutputStream().write(frameData);
            response.getOutputStream().write("\r\n".getBytes());
        }
        response.getOutputStream().write(("--" + boundary + "--\r\n").getBytes());
        response.getOutputStream().flush();
    }
}
