/*
 * XNAT DICOMweb Plugin
 * Copyright (c) 2026 XNATWorks.
 * All rights reserved.
 */

package org.nrg.xnat.dicomweb.util;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.ElementDictionary;
import org.dcm4che3.data.Keyword;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Parses QIDO-RS query parameters into a DICOM {@link Attributes}
 * carrier for downstream SQL / in-memory filtering.
 *
 * <p>Per PS3.18 &sect;6.7.1.1, an {@code {attributeID}} in a QIDO-RS
 * query may be either a DICOM keyword (e.g. {@code StudyDate}) or an
 * 8-digit hexadecimal Data Element Tag (e.g. {@code 00080020}).
 * DICOMweb clients differ on which form they emit — Weasis and OHIF
 * historically send tag numbers, XNAT's own front end sends keywords
 * — so both must be accepted. This parser matches keywords and tag
 * numbers case-insensitively, and additionally tolerates the DIMSE
 * spellings {@code (0008,0020)} and {@code 0008,0020} that some
 * clients emit even though PS3.18 does not require them.
 *
 * <p>Only the parameters enumerated in the plugin's QIDO-RS
 * conformance table (CONFORMANCE &sect;6.2) are recognized; anything
 * else is logged at debug and dropped.
 *
 * <p>Values of recognized parameters whose VR constrains their syntax
 * are validated here, at the REST boundary, by
 * {@link DicomQueryValueValidator}. This is deliberate: it is the one
 * point every QIDO-RS endpoint passes through, and it sits outside
 * the query-execution error handling, so a malformed value surfaces
 * as HTTP 400 rather than being swallowed into an empty result set.
 */
public final class QidoQueryParamParser {

    private static final Logger log = LoggerFactory.getLogger(QidoQueryParamParser.class);

    private QidoQueryParamParser() {}

    private static final class TagInfo {
        final int tag;
        final VR vr;
        TagInfo(int tag, VR vr) {
            this.tag = tag;
            this.vr = vr;
        }
    }

    private static final Map<String, TagInfo> SUPPORTED;
    static {
        Map<String, TagInfo> m = new HashMap<>();
        register(m, Tag.PatientName);
        register(m, Tag.PatientID);
        register(m, Tag.StudyDate);
        register(m, Tag.StudyTime);
        register(m, Tag.StudyInstanceUID);
        register(m, Tag.AccessionNumber);
        register(m, Tag.Modality);
        register(m, Tag.SeriesDescription);
        register(m, Tag.SeriesInstanceUID);
        register(m, Tag.SeriesNumber);
        register(m, Tag.SOPInstanceUID);
        register(m, Tag.SOPClassUID);
        register(m, Tag.InstanceNumber);

        // ModalitiesInStudy (0008,0061) is currently treated as an
        // alias for Modality (0008,0060) — see PLUGINS-325.
        alias(m, Tag.ModalitiesInStudy, Tag.Modality);

        SUPPORTED = Collections.unmodifiableMap(m);
    }

    // Register a tag under both its DICOM keyword (e.g. "StudyDate")
    // and its 8-hex-digit tag string (e.g. "00080020"), pulling both
    // the keyword and VR from dcm4che's standard element dictionary
    // so nothing has to be re-spelled here.
    private static void register(Map<String, TagInfo> m, int tag) {
        TagInfo info = new TagInfo(tag, ElementDictionary.vrOf(tag, null));
        m.put(Keyword.valueOf(tag).toLowerCase(), info);
        m.put(hex8(tag), info);
    }

    // Register {@code aliasTag} (both keyword and hex forms) as
    // another spelling of {@code canonicalTag}. The value gets
    // written into the query attributes under canonicalTag.
    private static void alias(Map<String, TagInfo> m, int aliasTag, int canonicalTag) {
        TagInfo canonical = m.get(Keyword.valueOf(canonicalTag).toLowerCase());
        m.put(Keyword.valueOf(aliasTag).toLowerCase(), canonical);
        m.put(hex8(aliasTag), canonical);
    }

    private static String hex8(int tag) {
        return String.format("%08x", tag);
    }

    /**
     * Convert an HTTP query-parameter map into a DICOM
     * {@link Attributes} filter, keyed by DICOM tag. Empty or
     * unrecognized entries are skipped.
     *
     * @param queryParams raw query parameters as delivered by Spring
     * @return the parsed attributes; never null, possibly empty
     */
    public static Attributes parse(Map<String, String> queryParams) {
        Attributes attrs = new Attributes();
        if (queryParams == null || queryParams.isEmpty()) {
            return attrs;
        }
        for (Map.Entry<String, String> entry : queryParams.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || value == null || value.isEmpty()) {
                continue;
            }
            TagInfo info = SUPPORTED.get(normalize(key));
            if (info == null) {
                log.debug("Unsupported query parameter: {}", key);
                continue;
            }
            if (!isApplicableFilter(key, value, info.vr)) {
                continue;
            }
            attrs.setString(info.tag, info.vr, value);
        }
        log.debug("Parsed {} query parameters into DICOM attributes", attrs.size());
        return attrs;
    }

    // Validate values whose VR constrains their syntax, so that a
    // malformed date or time is reported as HTTP 400 here rather than
    // silently degrading into an empty or unfiltered result set
    // downstream. Returns false when the value denotes Universal
    // Matching, in which case the parameter is dropped rather than
    // carried into the query as a filter.
    private static boolean isApplicableFilter(String key, String value, VR vr) {
        if (vr == VR.DA) {
            return DicomQueryValueValidator.validateDate(key, value);
        }
        if (vr == VR.TM) {
            return DicomQueryValueValidator.validateTime(key, value);
        }
        return true;
    }

    // Lower-case and strip parentheses / commas so that the
    // 8-hex-digit form is matched whether the client emits
    // "00080020", "(0008,0020)", or "0008,0020".
    private static String normalize(String key) {
        StringBuilder b = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '(' || c == ')' || c == ',') {
                continue;
            }
            b.append(Character.toLowerCase(c));
        }
        return b.toString();
    }
}
