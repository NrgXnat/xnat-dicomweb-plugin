/*
 * XNAT DICOMweb Plugin
 * Copyright (c) 2026 XNATWorks.
 * All rights reserved.
 */

package org.nrg.xnat.dicomweb.util;

import org.junit.Test;
import org.nrg.xnat.dicomweb.exceptions.BadRequestException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link DicomQueryValueValidator} and the DA/TM value
 * grammar in {@link DicomDateTimeValues}.
 *
 * <p>These cover the QIDO-RS regression in which a malformed date
 * parameter produced an HTTP 200 with an empty result set instead of
 * the HTTP 400 required by PS3.18 &sect;10.6.3.1.
 */
public class DicomQueryValueValidatorTest {

    // ---- Dates: valid values are accepted as filters ----

    @Test
    public void exactDateAccepted() {
        assertTrue(DicomQueryValueValidator.validateDate("StudyDate", "20250115"));
    }

    @Test
    public void dateRangesAccepted() {
        assertTrue(DicomQueryValueValidator.validateDate("StudyDate", "20250101-20250131"));
        assertTrue(DicomQueryValueValidator.validateDate("StudyDate", "20250101-"));
        assertTrue(DicomQueryValueValidator.validateDate("StudyDate", "-20250131"));
    }

    @Test
    public void trailingSpacePaddingAccepted() {
        // PS3.5 §6.2 DA: "a trailing SPACE character is allowed for padding".
        assertTrue(DicomQueryValueValidator.validateDate("StudyDate", "20250115 "));
        assertTrue(DicomQueryValueValidator.validateTime("StudyTime", "103000 "));
    }

    // ---- Trailing SPACE padding, including on range forms ----
    // DICOM pads a value to an even byte count, so the range forms
    // that actually arrive padded are the odd-length ones: "20250101-"
    // is 9 characters and "-" is 1. Padding must therefore be stripped
    // from the whole value before it is split on the hyphen, or the
    // pad lands where the empty endpoint belongs.

    @Test
    public void paddedOpenUpperRangeAccepted() {
        assertTrue(DicomQueryValueValidator.validateDate("StudyDate", "20250101- "));
        assertTrue(DicomQueryValueValidator.validateTime("StudyTime", "080000- "));
    }

    @Test
    public void paddedOpenLowerRangeAccepted() {
        assertTrue(DicomQueryValueValidator.validateDate("StudyDate", "-20250131 "));
    }

    @Test
    public void paddedClosedRangeAccepted() {
        // 17 characters plus a pad, the 18-byte maximum PS3.5 cites for
        // a DA in a Query with Range Matching.
        assertTrue(DicomQueryValueValidator.validateDate(
                "StudyDate", "20250101-20250131 "));
    }

    @Test
    public void paddedUniversalRangeMarkerAccepted() {
        assertTrue(DicomQueryValueValidator.validateDate("StudyDate", "- "));
        assertTrue(DicomQueryValueValidator.validateTime("StudyTime", "- "));
    }

    @Test
    public void paddedOpenUpperRangeParsesAsOpenEnded() {
        // Not merely accepted — the pad must not become an endpoint.
        DicomRangeParser.DicomDateRange r =
                DicomRangeParser.parseDicomDateRange("20250101- ").get();
        assertEquals(LocalDate.of(2025, 1, 1), r.start);
        assertNull(r.end);
    }

    @Test
    public void paddedUniversalMarkerParsesAsUniversal() {
        assertTrue(DicomRangeParser.parseDicomDateRange("- ").get().isUniversal());
    }

    // ---- A value that is only padding is Universal Matching ----
    // PS3.4 §C.2.2.2.3: "If the value specified for a Key Attribute in
    // a request is zero length, then all entities shall match this
    // Attribute." Stripping the pad leaves zero length, so
    // ?StudyDate=%20 drops the filter instead of returning 400.

    @Test
    public void paddingOnlyValueIsUniversalNotAnError() {
        assertFalse(DicomQueryValueValidator.validateDate("StudyDate", " "));
        assertFalse(DicomQueryValueValidator.validateTime("StudyTime", " "));
    }

    @Test
    public void multipleSpacesAreAlsoUniversal() {
        assertFalse(DicomQueryValueValidator.validateDate("StudyDate", "   "));
    }

    // ---- Only the tail is padding ----
    // PS3.5 §6.2 TM: "Leading and embedded spaces are not allowed",
    // and the DA repertoire admits no interior space either.

    @Test(expected = BadRequestException.class)
    public void leadingSpaceRejected() {
        DicomQueryValueValidator.validateDate("StudyDate", " 20250115");
    }

    @Test(expected = BadRequestException.class)
    public void embeddedSpaceRejected() {
        DicomQueryValueValidator.validateDate("StudyDate", "2025 0115");
    }

    @Test(expected = BadRequestException.class)
    public void spacesAroundRangeSeparatorRejected() {
        // PS3.4 writes the form as "<date1> - <date2>", but those
        // spaces are typographic: neither the DA nor the TM character
        // repertoire permits an embedded space.
        DicomQueryValueValidator.validateDate("StudyDate", "20250101 - 20250131");
    }

    @Test(expected = BadRequestException.class)
    public void paddingDoesNotRescueAnOtherwiseMalformedValue() {
        DicomQueryValueValidator.validateDate("StudyDate", "20251345 ");
    }

    @Test
    public void leapDayAccepted() {
        assertTrue(DicomQueryValueValidator.validateDate("StudyDate", "20240229"));
    }

    // ---- Dates: universal matching drops the filter ----

    @Test
    public void emptyDateIsUniversal() {
        assertFalse(DicomQueryValueValidator.validateDate("StudyDate", ""));
        assertFalse(DicomQueryValueValidator.validateDate("StudyDate", null));
    }

    @Test
    public void bareAsteriskIsUniversal() {
        // PS3.4 §C.2.2.2.4 Note: "Wild Card Matching on a value of '*'
        // is equivalent to Universal Matching."
        assertFalse(DicomQueryValueValidator.validateDate("StudyDate", "*"));
        assertFalse(DicomQueryValueValidator.validateTime("StudyTime", "*"));
    }

    // ---- Dates: malformed values are rejected ----

    @Test(expected = BadRequestException.class)
    public void nonNumericDateRejected() {
        DicomQueryValueValidator.validateDate("StudyDate", "yesterday");
    }

    @Test(expected = BadRequestException.class)
    public void impossibleCalendarDateRejected() {
        // The originally reported case: 8 digits, so it bypassed range
        // parsing, was sliced into "2025-13-45", and blew up in the
        // database with the exception swallowed.
        DicomQueryValueValidator.validateDate("StudyDate", "20251345");
    }

    @Test(expected = BadRequestException.class)
    public void nonLeapYearFeb29Rejected() {
        DicomQueryValueValidator.validateDate("StudyDate", "20250229");
    }

    @Test(expected = BadRequestException.class)
    public void isoFormattedDateRejected() {
        // Splits on "-" into three parts, so it fails as a range too.
        DicomQueryValueValidator.validateDate("StudyDate", "2025-01-15");
    }

    @Test(expected = BadRequestException.class)
    public void shortDateRejected() {
        DicomQueryValueValidator.validateDate("StudyDate", "202501");
    }

    @Test(expected = BadRequestException.class)
    public void malformedRangeEndpointRejected() {
        DicomQueryValueValidator.validateDate("StudyDate", "20250101-garbage");
    }

    @Test(expected = BadRequestException.class)
    public void threePartRangeRejected() {
        DicomQueryValueValidator.validateDate("StudyDate", "20250101-20250201-20250301");
    }

    // ---- Inverted ranges ----
    // PS3.4 §C.2.2.2.5.1 / §C.2.2.2.5.2 define the two-endpoint form
    // only "where <date1> is less or equal to <date2>" (respectively
    // <time1> / <time2>).

    @Test(expected = BadRequestException.class)
    public void invertedDateRangeRejected() {
        DicomQueryValueValidator.validateDate("StudyDate", "20250131-20250101");
    }

    @Test(expected = BadRequestException.class)
    public void invertedTimeRangeRejected() {
        DicomQueryValueValidator.validateTime("StudyTime", "180000-080000");
    }

    @Test
    public void singleDayRangeAccepted() {
        assertTrue(DicomQueryValueValidator.validateDate("StudyDate",
                "20250101-20250101"));
    }

    @Test
    public void invertedRangeReportsBadRequestNamingTheParameter() {
        try {
            DicomQueryValueValidator.validateDate("StudyDate", "20250131-20250101");
            throw new AssertionError("expected BadRequestException");
        } catch (BadRequestException e) {
            assertEquals(400, e.getHttpStatus());
            assertTrue("message should name the parameter: " + e.getMessage(),
                    e.getMessage().contains("StudyDate"));
        }
    }

    // ---- Wildcards are not defined for DA/TM ----
    // PS3.4 §C.2.2.2.4 scopes Wild Card Matching to
    // "AE, CS, LO, LT, PN, SH, ST, UC, UR, UT".

    @Test(expected = BadRequestException.class)
    public void partialDateWildcardRejected() {
        DicomQueryValueValidator.validateDate("StudyDate", "2025*");
    }

    @Test(expected = BadRequestException.class)
    public void dateQuestionMarkWildcardRejected() {
        DicomQueryValueValidator.validateDate("StudyDate", "202501??");
    }

    @Test(expected = BadRequestException.class)
    public void timeWildcardRejected() {
        DicomQueryValueValidator.validateTime("StudyTime", "12*");
    }

    // ---- Times: the TM grammar of PS3.5 §6.2 ----

    @Test
    public void fullPrecisionTimeAccepted() {
        assertTrue(DicomQueryValueValidator.validateTime("StudyTime", "103000"));
    }

    @Test
    public void partialPrecisionTimesAccepted() {
        assertTrue(DicomQueryValueValidator.validateTime("StudyTime", "10"));
        assertTrue(DicomQueryValueValidator.validateTime("StudyTime", "1030"));
    }

    @Test
    public void fractionalSecondAccepted() {
        assertTrue(DicomQueryValueValidator.validateTime("StudyTime", "103000.500000"));
    }

    @Test(expected = BadRequestException.class)
    public void oddDigitCountTimeRejected() {
        // PS3.5 §6.2 cites "021 " as an invalid TM value.
        DicomQueryValueValidator.validateTime("StudyTime", "021");
    }

    @Test(expected = BadRequestException.class)
    public void hourOutOfRangeRejected() {
        DicomQueryValueValidator.validateTime("StudyTime", "250000");
    }

    @Test(expected = BadRequestException.class)
    public void minuteOutOfRangeRejected() {
        DicomQueryValueValidator.validateTime("StudyTime", "106000");
    }

    @Test(expected = BadRequestException.class)
    public void nonNumericTimeRejected() {
        DicomQueryValueValidator.validateTime("StudyTime", "noon");
    }

    @Test(expected = BadRequestException.class)
    public void colonSeparatedTimeRejected() {
        // The ACR-NEMA HH:MM:SS form PS3.5 calls non-compliant.
        DicomQueryValueValidator.validateTime("StudyTime", "10:30:00");
    }

    // ---- Value parsing detail ----

    @Test
    public void unspecifiedTimeComponentsResolveToZero() {
        assertEquals(LocalTime.of(10, 0, 0),
                DicomDateTimeValues.parseTime("StudyTime", "10"));
        assertEquals(LocalTime.of(10, 30, 0),
                DicomDateTimeValues.parseTime("StudyTime", "1030"));
    }

    // ---- Leap seconds (SS=60) ----
    // PS3.5 §6.2: SS range "00" - "60", and "The SS component may have
    // a Value of 60 only for a leap second." Per IERS Bulletin C a leap
    // second always falls at 23:59:60 UTC, so the minute is always 59
    // while the hour varies with the local UTC offset.

    @Test
    public void leapSecondAcceptedInMinute59AtAnyHour() {
        assertTrue(DicomQueryValueValidator.validateTime("StudyTime", "235960"));
        assertTrue(DicomQueryValueValidator.validateTime("StudyTime", "115960"));
        assertTrue(DicomQueryValueValidator.validateTime("StudyTime", "005960"));
    }

    @Test
    public void leapSecondClampsToLastMicrosecondOfMinute() {
        // Microsecond, not nanosecond: a 9-digit fraction is rounded up
        // by Postgres and carries into the following minute, which is
        // the defect this guards against.
        assertEquals(LocalTime.of(23, 59, 59, 999_999_000),
                DicomDateTimeValues.parseTime("StudyTime", "235960"));
    }

    @Test
    public void leapSecondClampIsAWholeNumberOfMicroseconds() {
        // The clamp must stay renderable in six fractional digits.
        assertEquals(0,
                DicomDateTimeValues.parseTime("StudyTime", "235960").getNano() % 1_000);
    }

    @Test(expected = BadRequestException.class)
    public void secondSixtyRejectedInMinuteThirty() {
        // The value from the QA report — never a leap second.
        DicomQueryValueValidator.validateTime("StudyTime", "103060");
    }

    @Test(expected = BadRequestException.class)
    public void secondSixtyRejectedInMinuteZero() {
        DicomQueryValueValidator.validateTime("StudyTime", "100060");
    }

    @Test(expected = BadRequestException.class)
    public void secondSixtyRejectedInMinuteTwentyNine() {
        // A fractional-hour offset (+05:30) would render the leap
        // second here. Rejecting it is a recorded decision, not an
        // oversight — see CONFORMANCE §0.12. Do not relax this without
        // revisiting that note.
        DicomQueryValueValidator.validateTime("StudyTime", "102960");
    }

    @Test
    public void secondSixtyRejectionExplainsTheLeapSecondRule() {
        // "seconds 00-60" alone would read as a contradiction to a
        // client that just sent second 60, so the message must say why.
        try {
            DicomQueryValueValidator.validateTime("StudyTime", "103060");
            throw new AssertionError("expected BadRequestException");
        } catch (BadRequestException e) {
            assertEquals(400, e.getHttpStatus());
            assertTrue("message should mention leap seconds: " + e.getMessage(),
                    e.getMessage().toLowerCase().contains("leap second"));
            assertTrue("message should name the parameter: " + e.getMessage(),
                    e.getMessage().contains("StudyTime"));
        }
    }

    @Test
    public void leapSecondWithFractionAccepted() {
        // PS3.5 permits FFFFFF alongside SS; the fraction is subsumed
        // by the clamp, so it maps to the same bound.
        assertTrue(DicomQueryValueValidator.validateTime("StudyTime", "235960.5"));
        assertEquals(DicomDateTimeValues.parseTime("StudyTime", "235960"),
                DicomDateTimeValues.parseTime("StudyTime", "235960.5"));
    }

    @Test
    public void secondSixtyOneStillRejected() {
        try {
            DicomQueryValueValidator.validateTime("StudyTime", "235961");
            throw new AssertionError("expected BadRequestException");
        } catch (BadRequestException e) {
            assertEquals(400, e.getHttpStatus());
        }
    }

    @Test
    public void partialPrecisionMinute59IsUnaffected() {
        // 2359 is minute 59 with no seconds; it must not be dragged
        // into the leap-second path.
        assertEquals(LocalTime.of(23, 59, 0),
                DicomDateTimeValues.parseTime("StudyTime", "2359"));
    }

    // ---- SQL rendering ----

    @Test
    public void sqlTimeAlwaysRendersSixFractionalDigits() {
        assertEquals("23:59:59.999999", DicomDateTimeValues.toSqlTime(
                DicomDateTimeValues.parseTime("StudyTime", "235960")));
        assertEquals("10:30:00.000000", DicomDateTimeValues.toSqlTime(
                DicomDateTimeValues.parseTime("StudyTime", "103000")));
        assertEquals("10:30:00.500000", DicomDateTimeValues.toSqlTime(
                DicomDateTimeValues.parseTime("StudyTime", "103000.5")));
    }

    @Test
    public void sqlTimestampAlwaysRendersSixFractionalDigits() {
        assertEquals("2026-06-30T23:59:59.999999",
                DicomDateTimeValues.toSqlTimestamp(LocalDateTime.of(
                        LocalDate.of(2026, 6, 30),
                        DicomDateTimeValues.parseTime("StudyTime", "235960"))));
    }

    @Test
    public void fractionIsRightPaddedToNanos() {
        assertEquals(LocalTime.of(10, 30, 0, 500_000_000),
                DicomDateTimeValues.parseTime("StudyTime", "103000.5"));
    }

    @Test
    public void dateParsesToExpectedValue() {
        assertEquals(LocalDate.of(2025, 1, 15),
                DicomDateTimeValues.parseDate("StudyDate", "20250115"));
    }

    @Test
    public void timeMatchPrefixReflectsSuppliedComponents() {
        assertEquals("10", DicomDateTimeValues.timeMatchPrefix("10"));
        assertEquals("1030", DicomDateTimeValues.timeMatchPrefix("1030"));
        assertEquals("103000", DicomDateTimeValues.timeMatchPrefix("103000"));
        assertEquals("103000", DicomDateTimeValues.timeMatchPrefix("103000.5"));
        assertEquals("103000", DicomDateTimeValues.timeMatchPrefix("103000 "));
    }

    // ---- The error carries a 400 and names the parameter ----

    @Test
    public void rejectionReportsBadRequestNamingTheParameter() {
        try {
            DicomQueryValueValidator.validateDate("StudyDate", "20251345");
            throw new AssertionError("expected BadRequestException");
        } catch (BadRequestException e) {
            assertEquals(400, e.getHttpStatus());
            assertTrue("message should name the parameter: " + e.getMessage(),
                    e.getMessage().contains("StudyDate"));
        }
    }

    @Test
    public void rejectionUsesTheClientsSpellingOfTheParameter() {
        // Clients may send the 8-hex-digit tag form instead of the
        // keyword; the error should echo what they sent.
        try {
            DicomQueryValueValidator.validateDate("00080020", "20251345");
            throw new AssertionError("expected BadRequestException");
        } catch (BadRequestException e) {
            assertTrue("message should name the parameter: " + e.getMessage(),
                    e.getMessage().contains("00080020"));
        }
    }

    @Test
    public void rangeRejectionAlsoUsesTheClientsSpelling() {
        try {
            DicomQueryValueValidator.validateDate("00080020", "20250101-nonsense");
            throw new AssertionError("expected BadRequestException");
        } catch (BadRequestException e) {
            assertTrue("message should name the parameter: " + e.getMessage(),
                    e.getMessage().contains("00080020"));
        }
    }
}
