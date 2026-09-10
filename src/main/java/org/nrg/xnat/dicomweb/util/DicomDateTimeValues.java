/*
 * XNAT DICOMweb Plugin
 * Copyright (c) 2026 XNATWorks.
 * All rights reserved.
 */

package org.nrg.xnat.dicomweb.util;

import org.nrg.xnat.dicomweb.exceptions.BadRequestException;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

/**
 * Lexical and semantic validation of DICOM DA and TM values appearing
 * as QIDO-RS query parameter values. This is the single source of
 * truth for DA/TM syntax in the plugin; {@link DicomRangeParser} uses
 * it for range endpoints and {@link QidoQueryParamParser} uses it to
 * reject malformed values at the REST boundary.
 *
 * <p>Grammar per PS3.5 &sect;6.2 Table 6.2-1:
 *
 * <blockquote>
 * <b>DA</b> &mdash; "A string of characters of the format YYYYMMDD;
 * where YYYY shall contain year, MM shall contain the month, and DD
 * shall contain the day, interpreted as a date of the Gregorian
 * calendar system." Length is "8 bytes fixed".
 * </blockquote>
 *
 * <blockquote>
 * <b>TM</b> &mdash; "A string of characters of the format
 * HHMMSS.FFFFFF; where HH contains hours (range "00" - "23"), MM
 * contains minutes (range "00" - "59"), SS contains seconds (range
 * "00" - "60"), and FFFFFF contains a fractional part of a second as
 * small as 1 millionth of a second&hellip; One or more of the
 * components MM, SS, or FFFFFF may be unspecified as long as every
 * component to the right of an unspecified component is also
 * unspecified, which indicates that the Value is not precise to the
 * precision of those unspecified components. The FFFFFF component,
 * if present, shall contain 1 to 6 digits. If FFFFFF is unspecified
 * the preceding "." shall not be included."
 * </blockquote>
 *
 * <p>PS3.5 gives DA no partial-precision form, so dates must always
 * be a full 8 digits. TM does have one, so {@code "10"},
 * {@code "1030"}, {@code "103000"} and {@code "103000.5"} are all
 * accepted; PS3.5 cites {@code "021"} as an explicitly invalid value.
 *
 * <p>Both VRs permit trailing SPACE padding, which is stripped before
 * parsing.
 */
public final class DicomDateTimeValues {

    // Strict resolver style catches invalid calendar dates like
    // 20200230; it requires the proleptic-year pattern 'uuuu' rather
    // than 'yyyy' since the latter needs an era in strict mode.
    private static final DateTimeFormatter DA_FORMAT =
            DateTimeFormatter.ofPattern("uuuuMMdd")
                    .withResolverStyle(ResolverStyle.STRICT);

    private DicomDateTimeValues() {}

    /**
     * Parse a DICOM DA value.
     *
     * @param paramName query parameter name, for the error message
     * @param value     the raw value; trailing SPACE padding is allowed
     * @return the parsed date
     * @throws BadRequestException if the value is not a valid DA
     */
    public static LocalDate parseDate(String paramName, String value) {
        final String v = stripPadding(value);
        if (v.length() != 8 || !isAllDigits(v)) {
            throw new BadRequestException(paramName,
                    "'" + value + "' is not a valid DICOM date; "
                    + "expected 8 digits in the form yyyyMMdd");
        }
        try {
            return LocalDate.parse(v, DA_FORMAT);
        } catch (DateTimeParseException e) {
            throw new BadRequestException(paramName,
                    "'" + value + "' is not a valid calendar date");
        }
    }

    /**
     * Parse a DICOM TM value, including the partial-precision forms
     * {@code HH} and {@code HHMM} and an optional fractional second.
     *
     * <p>Unspecified components are resolved to zero, so {@code "10"}
     * denotes 10:00:00 exactly. A leap second ({@code SS} = 60, which
     * PS3.5 permits) is clamped to the last representable instant of
     * the same minute, since {@link LocalTime} cannot represent it.
     *
     * @param paramName query parameter name, for the error message
     * @param value     the raw value; trailing SPACE padding is allowed
     * @return the parsed time
     * @throws BadRequestException if the value is not a valid TM
     */
    public static LocalTime parseTime(String paramName, String value) {
        final String v = stripPadding(value);
        final int dot = v.indexOf('.');
        final String whole = (dot < 0) ? v : v.substring(0, dot);
        final String fraction = (dot < 0) ? "" : v.substring(dot + 1);

        // Components are two digits each and may only be omitted from
        // the right, so the integral part is 2, 4, or 6 digits.
        if (!isAllDigits(whole)
                || (whole.length() != 2 && whole.length() != 4 && whole.length() != 6)) {
            throw new BadRequestException(paramName,
                    "'" + value + "' is not a valid DICOM time; expected "
                    + "HH, HHmm, HHmmss, or HHmmss.FFFFFF");
        }
        // "If FFFFFF is unspecified the preceding '.' shall not be
        // included", and a fraction is only meaningful once seconds
        // are specified.
        if (dot >= 0 && (whole.length() != 6
                || fraction.isEmpty() || fraction.length() > 6
                || !isAllDigits(fraction))) {
            throw new BadRequestException(paramName,
                    "'" + value + "' is not a valid DICOM time; a fractional "
                    + "second must follow HHmmss and contain 1 to 6 digits");
        }

        final int hour = twoDigitsAt(whole, 0);
        final int minute = (whole.length() >= 4) ? twoDigitsAt(whole, 2) : 0;
        final int second = (whole.length() >= 6) ? twoDigitsAt(whole, 4) : 0;

        if (hour > 23 || minute > 59 || second > 60) {
            throw new BadRequestException(paramName,
                    "'" + value + "' is not a valid DICOM time; hours must be "
                    + "00-23, minutes 00-59, seconds 00-60");
        }
        if (second == 60) {
            // Leap second: no LocalTime equivalent, so use the last
            // instant of the minute for comparison purposes.
            return LocalTime.of(hour, minute, 59, 999_999_999);
        }
        return LocalTime.of(hour, minute, second, nanosOf(fraction));
    }

    /**
     * The significant leading digits of a TM value, i.e. {@code HH},
     * {@code HHmm}, or {@code HHmmss}. Used to build a
     * precision-matched comparison against a stored time; the
     * fractional second is dropped because XNAT stores no sub-second
     * precision.
     *
     * @param value a TM value already accepted by
     *              {@link #parseTime(String, String)}
     * @return the significant digits, of length 2, 4, or 6
     */
    public static String timeMatchPrefix(String value) {
        final String v = stripPadding(value);
        final int dot = v.indexOf('.');
        return (dot < 0) ? v : v.substring(0, dot);
    }

    // PS3.5 allows trailing SPACE padding on both DA and TM. Leading
    // and embedded spaces are not allowed, so only strip the tail.
    private static String stripPadding(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == ' ') {
            end--;
        }
        return value.substring(0, end);
    }

    private static boolean isAllDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static int twoDigitsAt(String s, int index) {
        return (s.charAt(index) - '0') * 10 + (s.charAt(index + 1) - '0');
    }

    // Right-pad the fractional digits to nanosecond precision.
    private static int nanosOf(String fraction) {
        if (fraction.isEmpty()) {
            return 0;
        }
        final StringBuilder b = new StringBuilder(fraction);
        while (b.length() < 9) {
            b.append('0');
        }
        return Integer.parseInt(b.toString());
    }
}
