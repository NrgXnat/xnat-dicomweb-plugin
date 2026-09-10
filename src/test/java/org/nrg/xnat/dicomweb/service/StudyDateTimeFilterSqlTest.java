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
import org.nrg.xnat.dicomweb.service.impl.XnatDicomServiceImpl;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
        assertEquals("10:00", r.value("q_study_time_start"));
        assertEquals("18:00", r.value("q_study_time_end"));
    }

    // ---- Combined DA + TM, PS3.18 §8.3.4.1.1 → PS3.4 §C.2.2.2.5.4 ----

    @Test
    public void matchingRangeFormsCollapseToASingleTimestampClause() throws Exception {
        // The worked example from the PS3.4 §C.2.2.2.5.4 Note.
        Result r = filterFor("20060705-20060707", "1000-1800");
        assertTrue(r.sql, r.sql.contains("(e.date + e.time) >= CAST(:q_study_dt_start AS TIMESTAMP)"));
        assertTrue(r.sql, r.sql.contains("(e.date + e.time) <= CAST(:q_study_dt_end AS TIMESTAMP)"));
        assertEquals("2006-07-05T10:00", r.value("q_study_dt_start"));
        assertEquals("2006-07-07T18:00", r.value("q_study_dt_end"));
        // The independent date/time clauses must not also be emitted.
        assertFalse(r.sql, r.sql.contains("q_study_date_start"));
        assertFalse(r.sql, r.sql.contains("q_study_time_start"));
    }

    @Test
    public void mismatchedRangeFormsFallBackToIndependentClauses() throws Exception {
        Result r = filterFor("20250101-20250131", "1000-");
        assertFalse(r.sql, r.sql.contains("e.date + e.time"));
        assertEquals("2025-01-01", r.value("q_study_date_start"));
        assertEquals("10:00", r.value("q_study_time_start"));
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
