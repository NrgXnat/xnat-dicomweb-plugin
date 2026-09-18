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
import static org.junit.Assert.fail;

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

    // ---- Inverted ranges → 400 ----
    // PS3.4 §C.2.2.2.5.1 defines the two-endpoint form only "where
    // <date1> is less or equal to <date2>". An inverted range can
    // never match anything, so it is reported as a bad request rather
    // than run as a query guaranteed to return nothing.

    @Test(expected = BadRequestException.class)
    public void invertedDateRangeRejected() {
        DicomRangeParser.parseDicomDateRange("20201231-20200101");
    }

    @Test
    public void equalEndpointDateRangeAccepted() {
        // "less or equal" — a single-day range is still well formed.
        DicomDateRange r = DicomRangeParser.parseDicomDateRange(
                "20200101-20200101").get();
        assertEquals(LocalDate.of(2020, 1, 1), r.start);
        assertEquals(LocalDate.of(2020, 1, 1), r.end);
    }

    @Test
    public void openEndedRangesAreNeverInverted() {
        // Only a closed range has two bounds to compare; the
        // open-ended forms must not trip the ordering check.
        assertTrue(DicomRangeParser.parseDicomDateRange("20201231-").isPresent());
        assertTrue(DicomRangeParser.parseDicomDateRange("-20200101").isPresent());
        assertTrue(DicomRangeParser.parseDicomDateRange("-").isPresent());
    }

    @Test
    public void invertedDateRangeMessageNamesBothBounds() {
        try {
            DicomRangeParser.parseDicomDateRange("20201231-20200101", "StudyDate");
            fail("expected BadRequestException");
        } catch (BadRequestException e) {
            assertEquals(400, e.getHttpStatus());
            assertTrue("should name the parameter: " + e.getMessage(),
                    e.getMessage().contains("StudyDate"));
            assertTrue("should quote the start bound: " + e.getMessage(),
                    e.getMessage().contains("20201231"));
            assertTrue("should quote the end bound: " + e.getMessage(),
                    e.getMessage().contains("20200101"));
        }
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

    // PS3.5 §6.2 TM: "One or more of the components MM, SS, or FFFFFF
    // may be unspecified as long as every component to the right of an
    // unspecified component is also unspecified". Partial-precision
    // endpoints are therefore valid, and unspecified components
    // resolve to zero.

    @Test
    public void partialPrecisionHourMinuteEndpointsAccepted() {
        DicomTimeRange r = DicomRangeParser.parseDicomTimeRange(
                "0800-180000").get();
        assertEquals(LocalTime.of(8, 0, 0), r.start);
        assertEquals(LocalTime.of(18, 0, 0), r.end);
    }

    @Test
    public void partialPrecisionHourOnlyEndpointAccepted() {
        DicomTimeRange r = DicomRangeParser.parseDicomTimeRange(
                "080000-18").get();
        assertEquals(LocalTime.of(8, 0, 0), r.start);
        assertEquals(LocalTime.of(18, 0, 0), r.end);
    }

    @Test
    public void ps3_4NoteExampleTimeRangeParses() {
        // The Study Time from the PS3.4 §C.2.2.2.5.4 worked example.
        DicomTimeRange r = DicomRangeParser.parseDicomTimeRange(
                "1000-1800").get();
        assertEquals(LocalTime.of(10, 0), r.start);
        assertEquals(LocalTime.of(18, 0), r.end);
    }

    @Test(expected = BadRequestException.class)
    public void oddDigitCountTimeRejected() {
        // PS3.5 §6.2 cites "021 " as an invalid TM value.
        DicomRangeParser.parseDicomTimeRange("021-180000");
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

    @Test
    public void fractionalSecondsInTimeRangeAccepted() {
        // PS3.5 §6.2 TM: "The FFFFFF component, if present, shall
        // contain 1 to 6 digits."
        DicomTimeRange r = DicomRangeParser.parseDicomTimeRange(
                "080000.5-180000").get();
        assertEquals(LocalTime.of(8, 0, 0, 500_000_000), r.start);
        assertEquals(LocalTime.of(18, 0, 0), r.end);
    }

    @Test(expected = BadRequestException.class)
    public void fractionWithoutSecondsRejected() {
        DicomRangeParser.parseDicomTimeRange("0800.5-180000");
    }

    @Test(expected = BadRequestException.class)
    public void overlongFractionRejected() {
        DicomRangeParser.parseDicomTimeRange("080000.1234567-180000");
    }

    @Test
    public void leapSecondEndpointAccepted() {
        // PS3.5 §6.2 TM: SS "range '00' - '60'"; the SS component
        // "may have a Value of 60 only for a leap second". Clamped to
        // microsecond precision, not nanosecond — a 9-digit fraction
        // gets rounded up by Postgres into the following minute.
        DicomTimeRange r = DicomRangeParser.parseDicomTimeRange(
                "080000-235960").get();
        assertEquals(LocalTime.of(23, 59, 59, 999_999_000), r.end);
    }

    @Test(expected = BadRequestException.class)
    public void leapSecondOutsideMinute59RejectedAsRangeEndpoint() {
        // The value from the QA report: 10:30:60 is not a leap second
        // under any timezone offset.
        DicomRangeParser.parseDicomTimeRange("103000-103060");
    }

    @Test
    public void leapSecondRangeEndpointsStayOrdered() {
        // The clamp must not perturb the inverted-range check: second
        // 59 clamps below the leap second, so this stays a valid range.
        DicomTimeRange r = DicomRangeParser.parseDicomTimeRange(
                "235959-235960").get();
        assertEquals(LocalTime.of(23, 59, 59), r.start);
        assertEquals(LocalTime.of(23, 59, 59, 999_999_000), r.end);
        assertTrue(r.start.isBefore(r.end));
    }

    @Test
    public void leapSecondOnBothEndsIsNotInverted() {
        DicomTimeRange r = DicomRangeParser.parseDicomTimeRange(
                "235960-235960").get();
        assertEquals(r.start, r.end);
    }

    @Test(expected = BadRequestException.class)
    public void invertedRangeAgainstLeapSecondStillRejected() {
        DicomRangeParser.parseDicomTimeRange("120000-115960");
    }

    // ---- Inverted time ranges → 400 ----
    // PS3.4 §C.2.2.2.5.2 defines the two-endpoint form only "where
    // <time1> is less or equal to <time2>", and §C.2.2.2.5.2 also
    // states that "Range Matching crossing midnight is not
    // supported" — so an inverted time range is not a shorthand for
    // an overnight window.

    @Test(expected = BadRequestException.class)
    public void invertedTimeRangeRejected() {
        DicomRangeParser.parseDicomTimeRange("180000-080000");
    }

    @Test(expected = BadRequestException.class)
    public void invertedPartialPrecisionTimeRangeRejected() {
        DicomRangeParser.parseDicomTimeRange("18-08");
    }

    @Test(expected = BadRequestException.class)
    public void invertedByFractionalSecondRejected() {
        // Differ only in the fractional component.
        DicomRangeParser.parseDicomTimeRange("080000.6-080000.5");
    }

    @Test
    public void equalEndpointTimeRangeAccepted() {
        DicomTimeRange r = DicomRangeParser.parseDicomTimeRange(
                "080000-080000").get();
        assertEquals(LocalTime.of(8, 0, 0), r.start);
        assertEquals(LocalTime.of(8, 0, 0), r.end);
    }

    @Test
    public void invertedTimeRangeMessageNamesBothBounds() {
        try {
            DicomRangeParser.parseDicomTimeRange("180000-080000", "StudyTime");
            fail("expected BadRequestException");
        } catch (BadRequestException e) {
            assertEquals(400, e.getHttpStatus());
            assertTrue("should name the parameter: " + e.getMessage(),
                    e.getMessage().contains("StudyTime"));
            assertTrue("should quote the start bound: " + e.getMessage(),
                    e.getMessage().contains("180000"));
            assertTrue("should quote the end bound: " + e.getMessage(),
                    e.getMessage().contains("080000"));
        }
    }

    @Test
    public void combinedRangeIsOrderedWhenComponentsAreOrdered() {
        // Documents why the combined DT range needs no ordering check
        // of its own: if the date bounds differ, the date decides the
        // ordering regardless of the times; if they are equal, the
        // already-validated time bounds decide it. So an ordered DA
        // range plus an ordered TM range always yields an ordered DT
        // range. Checked for both a multi-day and a same-day span.
        DicomDateTimeRange multiDay = DicomRangeParser.combineIntoDateTimeRange(
                DicomRangeParser.parseDicomDateRange("20200101-20200102"),
                DicomRangeParser.parseDicomTimeRange("080000-180000")
        ).get();
        assertTrue(multiDay.start.isBefore(multiDay.end));

        DicomDateTimeRange sameDay = DicomRangeParser.combineIntoDateTimeRange(
                DicomRangeParser.parseDicomDateRange("20200101-20200101"),
                DicomRangeParser.parseDicomTimeRange("080000-180000")
        ).get();
        assertTrue(sameDay.start.isBefore(sameDay.end));
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
