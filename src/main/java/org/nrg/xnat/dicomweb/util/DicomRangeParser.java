/*
 * XNAT DICOMweb Plugin
 * Copyright (c) 2026 XNATWorks.
 * All rights reserved.
 */

package org.nrg.xnat.dicomweb.util;

import org.nrg.xnat.dicomweb.exceptions.BadRequestException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Optional;

/**
 * Parses DICOM DA and TM range-match query values as defined in
 * PS3.4 &sect;C.2.2.2.5.1 (dates) and &sect;C.2.2.2.5.2 (times).
 * Callers use this to decide whether to emit a range clause, an
 * exact-match clause, or a wildcard clause when translating QIDO-RS
 * query parameters to SQL.
 *
 * <p>Range values are of the form {@code start-end}, {@code start-},
 * {@code -end}, or the bare universal marker {@code -}. Wildcards
 * ({@code *}, {@code ?}) are not permitted inside a range endpoint.
 *
 * <p>Endpoints must be full 8-digit {@code yyyyMMdd} dates or
 * 6-digit {@code HHmmss} times. Partial precision and fractional
 * seconds are not accepted; a malformed value raises
 * {@link BadRequestException} (HTTP 400).
 */
public final class DicomRangeParser {

    // Strict resolver style catches invalid calendar dates like
    // 20200230; it requires the proleptic-year pattern 'uuuu' rather
    // than 'yyyy' since the latter needs an era in strict mode.
    private static final DateTimeFormatter DA_FORMAT =
            DateTimeFormatter.ofPattern("uuuuMMdd")
                    .withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter TM_FORMAT =
            DateTimeFormatter.ofPattern("HHmmss")
                    .withResolverStyle(ResolverStyle.STRICT);

    private DicomRangeParser() {}

    /**
     * Parse a QIDO-RS StudyDate query value as a DICOM DA range.
     *
     * @param value the raw query value
     * @return the parsed range, or {@link Optional#empty()} if the
     *         value is not a range (caller should fall through to
     *         exact or wildcard matching)
     * @throws BadRequestException if the value looks like a range but
     *                             is malformed
     */
    public static Optional<DicomDateRange> parseDicomDateRange(String value) {
        if (value == null || !value.contains("-")) {
            return Optional.empty();
        }
        String[] parts = splitRange(value, "StudyDate");
        LocalDate start = parts[0].isEmpty()
                ? null : parseDate(parts[0], "StudyDate");
        LocalDate end = parts[1].isEmpty()
                ? null : parseDate(parts[1], "StudyDate");
        return Optional.of(new DicomDateRange(start, end));
    }

    /**
     * Parse a QIDO-RS StudyTime query value as a DICOM TM range.
     *
     * @param value the raw query value
     * @return the parsed range, or {@link Optional#empty()} if the
     *         value is not a range
     * @throws BadRequestException if the value looks like a range but
     *                             is malformed
     */
    public static Optional<DicomTimeRange> parseDicomTimeRange(String value) {
        if (value == null || !value.contains("-")) {
            return Optional.empty();
        }
        String[] parts = splitRange(value, "StudyTime");
        LocalTime start = parts[0].isEmpty()
                ? null : parseTime(parts[0], "StudyTime");
        LocalTime end = parts[1].isEmpty()
                ? null : parseTime(parts[1], "StudyTime");
        return Optional.of(new DicomTimeRange(start, end));
    }

    private static String[] splitRange(String value, String paramName) {
        if (value.indexOf('*') >= 0 || value.indexOf('?') >= 0) {
            throw new BadRequestException(paramName,
                    "wildcards are not permitted inside a range endpoint");
        }
        String[] parts = value.split("-", -1);
        if (parts.length != 2) {
            throw new BadRequestException(paramName,
                    "expected 'start-end', 'start-', '-end', or '-'");
        }
        return parts;
    }

    private static LocalDate parseDate(String s, String paramName) {
        try {
            return LocalDate.parse(s, DA_FORMAT);
        } catch (DateTimeParseException e) {
            throw new BadRequestException(paramName,
                    "range endpoint '" + s + "' is not a valid DICOM "
                    + "date (yyyyMMdd)");
        }
    }

    private static LocalTime parseTime(String s, String paramName) {
        try {
            return LocalTime.parse(s, TM_FORMAT);
        } catch (DateTimeParseException e) {
            throw new BadRequestException(paramName,
                    "range endpoint '" + s + "' is not a valid DICOM "
                    + "time (HHmmss)");
        }
    }

    /**
     * A parsed DICOM DA range. Either bound may be {@code null},
     * denoting an open-ended range. Both bounds {@code null} denotes
     * the universal marker {@code -} (semantically equivalent to no
     * filter).
     */
    public static final class DicomDateRange {
        public final LocalDate start;
        public final LocalDate end;

        public DicomDateRange(LocalDate start, LocalDate end) {
            this.start = start;
            this.end = end;
        }

        /**
         * @return true if both bounds are null (equivalent to no filter)
         */
        public boolean isUniversal() {
            return start == null && end == null;
        }
    }

    /**
     * A parsed DICOM TM range. See {@link DicomDateRange} for
     * {@code null}-bound semantics.
     */
    public static final class DicomTimeRange {
        public final LocalTime start;
        public final LocalTime end;

        public DicomTimeRange(LocalTime start, LocalTime end) {
            this.start = start;
            this.end = end;
        }

        /**
         * @return true if both bounds are null (equivalent to no filter)
         */
        public boolean isUniversal() {
            return start == null && end == null;
        }
    }

    /**
     * A combined DICOM DA + TM range, treated as a single DT-VR
     * range per PS3.18 &sect;8.3.4.1.1 (deferring to PS3.4
     * &sect;C.2.2.2.5.4). Either bound may be {@code null}, denoting
     * an open-ended range; both {@code null} denotes the universal
     * marker (semantically equivalent to no filter).
     */
    public static final class DicomDateTimeRange {
        public final LocalDateTime start;
        public final LocalDateTime end;

        public DicomDateTimeRange(LocalDateTime start, LocalDateTime end) {
            this.start = start;
            this.end = end;
        }

        /**
         * @return true if both bounds are null (equivalent to no filter)
         */
        public boolean isUniversal() {
            return start == null && end == null;
        }
    }

    /**
     * Combine a DA range and a TM range into a single DT-VR range
     * per PS3.18 &sect;8.3.4.1.1 &rarr; PS3.4 &sect;C.2.2.2.5.4:
     *
     * <blockquote>
     * &hellip;a pair of Attributes that are of VR DA and TM, both of
     * which specify the same form of Range Matching, shall have the
     * concatenated string values of each Range Matching component
     * matched as if they were a single Attribute of VR DT.
     * </blockquote>
     *
     * <p>"Same form of Range Matching" is interpreted narrowly: the
     * two ranges must share bound structure (both closed, both
     * open-upper {@code start-}, or both open-lower {@code -end}).
     * Mixed forms return {@link Optional#empty()}, at which point the
     * caller should fall through to independent DA + TM handling.
     *
     * @param dateRange the parsed StudyDate range (or empty if
     *                  StudyDate was absent, exact, wildcard, or
     *                  otherwise not a Range Matching value)
     * @param timeRange the parsed StudyTime range, same conventions
     * @return a combined DT range, or empty if the caller should
     *         fall through to independent handling
     */
    public static Optional<DicomDateTimeRange> combineIntoDateTimeRange(
            Optional<DicomDateRange> dateRange,
            Optional<DicomTimeRange> timeRange) {
        if (!dateRange.isPresent() || !timeRange.isPresent()) {
            return Optional.empty();
        }
        DicomDateRange dr = dateRange.get();
        DicomTimeRange tr = timeRange.get();

        // Narrow "same form" check: bound structure must match on
        // both start and end.
        boolean sameStartForm = (dr.start == null) == (tr.start == null);
        boolean sameEndForm = (dr.end == null) == (tr.end == null);
        if (!sameStartForm || !sameEndForm) {
            return Optional.empty();
        }

        LocalDateTime start = (dr.start != null)
                ? LocalDateTime.of(dr.start, tr.start) : null;
        LocalDateTime end = (dr.end != null)
                ? LocalDateTime.of(dr.end, tr.end) : null;
        return Optional.of(new DicomDateTimeRange(start, end));
    }
}
