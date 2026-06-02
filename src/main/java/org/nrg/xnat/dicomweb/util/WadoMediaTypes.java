/*
 * XNAT DICOMweb Plugin
 * Copyright (c) 2026 XNATWorks.
 * All rights reserved.
 */

package org.nrg.xnat.dicomweb.util;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.dcm4che3.ws.rs.MediaTypes.APPLICATION_DICOM;
import static org.dcm4che3.ws.rs.MediaTypes.APPLICATION_DICOM_JSON;
import static org.dcm4che3.ws.rs.MediaTypes.APPLICATION_DICOM_XML;
import static org.dcm4che3.ws.rs.MediaTypes.IMAGE_GIF;
import static org.dcm4che3.ws.rs.MediaTypes.IMAGE_JPEG;
import static org.dcm4che3.ws.rs.MediaTypes.IMAGE_PNG;
import static org.dcm4che3.ws.rs.MediaTypes.MULTIPART_RELATED;
import static org.springframework.http.MediaType.APPLICATION_OCTET_STREAM_VALUE;

/**
 * Server-supported media types for WADO-RS resource categories,
 * shared by the per-project and site-wide WADO-RS controllers.
 *
 * <p>DICOM_TYPES uses bare {@code MULTIPART_RELATED} (not
 * {@code MULTIPART_RELATED_APPLICATION_DICOM}) so the negotiator's
 * type/subtype-only matching can match a client's
 * {@code Accept: multipart/related[;type="application/dicom"]} header.
 * Multipart is listed first so a wildcard Accept resolves to it over
 * {@code application/dicom}.</p>
 */
public final class WadoMediaTypes {
    private WadoMediaTypes() {}

    public static final List<String> DICOM_TYPES =
            Arrays.asList(MULTIPART_RELATED, APPLICATION_DICOM);
    public static final String INSTANCE_DEFAULT = APPLICATION_DICOM;

    public static final List<String> METADATA_TYPES =
            Arrays.asList(APPLICATION_DICOM_JSON, APPLICATION_DICOM_XML);
    public static final String METADATA_DEFAULT = APPLICATION_DICOM_JSON;

    public static final List<String> RENDERED_TYPES =
            Arrays.asList(IMAGE_JPEG, IMAGE_PNG, IMAGE_GIF);
    public static final String RENDERED_DEFAULT = IMAGE_JPEG;

    public static final List<String> FRAME_TYPES =
            Arrays.asList(APPLICATION_OCTET_STREAM_VALUE, MULTIPART_RELATED);

    public static final List<String> BULKDATA_TYPES =
            Collections.singletonList(APPLICATION_OCTET_STREAM_VALUE);

    public static final List<String> MULTIPART_OCTET_TYPES =
            Arrays.asList(MULTIPART_RELATED, APPLICATION_OCTET_STREAM_VALUE);
}
