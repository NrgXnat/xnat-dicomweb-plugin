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
import org.nrg.xnat.dicomweb.service.SiteWideProjectFilter;
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

@XapiRestController
@Api("DICOMweb Site-Wide WADO-RS API")
@Slf4j
public class SiteWideWadoRsApi extends AbstractXapiRestController {

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

    // ---- Retrieve Instance ----

    @ApiOperation(value = "Retrieve a DICOM instance (site-wide)")
    @ApiResponses({
            @ApiResponse(code = 200, message = "Instance retrieved"),
            @ApiResponse(code = 404, message = "Not found")
    })
    @XapiRequestMapping(
            value = "/dicomweb/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}",
            method = RequestMethod.GET,
            produces = {APPLICATION_DICOM, APPLICATION_OCTET_STREAM_VALUE})
    public ResponseEntity<?> retrieveInstance(
            @PathVariable String studyUID,
            @PathVariable String seriesUID,
            @PathVariable String instanceUID,
            HttpServletRequest request) throws DicomWebException, IOException {

        ResponseEntity<?> check = checkSiteWideEnabled();
        if (check != null) return check;

        UserI user = getSessionUser();

        InputStream dicomStream = dicomService.retrieveInstance(user, null, studyUID, seriesUID, instanceUID);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(APPLICATION_DICOM));

        return ResponseEntity.ok()
                .headers(headers)
                .body(new InputStreamResource(dicomStream));
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
            HttpServletRequest request) throws DicomWebException, IOException {

        ResponseEntity<?> check = checkSiteWideEnabled();
        if (check != null) return check;

        UserI user = getSessionUser();
        String selected = negotiateMediaType(request, METADATA_TYPES, METADATA_DEFAULT);
        String requestUrl = request.getRequestURL().toString();
        String baseUri = BulkDataHandler.extractBaseUri(requestUrl, null);

        Attributes attrs = dicomService.retrieveMetadata(user, null, studyUID, seriesUID, instanceUID);

        return buildMetadataResponse(Stream.of(attrs), selected, baseUri, studyUID);
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
            HttpServletRequest request,
            HttpServletResponse response) throws DicomWebException, IOException {

        ResponseEntity<?> check = checkSiteWideEnabled();
        if (check != null) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            response.getWriter().write("Site-wide DICOMweb querying is not enabled");
            return;
        }

        final UserI user = getSessionUser();

        // Resolve the files on the request thread, where the request's ThreadLocal context
        // (Spring TransactionSynchronizationManager, XDAT user/tx stash) is still bound.
        // The streaming write loop below is pure file I/O — no XDAT calls — so it's safe
        // even though the async dispatch thread doesn't inherit that context. Empty list
        // = 404, and the catalog walk parses no DICOM headers.
        final List<File> files = dicomService.resolveSeriesFiles(user, null, studyUID, seriesUID);
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
            HttpServletRequest request) throws DicomWebException, IOException {

        ResponseEntity<?> check = checkSiteWideEnabled();
        if (check != null) return check;

        UserI user = getSessionUser();
        String selected = negotiateMediaType(request, METADATA_TYPES, METADATA_DEFAULT);
        String requestUrl = request.getRequestURL().toString();
        String baseUri = BulkDataHandler.extractBaseUri(requestUrl, null);

        List<Attributes> instances = dicomService.retrieveAllStudyInstanceMetadata(user, null, studyUID)
                .collect(Collectors.toList());

        if (instances.isEmpty()) {
            throw new ResourceNotFoundException("Study", studyUID);
        }

        return buildMetadataResponse(instances.stream(), selected, baseUri, studyUID);
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
            HttpServletRequest request,
            HttpServletResponse response) throws DicomWebException, IOException {

        ResponseEntity<?> check = checkSiteWideEnabled();
        if (check != null) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            response.getWriter().write("Site-wide DICOMweb querying is not enabled");
            return;
        }

        final UserI user = getSessionUser();

        // Resolve every file in the study on the request thread; see retrieveSeries above
        // for the ThreadLocal-context rationale.
        final List<File> files = dicomService.resolveStudyFiles(user, null, studyUID);
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
            HttpServletRequest request,
            HttpServletResponse response) throws DicomWebException, IOException {

        ResponseEntity<?> check = checkSiteWideEnabled();
        if (check != null) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return;
        }

        UserI user = getSessionUser();
        String selected = negotiateMediaType(request, RENDERED_TYPES, RENDERED_DEFAULT);
        ImageFormat format = ImageFormat.fromMimeType(selected);
        RenderingParams params = RenderingParams.parse(viewport, window, quality);

        RenderedInstanceResult result = dicomService.retrieveRenderedInstance(
                user, null, studyUID, seriesUID, instanceUID, null, format, params);

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
            HttpServletRequest request,
            HttpServletResponse response) throws DicomWebException, IOException {

        ResponseEntity<?> check = checkSiteWideEnabled();
        if (check != null) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return;
        }

        UserI user = getSessionUser();
        String selected = negotiateMediaType(request, RENDERED_TYPES, RENDERED_DEFAULT);
        ImageFormat format = ImageFormat.fromMimeType(selected);
        RenderingParams params = RenderingParams.parse(viewport, window, quality);

        RenderedInstanceResult result = dicomService.retrieveRenderedStudy(
                user, null, studyUID, null, format, params);

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
            HttpServletRequest request,
            HttpServletResponse response) throws DicomWebException, IOException {

        ResponseEntity<?> check = checkSiteWideEnabled();
        if (check != null) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return;
        }

        UserI user = getSessionUser();
        String selected = negotiateMediaType(request, RENDERED_TYPES, RENDERED_DEFAULT);
        ImageFormat format = ImageFormat.fromMimeType(selected);
        RenderingParams params = RenderingParams.parse(viewport, window, quality);

        RenderedInstanceResult result = dicomService.retrieveRenderedSeries(
                user, null, studyUID, seriesUID, null, format, params);

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
            HttpServletRequest request,
            HttpServletResponse response) throws DicomWebException, IOException {

        ResponseEntity<?> check = checkSiteWideEnabled();
        if (check != null) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return;
        }

        UserI user = getSessionUser();
        String selected = negotiateMediaType(request, RENDERED_TYPES, RENDERED_DEFAULT);
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

        RenderedInstanceResult result = dicomService.retrieveRenderedInstance(
                user, null, studyUID, seriesUID, instanceUID, frameNumber, format, params);

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
            HttpServletRequest request,
            HttpServletResponse response) throws DicomWebException, IOException {

        ResponseEntity<?> check = checkSiteWideEnabled();
        if (check != null) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return;
        }

        UserI user = getSessionUser();
        String selected = negotiateMediaType(request, RENDERED_TYPES, RENDERED_DEFAULT);
        ImageFormat format = ImageFormat.fromMimeType(selected);
        RenderingParams params = RenderingParams.parse(viewport, null, null);

        RenderedInstanceResult result = dicomService.retrieveThumbnailStudy(
                user, null, studyUID, params, format);

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
            HttpServletRequest request,
            HttpServletResponse response) throws DicomWebException, IOException {

        ResponseEntity<?> check = checkSiteWideEnabled();
        if (check != null) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return;
        }

        UserI user = getSessionUser();
        String selected = negotiateMediaType(request, RENDERED_TYPES, RENDERED_DEFAULT);
        ImageFormat format = ImageFormat.fromMimeType(selected);
        RenderingParams params = RenderingParams.parse(viewport, null, null);

        RenderedInstanceResult result = dicomService.retrieveThumbnailSeries(
                user, null, studyUID, seriesUID, params, format);

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
            HttpServletRequest request,
            HttpServletResponse response) throws DicomWebException, IOException {

        ResponseEntity<?> check = checkSiteWideEnabled();
        if (check != null) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return;
        }

        UserI user = getSessionUser();
        String selected = negotiateMediaType(request, RENDERED_TYPES, RENDERED_DEFAULT);
        ImageFormat format = ImageFormat.fromMimeType(selected);
        RenderingParams params = RenderingParams.parse(viewport, null, null);

        RenderedInstanceResult result = dicomService.retrieveThumbnailInstance(
                user, null, studyUID, seriesUID, instanceUID, params, format);

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
            HttpServletRequest request,
            HttpServletResponse response) throws DicomWebException, IOException {

        ResponseEntity<?> check = checkSiteWideEnabled();
        if (check != null) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return;
        }

        UserI user = getSessionUser();
        String selected = negotiateMediaType(request, RENDERED_TYPES, RENDERED_DEFAULT);
        ImageFormat format = ImageFormat.fromMimeType(selected);
        RenderingParams params = RenderingParams.parse(viewport, null, null);

        RenderedInstanceResult result = dicomService.retrieveThumbnailFrame(
                user, null, studyUID, seriesUID, instanceUID, frameList, params, format);

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
            HttpServletRequest request,
            HttpServletResponse response) throws DicomWebException, IOException {

        ResponseEntity<?> check = checkSiteWideEnabled();
        if (check != null) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            response.getWriter().write("Site-wide DICOMweb querying is not enabled");
            return;
        }

        UserI user = getSessionUser();

        List<byte[]> frames = dicomService.retrieveFrames(
                user, null, studyUID, seriesUID, instanceUID, frameList);

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

        UserI user = getSessionUser();

        int tagInt;
        try {
            tagInt = Integer.parseUnsignedInt(tag, 16);
        } catch (NumberFormatException e) {
            throw new BadRequestException("tag", "must be a valid hexadecimal DICOM tag");
        }

        InputStream stream = dicomService.retrieveInstance(user, null, studyUID, seriesUID, instanceUID);

        byte[] bulkData;
        try (org.dcm4che3.io.DicomInputStream dis = new org.dcm4che3.io.DicomInputStream(stream)) {
            dis.setIncludeBulkData(org.dcm4che3.io.DicomInputStream.IncludeBulkData.YES);
            Attributes attrs = dis.readDataset();

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

        UserI user = getSessionUser();
        String baseUri = BulkDataHandler.extractBaseUri(request.getRequestURL().toString(), null);

        List<BulkDataItem> bulkDataItems = dicomService.retrieveInstanceBulkData(
                user, null, studyUID, seriesUID, instanceUID, baseUri);

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

        UserI user = getSessionUser();
        String baseUri = BulkDataHandler.extractBaseUri(request.getRequestURL().toString(), null);

        List<BulkDataItem> bulkDataItems = dicomService.retrieveSeriesBulkData(
                user, null, studyUID, seriesUID, baseUri);

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

        UserI user = getSessionUser();
        String baseUri = BulkDataHandler.extractBaseUri(request.getRequestURL().toString(), null);

        List<BulkDataItem> bulkDataItems = dicomService.retrieveStudyBulkData(
                user, null, studyUID, baseUri);

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

        UserI user = getSessionUser();
        String baseUri = BulkDataHandler.extractBaseUri(request.getRequestURL().toString(), null);

        List<BulkDataItem> items = dicomService.retrieveInstancePixelData(
                user, null, studyUID, seriesUID, instanceUID, baseUri);

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

        UserI user = getSessionUser();
        String baseUri = BulkDataHandler.extractBaseUri(request.getRequestURL().toString(), null);

        List<BulkDataItem> items = dicomService.retrieveSeriesPixelData(
                user, null, studyUID, seriesUID, baseUri);

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

        UserI user = getSessionUser();
        String baseUri = BulkDataHandler.extractBaseUri(request.getRequestURL().toString(), null);

        List<BulkDataItem> items = dicomService.retrieveStudyPixelData(
                user, null, studyUID, baseUri);

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
            HttpServletRequest request) throws DicomWebException, IOException {

        ResponseEntity<?> check = checkSiteWideEnabled();
        if (check != null) return check;

        UserI user = getSessionUser();
        String selected = negotiateMediaType(request, METADATA_TYPES, METADATA_DEFAULT);
        String requestUrl = request.getRequestURL().toString();
        String baseUri = BulkDataHandler.extractBaseUri(requestUrl, null);

        List<Attributes> instances = dicomService.searchMetadata(user, null, studyUID, seriesUID, null)
                .collect(Collectors.toList());

        if (instances.isEmpty()) {
            throw new ResourceNotFoundException("Series metadata", seriesUID);
        }

        return buildMetadataResponse(instances.stream(), selected, baseUri, studyUID);
    }

    // ---- Helper Methods ----

    private String negotiateMediaType(HttpServletRequest request, List<String> supported, String defaultType) {
        String acceptHeader = request != null ? request.getHeader("Accept") : null;
        return MediaTypeNegotiator.negotiate(acceptHeader, null, supported, defaultType);
    }

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
