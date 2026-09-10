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
import org.nrg.xnat.dicomweb.config.DicomWebProperties;
import org.nrg.xnat.dicomweb.service.SiteWideProjectFilter;
import org.nrg.xnat.dicomweb.service.XnatDicomService;
import org.nrg.xnat.dicomweb.util.DicomWebUtils;
import org.nrg.xnat.dicomweb.util.QidoQueryParamParser;
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

@XapiRestController
@Api("DICOMweb Site-Wide QIDO-RS API")
public class SiteWideQidoRsApi extends AbstractXapiRestController {

    private static final Logger log = LoggerFactory.getLogger(SiteWideQidoRsApi.class);

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 1000;

    private final XnatDicomService dicomService;
    private final DicomWebProperties dicomWebProperties;
    private final SiteWideProjectFilter siteWideProjectFilter;

    @Autowired
    public SiteWideQidoRsApi(final UserManagementServiceI userManagementService,
                             final RoleHolder roleHolder,
                             final XnatDicomService dicomService,
                             final DicomWebProperties dicomWebProperties,
                             final SiteWideProjectFilter siteWideProjectFilter) {
        super(userManagementService, roleHolder);
        this.dicomService = dicomService;
        this.dicomWebProperties = dicomWebProperties;
        this.siteWideProjectFilter = siteWideProjectFilter;
    }

    @ApiOperation(value = "Search for studies (site-wide)")
    @ApiResponses({
            @ApiResponse(code = 200, message = "Successfully retrieved studies"),
            @ApiResponse(code = 404, message = "Site-wide querying is not enabled")
    })
    @XapiRequestMapping(value = "/dicomweb/studies", method = RequestMethod.GET, produces = MediaType.ALL_VALUE)
    public ResponseEntity<String> searchStudies(
            @RequestParam(required = false) final Map<String, String> queryParameters) {
        if (!siteWideProjectFilter.isSiteWideEnabled()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        final UserI user = getSessionUser();
        final Attributes queryAttributes = QidoQueryParamParser.parse(queryParameters);
        final int limit = getLimit(queryParameters);
        final int offset = getOffset(queryParameters);

        log.debug("Site-wide QIDO-RS search for studies, limit={}, offset={}", limit, offset);

        final List<Attributes> results = dicomService.searchStudies(user, null, queryAttributes);
        final List<Attributes> paginated = applyPagination(results, limit, offset);

        final String json = paginated.stream()
                .map(attrs -> {
                    try { return DicomWebUtils.toJson(attrs); }
                    catch (Exception e) { log.error("Error converting study to JSON", e); return "{}"; }
                })
                .collect(Collectors.joining(",", "[", "]"));

        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(results.size()))
                .contentType(MediaType.parseMediaType(DicomWebUtils.getDicomJsonContentType()))
                .body(json);
    }

    @ApiOperation(value = "Search for series within a study (site-wide)")
    @ApiResponses({
            @ApiResponse(code = 200, message = "Successfully retrieved series"),
            @ApiResponse(code = 404, message = "Site-wide querying is not enabled")
    })
    @XapiRequestMapping(value = "/dicomweb/studies/{studyUID}/series", method = RequestMethod.GET, produces = MediaType.ALL_VALUE)
    public ResponseEntity<String> searchSeries(
            @PathVariable final String studyUID,
            @RequestParam(required = false) final Map<String, String> queryParameters) {
        if (!siteWideProjectFilter.isSiteWideEnabled()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        final UserI user = getSessionUser();
        final Attributes queryAttributes = QidoQueryParamParser.parse(queryParameters);
        final int limit = getLimit(queryParameters);
        final int offset = getOffset(queryParameters);

        log.debug("Site-wide QIDO-RS search for series in study {}, limit={}, offset={}", studyUID, limit, offset);

        final List<Attributes> results = dicomService.searchSeries(user, null, studyUID, queryAttributes);
        final List<Attributes> paginated = applyPagination(results, limit, offset);

        final String json = paginated.stream()
                .map(attrs -> {
                    try { return DicomWebUtils.toJson(attrs); }
                    catch (Exception e) { log.error("Error converting series to JSON", e); return "{}"; }
                })
                .collect(Collectors.joining(",", "[", "]"));

        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(results.size()))
                .contentType(MediaType.parseMediaType(DicomWebUtils.getDicomJsonContentType()))
                .body(json);
    }

    @ApiOperation(value = "Search for instances within a series (site-wide)")
    @ApiResponses({
            @ApiResponse(code = 200, message = "Successfully retrieved instances"),
            @ApiResponse(code = 404, message = "Site-wide querying is not enabled")
    })
    @XapiRequestMapping(value = "/dicomweb/studies/{studyUID}/series/{seriesUID}/instances", method = RequestMethod.GET, produces = MediaType.ALL_VALUE)
    public ResponseEntity<String> searchInstances(
            @PathVariable final String studyUID,
            @PathVariable final String seriesUID,
            @RequestParam(required = false) final Map<String, String> queryParameters) {
        if (!siteWideProjectFilter.isSiteWideEnabled()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        final UserI user = getSessionUser();
        final Attributes queryAttributes = QidoQueryParamParser.parse(queryParameters);
        final int limit = getLimit(queryParameters);
        final int offset = getOffset(queryParameters);

        log.debug("Site-wide QIDO-RS search for instances in study {}, series {}, limit={}, offset={}",
                studyUID, seriesUID, limit, offset);

        final List<Attributes> results = dicomService.searchInstances(user, null, studyUID, seriesUID, queryAttributes);
        final List<Attributes> paginated = applyPagination(results, limit, offset);

        final String json = paginated.stream()
                .map(attrs -> {
                    try { return DicomWebUtils.toJson(attrs); }
                    catch (Exception e) { log.error("Error converting instance to JSON", e); return "{}"; }
                })
                .collect(Collectors.joining(",", "[", "]"));

        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(results.size()))
                .contentType(MediaType.parseMediaType(DicomWebUtils.getDicomJsonContentType()))
                .body(json);
    }

    private int getLimit(final Map<String, String> params) {
        if (params == null) return DEFAULT_LIMIT;
        final String limitStr = params.get("limit");
        if (limitStr != null) {
            try {
                final int limit = Integer.parseInt(limitStr);
                return Math.min(Math.max(limit, 1), MAX_LIMIT);
            } catch (final NumberFormatException e) {
                log.warn("Invalid limit parameter: {}", limitStr);
            }
        }
        return DEFAULT_LIMIT;
    }

    private int getOffset(final Map<String, String> params) {
        if (params == null) return 0;
        final String offsetStr = params.get("offset");
        if (offsetStr != null) {
            try {
                return Math.max(Integer.parseInt(offsetStr), 0);
            } catch (final NumberFormatException e) {
                log.warn("Invalid offset parameter: {}", offsetStr);
            }
        }
        return 0;
    }

    private List<Attributes> applyPagination(final List<Attributes> results, final int limit, final int offset) {
        return results.stream()
                .skip(offset)
                .limit(limit)
                .collect(Collectors.toList());
    }

}
