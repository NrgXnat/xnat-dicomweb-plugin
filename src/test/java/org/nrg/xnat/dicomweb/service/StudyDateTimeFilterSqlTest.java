/*
 * XNAT DICOMweb Plugin
 * Copyright (c) 2026 XNATWorks.
 * All rights reserved.
 */

package org.nrg.xnat.dicomweb.service;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.junit.BeforeClass;
import org.junit.Test;
import org.nrg.xnat.dicomweb.exceptions.DicomWebException;
import org.nrg.xnat.dicomweb.service.impl.XnatDicomServiceImpl;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests the SQL emitted for StudyDate / StudyTime query parameters by
 * {@code XnatDicomServiceImpl.appendStudyDateTimeFilter}.
 *
 * <p>Values are written here exactly as {@code QidoQueryParamParser}
 * would leave them, i.e. already validated, since that is the only
 * way they reach this code.
 */
public class StudyDateTimeFilterSqlTest {

    private static Method appendStudyDateTimeFilter;

    @BeforeClass
    public static void resolveMethod() throws Exception {
        appendStudyDateTimeFilter = XnatDicomServiceImpl.class.getDeclaredMethod(
                "appendStudyDateTimeFilter",
                StringBuilder.class, MapSqlParameterSource.class, Attributes.class);
        appendStudyDateTimeFilter.setAccessible(true);
    }

    private static final class Result {
        final String sql;
        final MapSqlParameterSource params;
        Result(String sql, MapSqlParameterSource params) {
            this.sql = sql;
            this.params = params;
        }
        Object value(String name) {
            return params.hasValue(name) ? params.getValue(name) : null;
        }
    }

    private static Result filterFor(String studyDate, String studyTime) throws Exception {
        Attributes attrs = new Attributes();
        if (studyDate != null) {
            attrs.setString(Tag.StudyDate, VR.DA, studyDate);
        }
        if (studyTime != null) {
            attrs.setString(Tag.StudyTime, VR.TM, studyTime);
        }
        StringBuilder sql = new StringBuilder();
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendStudyDateTimeFilter.invoke(null, sql, params, attrs);
        return new Result(sql.toString(), params);
    }

    // ---- Single value matching ----

    @Test
    public void exactDateBindsAnIsoDate() throws Exception {
        Result r = filterFor("20250115", null);
        assertTrue(r.sql, r.sql.contains("e.date = CAST(:q_study_date AS DATE)"));
        assertEquals("2025-01-15", r.value("q_study_date"));
    }

    @Test
    public void exactDateNoLongerBuildsAnIlikeClause() throws Exception {
        // Regression guard for the removed wildcard branch.
        Result r = filterFor("20250115", null);
        assertFalse(r.sql, r.sql.contains("ILIKE"));
    }

    @Test
    public void fullPrecisionTimeComparesAllSixDigits() throws Exception {
        Result r = filterFor(null, "103000");
        assertTrue(r.sql, r.sql.contains("LEFT(TO_CHAR(e.time, 'HH24MISS'), :q_study_time_digits)"));
        assertEquals(6, r.value("q_study_time_digits"));
        assertEquals("103000", r.value("q_study_time"));
    }

    @Test
    public void hourPrecisionTimeComparesTwoDigits() throws Exception {
        Result r = filterFor(null, "10");
        assertEquals(2, r.value("q_study_time_digits"));
        assertEquals("10", r.value("q_study_time"));
    }

    @Test
    public void minutePrecisionTimeComparesFourDigits() throws Exception {
        Result r = filterFor(null, "1030");
        assertEquals(4, r.value("q_study_time_digits"));
        assertEquals("1030", r.value("q_study_time"));
    }

    @Test
    public void fractionalSecondIsIgnoredForSingleValueMatching() throws Exception {
        // XNAT stores no sub-second precision.
        Result r = filterFor(null, "103000.500000");
        assertEquals(6, r.value("q_study_time_digits"));
        assertEquals("103000", r.value("q_study_time"));
    }

    // ---- Range matching ----

    @Test
    public void closedDateRangeBindsBothBounds() throws Exception {
        Result r = filterFor("20250101-20250131", null);
        assertTrue(r.sql, r.sql.contains("e.date >= CAST(:q_study_date_start AS DATE)"));
        assertTrue(r.sql, r.sql.contains("e.date <= CAST(:q_study_date_end AS DATE)"));
        assertEquals("2025-01-01", r.value("q_study_date_start"));
        assertEquals("2025-01-31", r.value("q_study_date_end"));
    }

    @Test
    public void openUpperDateRangeBindsOnlyStart() throws Exception {
        Result r = filterFor("20250101-", null);
        assertTrue(r.sql, r.sql.contains("e.date >= CAST(:q_study_date_start AS DATE)"));
        assertFalse(r.sql, r.sql.contains("q_study_date_end"));
        assertNull(r.value("q_study_date_end"));
    }

    @Test
    public void universalRangeMarkerEmitsNoClause() throws Exception {
        Result r = filterFor("-", null);
        assertEquals("", r.sql);
    }

    @Test
    public void partialPrecisionTimeRangeResolvesToWholeMinutes() throws Exception {
        Result r = filterFor(null, "1000-1800");
        assertEquals("10:00:00.000000", r.value("q_study_time_start"));
        assertEquals("18:00:00.000000", r.value("q_study_time_end"));
    }

    // ---- Leap seconds bind within Postgres resolution ----
    // Regression tests for the QA finding that a TM range endpoint with
    // SS=60 was treated as the following minute: the bound was rendered
    // with a nanosecond fraction, which Postgres rounds up to the next
    // microsecond and carries into the next second.

    @Test
    public void leapSecondEndBindsAtMicrosecondPrecision() throws Exception {
        Result r = filterFor(null, "235959-235960");
        assertEquals("23:59:59.999999", r.value("q_study_time_end"));
    }

    @Test
    public void everyTimeBoundRendersAtMostSixFractionalDigits() throws Exception {
        // The actual defect was in the rendered string, not the
        // LocalTime, so assert on the bound as SQL will see it. A test
        // that compared LocalTime values would have passed while the
        // bug was live.
        assertAtMostMicroseconds(filterFor(null, "235959-235960"), "q_study_time_end");
        assertAtMostMicroseconds(filterFor(null, "080000.123456-180000"), "q_study_time_start");
        assertAtMostMicroseconds(filterFor(null, "1000-1800"), "q_study_time_end");
    }

    @Test
    public void combinedLeapSecondBoundRendersAtMostSixFractionalDigits() throws Exception {
        Result r = filterFor("20260630-20260630", "235959-235960");
        assertEquals("2026-06-30T23:59:59.999999", r.value("q_study_dt_end"));
        assertAtMostMicroseconds(r, "q_study_dt_end");
    }

    @Test
    public void leapSecondOutsideMinute59NeverReachesSql() {
        // ?StudyTime=103000-103060, the QA example.
        assertRejected(null, "103000-103060", "StudyTime");
    }

    @Test
    public void singleValueLeapSecondComparesRawDigits() throws Exception {
        // Single-value matching does not go through the clamp; it
        // compares the literal digits, and TO_CHAR never emits SS=60,
        // so this correctly matches nothing. Pinned so it is not
        // mistaken for a bug later.
        Result r = filterFor(null, "235960");
        assertTrue(r.sql, r.sql.contains("LEFT(TO_CHAR(e.time, 'HH24MISS'), :q_study_time_digits)"));
        assertEquals("235960", r.value("q_study_time"));
        assertEquals(6, r.value("q_study_time_digits"));
    }

    private static void assertAtMostMicroseconds(Result r, String param) {
        String bound = (String) r.value(param);
        assertNotNull("expected a bound for " + param, bound);
        int dot = bound.indexOf('.');
        int digits = (dot < 0) ? 0 : bound.length() - dot - 1;
        assertTrue(param + " must not exceed 6 fractional digits, got " + bound,
                digits <= 6);
    }

    // ---- Inverted ranges never reach SQL ----
    // Validation normally happens at the REST boundary, but the query
    // builder re-parses the range, so an inverted value is rejected
    // here too rather than emitting a self-contradictory clause pair
    // (date >= end AND date <= start) that can only match nothing.

    @Test
    public void invertedDateRangeThrowsInsteadOfEmittingClauses() {
        assertRejected("20250131-20250101", null, "StudyDate");
    }

    @Test
    public void invertedTimeRangeThrowsInsteadOfEmittingClauses() {
        assertRejected(null, "180000-080000", "StudyTime");
    }

    private static void assertRejected(String studyDate, String studyTime, String paramName) {
        try {
            filterFor(studyDate, studyTime);
            fail("expected the inverted range to be rejected");
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            assertTrue("expected a DicomWebException, got " + cause,
                    cause instanceof DicomWebException);
            assertEquals(400, ((DicomWebException) cause).getHttpStatus());
            assertTrue("message should name " + paramName + ": " + cause.getMessage(),
                    cause.getMessage().contains(paramName));
        } catch (Exception e) {
            throw new AssertionError("unexpected exception type", e);
        }
    }

    // ---- Combined DA + TM, PS3.18 §8.3.4.1.1 → PS3.4 §C.2.2.2.5.4 ----

    @Test
    public void matchingRangeFormsCollapseToASingleTimestampClause() throws Exception {
        // The worked example from the PS3.4 §C.2.2.2.5.4 Note.
        Result r = filterFor("20060705-20060707", "1000-1800");
        assertTrue(r.sql, r.sql.contains("(e.date + e.time) >= CAST(:q_study_dt_start AS TIMESTAMP)"));
        assertTrue(r.sql, r.sql.contains("(e.date + e.time) <= CAST(:q_study_dt_end AS TIMESTAMP)"));
        assertEquals("2006-07-05T10:00:00.000000", r.value("q_study_dt_start"));
        assertEquals("2006-07-07T18:00:00.000000", r.value("q_study_dt_end"));
        // The independent date/time clauses must not also be emitted.
        assertFalse(r.sql, r.sql.contains("q_study_date_start"));
        assertFalse(r.sql, r.sql.contains("q_study_time_start"));
    }

    @Test
    public void mismatchedRangeFormsFallBackToIndependentClauses() throws Exception {
        Result r = filterFor("20250101-20250131", "1000-");
        assertFalse(r.sql, r.sql.contains("e.date + e.time"));
        assertEquals("2025-01-01", r.value("q_study_date_start"));
        assertEquals("10:00:00.000000", r.value("q_study_time_start"));
    }

    @Test
    public void dateRangeWithExactTimeStaysIndependent() throws Exception {
        Result r = filterFor("20250101-20250131", "103000");
        assertFalse(r.sql, r.sql.contains("e.date + e.time"));
        assertEquals("2025-01-01", r.value("q_study_date_start"));
        assertEquals("103000", r.value("q_study_time"));
    }

    // ---- Absent parameters ----

    @Test
    public void noDateOrTimeEmitsNothing() throws Exception {
        Result r = filterFor(null, null);
        assertEquals("", r.sql);
    }
}
