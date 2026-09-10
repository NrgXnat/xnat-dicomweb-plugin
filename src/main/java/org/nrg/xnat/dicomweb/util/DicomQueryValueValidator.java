/*
 * XNAT DICOMweb Plugin
 * Copyright (c) 2026 XNATWorks.
 * All rights reserved.
 */

package org.nrg.xnat.dicomweb.util;

import org.nrg.xnat.dicomweb.exceptions.BadRequestException;

/**
 * Validates QIDO-RS query parameter values for attributes of VR DA and
 * TM, rejecting malformed values with HTTP 400.
 *
 * <p>PS3.18 &sect;8.3.4.1 defers the acceptable values of a search
 * parameter to the C-FIND matching rules:
 *
 * <blockquote>
 * The acceptable values are determined by the types of matching
 * allowed by C-FIND for its associated attribute. See Section
 * C.2.2.2 in PS3.4.
 * </blockquote>
 *
 * <p>PS3.4 &sect;C.2.2.2.1 distinguishes the two matching types that
 * apply to a DA or TM value by the presence of a hyphen &mdash; Single
 * Value Matching applies when the value is "of VR of DA, TM or DT and
 * contains a single value with no '-' and no QUOTATION MARK
 * characters", and Range Matching (&sect;C.2.2.2.5) otherwise. This
 * class applies the same split.
 *
 * <p>Wildcard matching is <em>not</em> available for these VRs.
 * PS3.4 &sect;C.2.2.2.4 scopes it to "AE, CS, LO, LT, PN, SH, ST, UC,
 * UR, UT", and &sect;C.2.2.2 notes that "the wild card characters '*'
 * and '?' are not valid for the CS VR but are used for Wild Card
 * Matching". A {@code *} or {@code ?} in a date or time parameter is
 * therefore rejected, with one tolerance: the bare value {@code "*"}
 * is accepted as Universal Matching, per the &sect;C.2.2.2.4 note
 * that "Wild Card Matching on a value of '*' is equivalent to
 * Universal Matching". Callers treat it as though the parameter were
 * absent.
 *
 * <p>PS3.18 &sect;10.6.3.1 Table 10.6.3-1 gives the status code for a
 * value this class rejects: "400 (Bad Request) &mdash; The was a
 * problem with the request. For example, the Query Parameter syntax
 * is incorrect."
 */
public final class DicomQueryValueValidator {

    /** The bare wildcard, accepted as Universal Matching. */
    private static final String UNIVERSAL = "*";

    private DicomQueryValueValidator() {}

    /**
     * Validate a DA query parameter value.
     *
     * @param paramName query parameter name, as spelled by the client
     * @param value     the raw value
     * @return true if the value is a filter to apply, false if it is
     *         Universal Matching and the parameter should be dropped
     * @throws BadRequestException if the value is malformed
     */
    public static boolean validateDate(String paramName, String value) {
        return validate(paramName, value, true);
    }

    /**
     * Validate a TM query parameter value.
     *
     * @param paramName query parameter name, as spelled by the client
     * @param value     the raw value
     * @return true if the value is a filter to apply, false if it is
     *         Universal Matching and the parameter should be dropped
     * @throws BadRequestException if the value is malformed
     */
    public static boolean validateTime(String paramName, String value) {
        return validate(paramName, value, false);
    }

    private static boolean validate(String paramName, String value, boolean isDate) {
        if (value == null || value.isEmpty() || UNIVERSAL.equals(value)) {
            return false;
        }
        rejectWildcards(paramName, value);

        if (value.indexOf('-') >= 0) {
            // Range Matching, PS3.4 §C.2.2.2.5. Endpoints are
            // validated by the range parser, which throws on a
            // malformed endpoint or a malformed range structure.
            if (isDate) {
                DicomRangeParser.parseDicomDateRange(value, paramName);
            } else {
                DicomRangeParser.parseDicomTimeRange(value, paramName);
            }
            return true;
        }

        // Single Value Matching, PS3.4 §C.2.2.2.1.
        if (isDate) {
            DicomDateTimeValues.parseDate(paramName, value);
        } else {
            DicomDateTimeValues.parseTime(paramName, value);
        }
        return true;
    }

    private static void rejectWildcards(String paramName, String value) {
        if (value.indexOf('*') >= 0 || value.indexOf('?') >= 0) {
            throw new BadRequestException(paramName,
                    "wildcard matching is not defined for dates and times "
                    + "(PS3.4 C.2.2.2.4); use an exact value or a range");
        }
    }
}
