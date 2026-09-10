/*
 * XNAT DICOMweb Plugin
 * Copyright (c) 2026 XNATWorks.
 * All rights reserved.
 */

package org.nrg.xnat.dicomweb.util;

import org.junit.Test;
import org.nrg.xnat.dicomweb.exceptions.BadRequestException;
import org.nrg.xnat.dicomweb.util.DicomRangeParser.DicomDateRange;
import org.nrg.xnat.dicomweb.util.DicomRangeParser.DicomDateTimeRange;
import org.nrg.xnat.dicomweb.util.DicomRangeParser.DicomTimeRange;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link DicomRangeParser}. Covers DICOM DA and TM
 * range parsing, including edge cases called out in the study-level
 * range implementation plan.
 */
public class DicomRangeParserTest {

    // ---- Date parsing: non-range values fall through ----

    @Test
    public void nullDateReturnsEmpty() {
        assertFalse(DicomRangeParser.parseDicomDateRange(null).isPresent());
    }

    @Test
    public void emptyDateReturnsEmpty() {
        assertFalse(DicomRangeParser.parseDicomDateRange("").isPresent());
    }

    @Test
    public void exactDateValueReturnsEmpty() {
        assertFalse(DicomRangeParser.parseDicomDateRange("20200101").isPresent());
    }

    @Test
    public void wildcardDateValueReturnsEmpty() {
        assertFalse(DicomRangeParser.parseDicomDateRange("2020*").isPresent());
        assertFalse(DicomRangeParser.parseDicomDateRange("202001??").isPresent());
    }

    // ---- Date parsing: valid ranges ----

    @Test
    public void closedDateRange() {
        DicomDateRange r = DicomRangeParser.parseDicomDateRange(
                "20200101-20201231").get();
        assertEquals(LocalDate.of(2020, 1, 1), r.start);
        assertEquals(LocalDate.of(2020, 12, 31), r.end);
        assertFalse(r.isUniversal());
    }

    @Test
    public void openUpperDateRange() {
        DicomDateRange r = DicomRangeParser.parseDicomDateRange(
                "20200101-").get();
        assertEquals(LocalDate.of(2020, 1, 1), r.start);
        assertNull(r.end);
    }

    @Test
    public void openLowerDateRange() {
        DicomDateRange r = DicomRangeParser.parseDicomDateRange(
                "-20201231").get();
        assertNull(r.start);
        assertEquals(LocalDate.of(2020, 12, 31), r.end);
    }

    @Test
    public void universalDateRange() {
        DicomDateRange r = DicomRangeParser.parseDicomDateRange("-").get();
        assertNull(r.start);
        assertNull(r.end);
        assertTrue(r.isUniversal());
    }

    @Test
    public void singleDayDateRange() {
        DicomDateRange r = DicomRangeParser.parseDicomDateRange(
                "20200101-20200101").get();
        assertEquals(LocalDate.of(2020, 1, 1), r.start);
        assertEquals(LocalDate.of(2020, 1, 1), r.end);
    }

    // ---- Date parsing: malformed → 400 ----

    @Test(expected = BadRequestException.class)
    public void wildcardStarInDateRangeRejected() {
        DicomRangeParser.parseDicomDateRange("2020*-20201231");
    }

    @Test(expected = BadRequestException.class)
    public void wildcardQuestionInDateRangeRejected() {
        DicomRangeParser.parseDicomDateRange("20200101-2020????");
    }

    @Test(expected = BadRequestException.class)
    public void nonEightDigitStartDateRejected() {
        DicomRangeParser.parseDicomDateRange("202001-20201231");
    }

    @Test(expected = BadRequestException.class)
    public void nonEightDigitEndDateRejected() {
        DicomRangeParser.parseDicomDateRange("20200101-2020");
    }

    @Test(expected = BadRequestException.class)
    public void tooManyHyphensDateRejected() {
        DicomRangeParser.parseDicomDateRange(
                "20200101-20200601-20201231");
    }

    @Test(expected = BadRequestException.class)
    public void invalidCalendarStartDateRejected() {
        DicomRangeParser.parseDicomDateRange("20200230-20201231");
    }

    @Test(expected = BadRequestException.class)
    public void invalidCalendarEndDateRejected() {
        DicomRangeParser.parseDicomDateRange("20200101-20201332");
    }

    @Test(expected = BadRequestException.class)
    public void nonNumericDateEndpointRejected() {
        DicomRangeParser.parseDicomDateRange("abcdefgh-20201231");
    }

    // ---- Time parsing: non-range values fall through ----

    @Test
    public void nullTimeReturnsEmpty() {
        assertFalse(DicomRangeParser.parseDicomTimeRange(null).isPresent());
    }

    @Test
    public void emptyTimeReturnsEmpty() {
        assertFalse(DicomRangeParser.parseDicomTimeRange("").isPresent());
    }

    @Test
    public void exactTimeValueReturnsEmpty() {
        assertFalse(DicomRangeParser.parseDicomTimeRange("120000").isPresent());
    }

    @Test
    public void wildcardTimeValueReturnsEmpty() {
        assertFalse(DicomRangeParser.parseDicomTimeRange("12*").isPresent());
    }

    // ---- Time parsing: valid ranges ----

    @Test
    public void closedTimeRange() {
        DicomTimeRange r = DicomRangeParser.parseDicomTimeRange(
                "080000-180000").get();
        assertEquals(LocalTime.of(8, 0, 0), r.start);
        assertEquals(LocalTime.of(18, 0, 0), r.end);
    }

    @Test
    public void openUpperTimeRange() {
        DicomTimeRange r = DicomRangeParser.parseDicomTimeRange(
                "080000-").get();
        assertEquals(LocalTime.of(8, 0, 0), r.start);
        assertNull(r.end);
    }

    @Test
    public void openLowerTimeRange() {
        DicomTimeRange r = DicomRangeParser.parseDicomTimeRange(
                "-180000").get();
        assertNull(r.start);
        assertEquals(LocalTime.of(18, 0, 0), r.end);
    }

    @Test
    public void universalTimeRange() {
        DicomTimeRange r = DicomRangeParser.parseDicomTimeRange("-").get();
        assertTrue(r.isUniversal());
    }

    // ---- Time parsing: malformed → 400 ----

    @Test(expected = BadRequestException.class)
    public void nonSixDigitTimeStartRejected() {
        DicomRangeParser.parseDicomTimeRange("0800-180000");
    }

    @Test(expected = BadRequestException.class)
    public void nonSixDigitTimeEndRejected() {
        DicomRangeParser.parseDicomTimeRange("080000-18");
    }

    @Test(expected = BadRequestException.class)
    public void invalidHourInTimeRejected() {
        DicomRangeParser.parseDicomTimeRange("250000-180000");
    }

    @Test(expected = BadRequestException.class)
    public void invalidMinuteInTimeRejected() {
        DicomRangeParser.parseDicomTimeRange("086000-180000");
    }

    @Test(expected = BadRequestException.class)
    public void wildcardInTimeRangeRejected() {
        DicomRangeParser.parseDicomTimeRange("08????-180000");
    }

    @Test(expected = BadRequestException.class)
    public void fractionalSecondsInTimeRangeRejected() {
        // Not in scope for this fix — matches series/instance-level
        // exact-form expectations at the study level.
        DicomRangeParser.parseDicomTimeRange("080000.500000-180000");
    }

    // ---- Combined DA + TM into DT range ----
    // Per PS3.18 §8.3.4.1.1 → PS3.4 §C.2.2.2.5.4. The narrow
    // "same form" interpretation combines only when both ranges
    // share bound structure (both closed, both open-upper, both
    // open-lower). Mixed forms fall back to independent handling.

    @Test
    public void combineWithNoDateInputReturnsEmpty() {
        assertFalse(DicomRangeParser.combineIntoDateTimeRange(
                Optional.empty(),
                Optional.of(new DicomTimeRange(LocalTime.of(10, 0), LocalTime.of(11, 0)))
        ).isPresent());
    }

    @Test
    public void combineWithNoTimeInputReturnsEmpty() {
        assertFalse(DicomRangeParser.combineIntoDateTimeRange(
                Optional.of(new DicomDateRange(LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 3))),
                Optional.empty()
        ).isPresent());
    }

    @Test
    public void combineWithNoInputsReturnsEmpty() {
        assertFalse(DicomRangeParser.combineIntoDateTimeRange(
                Optional.empty(), Optional.empty()
        ).isPresent());
    }

    @Test
    public void combineBothClosed_reproducesPs3_4NoteExample() {
        // PS3.4 §C.2.2.2.5.4 Note:
        //   StudyDate = 20060705-20060707, StudyTime = 1000-1800
        //   → matches July 5 10am until July 7 6pm as a single span.
        DicomDateRange dr = new DicomDateRange(
                LocalDate.of(2006, 7, 5), LocalDate.of(2006, 7, 7));
        DicomTimeRange tr = new DicomTimeRange(
                LocalTime.of(10, 0), LocalTime.of(18, 0));
        DicomDateTimeRange r = DicomRangeParser
                .combineIntoDateTimeRange(Optional.of(dr), Optional.of(tr))
                .get();
        assertEquals(LocalDateTime.of(2006, 7, 5, 10, 0), r.start);
        assertEquals(LocalDateTime.of(2006, 7, 7, 18, 0), r.end);
        assertFalse(r.isUniversal());
    }

    @Test
    public void combineBothOpenUpper() {
        DicomDateRange dr = new DicomDateRange(
                LocalDate.of(2020, 1, 1), null);
        DicomTimeRange tr = new DicomTimeRange(
                LocalTime.of(10, 0), null);
        DicomDateTimeRange r = DicomRangeParser
                .combineIntoDateTimeRange(Optional.of(dr), Optional.of(tr))
                .get();
        assertEquals(LocalDateTime.of(2020, 1, 1, 10, 0), r.start);
        assertNull(r.end);
    }

    @Test
    public void combineBothOpenLower() {
        DicomDateRange dr = new DicomDateRange(
                null, LocalDate.of(2020, 12, 31));
        DicomTimeRange tr = new DicomTimeRange(
                null, LocalTime.of(23, 59, 59));
        DicomDateTimeRange r = DicomRangeParser
                .combineIntoDateTimeRange(Optional.of(dr), Optional.of(tr))
                .get();
        assertNull(r.start);
        assertEquals(LocalDateTime.of(2020, 12, 31, 23, 59, 59), r.end);
    }

    @Test
    public void combineBothUniversalIsUniversal() {
        DicomDateRange dr = new DicomDateRange(null, null);
        DicomTimeRange tr = new DicomTimeRange(null, null);
        DicomDateTimeRange r = DicomRangeParser
                .combineIntoDateTimeRange(Optional.of(dr), Optional.of(tr))
                .get();
        assertNull(r.start);
        assertNull(r.end);
        assertTrue(r.isUniversal());
    }

    // ---- Mixed forms: narrow interpretation returns empty ----

    @Test
    public void combineClosedDateWithOpenUpperTimeReturnsEmpty() {
        DicomDateRange dr = new DicomDateRange(
                LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 3));
        DicomTimeRange tr = new DicomTimeRange(
                LocalTime.of(10, 0), null);
        assertFalse(DicomRangeParser.combineIntoDateTimeRange(
                Optional.of(dr), Optional.of(tr)).isPresent());
    }

    @Test
    public void combineOpenUpperDateWithClosedTimeReturnsEmpty() {
        DicomDateRange dr = new DicomDateRange(
                LocalDate.of(2020, 1, 1), null);
        DicomTimeRange tr = new DicomTimeRange(
                LocalTime.of(10, 0), LocalTime.of(11, 0));
        assertFalse(DicomRangeParser.combineIntoDateTimeRange(
                Optional.of(dr), Optional.of(tr)).isPresent());
    }

    @Test
    public void combineUniversalDateWithClosedTimeReturnsEmpty() {
        DicomDateRange dr = new DicomDateRange(null, null);
        DicomTimeRange tr = new DicomTimeRange(
                LocalTime.of(10, 0), LocalTime.of(11, 0));
        assertFalse(DicomRangeParser.combineIntoDateTimeRange(
                Optional.of(dr), Optional.of(tr)).isPresent());
    }

    @Test
    public void combineClosedDateWithOpenLowerTimeReturnsEmpty() {
        DicomDateRange dr = new DicomDateRange(
                LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 3));
        DicomTimeRange tr = new DicomTimeRange(
                null, LocalTime.of(11, 0));
        assertFalse(DicomRangeParser.combineIntoDateTimeRange(
                Optional.of(dr), Optional.of(tr)).isPresent());
    }
}
