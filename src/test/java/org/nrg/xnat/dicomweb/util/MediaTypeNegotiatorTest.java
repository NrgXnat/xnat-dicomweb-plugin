package org.nrg.xnat.dicomweb.util;

import org.junit.Test;
import org.nrg.xnat.dicomweb.exceptions.NotAcceptableException;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests for MediaTypeNegotiator — Accept header parsing and content negotiation.
 */
public class MediaTypeNegotiatorTest {

    // ========== Parsing ==========

    @Test
    public void parse_NullHeader_ReturnsEmptyList() {
        assertTrue(MediaTypeNegotiator.parse(null).isEmpty());
    }

    @Test
    public void parse_EmptyHeader_ReturnsEmptyList() {
        assertTrue(MediaTypeNegotiator.parse("").isEmpty());
        assertTrue(MediaTypeNegotiator.parse("   ").isEmpty());
    }

    @Test
    public void parse_SingleType_ParsesCorrectly() {
        List<MediaTypeNegotiator.ParsedMediaType> result =
                MediaTypeNegotiator.parse("application/dicom+json");
        assertEquals(1, result.size());
        assertEquals("application", result.get(0).getType());
        assertEquals("dicom+json", result.get(0).getSubtype());
        assertEquals("application/dicom+json", result.get(0).getFullType());
        assertEquals(1.0, result.get(0).getQuality(), 0.001);
    }

    @Test
    public void parse_MultipleTypes_SortedByQuality() {
        List<MediaTypeNegotiator.ParsedMediaType> result =
                MediaTypeNegotiator.parse("application/dicom+xml;q=0.5, application/dicom+json;q=1.0");
        assertEquals(2, result.size());
        assertEquals("application/dicom+json", result.get(0).getFullType());
        assertEquals("application/dicom+xml", result.get(1).getFullType());
    }

    @Test
    public void parse_DefaultQualityIsOne() {
        List<MediaTypeNegotiator.ParsedMediaType> result =
                MediaTypeNegotiator.parse("application/dicom+json, application/dicom+xml;q=0.9");
        assertEquals("application/dicom+json", result.get(0).getFullType());
        assertEquals(1.0, result.get(0).getQuality(), 0.001);
    }

    @Test
    public void parse_WildcardType() {
        List<MediaTypeNegotiator.ParsedMediaType> result =
                MediaTypeNegotiator.parse("*/*");
        assertEquals(1, result.size());
        assertTrue(result.get(0).isWildcard());
    }

    @Test
    public void parse_SubtypeWildcard() {
        List<MediaTypeNegotiator.ParsedMediaType> result =
                MediaTypeNegotiator.parse("image/*");
        assertEquals(1, result.size());
        assertFalse(result.get(0).isWildcard());
        assertTrue(result.get(0).isSubtypeWildcard());
    }

    @Test
    public void parse_WithParameters() {
        List<MediaTypeNegotiator.ParsedMediaType> result =
                MediaTypeNegotiator.parse("application/dicom;transfer-syntax=1.2.840.10008.1.2.1");
        assertEquals(1, result.size());
        assertEquals("1.2.840.10008.1.2.1", result.get(0).getParameter("transfer-syntax"));
    }

    @Test
    public void parse_WithQuotedParameters() {
        List<MediaTypeNegotiator.ParsedMediaType> result =
                MediaTypeNegotiator.parse("multipart/related;type=\"application/dicom\"");
        assertEquals(1, result.size());
        assertEquals("application/dicom", result.get(0).getParameter("type"));
    }

    // ========== Matching ==========

    @Test
    public void matches_ExactMatch() {
        MediaTypeNegotiator.ParsedMediaType mt =
                MediaTypeNegotiator.parse("application/dicom+json").get(0);
        assertTrue(mt.matches("application/dicom+json"));
        assertFalse(mt.matches("application/dicom+xml"));
    }

    @Test
    public void matches_Wildcard() {
        MediaTypeNegotiator.ParsedMediaType mt =
                MediaTypeNegotiator.parse("*/*").get(0);
        assertTrue(mt.matches("application/dicom+json"));
        assertTrue(mt.matches("image/jpeg"));
    }

    @Test
    public void matches_SubtypeWildcard() {
        MediaTypeNegotiator.ParsedMediaType mt =
                MediaTypeNegotiator.parse("image/*").get(0);
        assertTrue(mt.matches("image/jpeg"));
        assertTrue(mt.matches("image/png"));
        assertFalse(mt.matches("application/dicom"));
    }

    @Test
    public void matches_CaseInsensitive() {
        MediaTypeNegotiator.ParsedMediaType mt =
                MediaTypeNegotiator.parse("Application/DICOM+JSON").get(0);
        assertTrue(mt.matches("application/dicom+json"));
    }

    // ========== Negotiation ==========

    @Test
    public void negotiate_NoAcceptHeader_ReturnsDefault() {
        List<String> supported = Arrays.asList("application/dicom+json", "application/dicom+xml");
        String result = MediaTypeNegotiator.negotiate(null, null, supported, "application/dicom+json");
        assertEquals("application/dicom+json", result);
    }

    @Test
    public void negotiate_ExactMatch() {
        List<String> supported = Arrays.asList("application/dicom+json", "application/dicom+xml");
        String result = MediaTypeNegotiator.negotiate(
                "application/dicom+xml", null, supported, "application/dicom+json");
        assertEquals("application/dicom+xml", result);
    }

    @Test
    public void negotiate_QualityPreference() {
        List<String> supported = Arrays.asList("application/dicom+json", "application/dicom+xml");
        String result = MediaTypeNegotiator.negotiate(
                "application/dicom+json;q=0.5, application/dicom+xml;q=1.0",
                null, supported, "application/dicom+json");
        assertEquals("application/dicom+xml", result);
    }

    @Test
    public void negotiate_WildcardMatchesFirstSupported() {
        List<String> supported = Arrays.asList("application/dicom+json", "application/dicom+xml");
        String result = MediaTypeNegotiator.negotiate(
                "*/*", null, supported, "application/dicom+json");
        assertEquals("application/dicom+json", result);
    }

    @Test
    public void negotiate_SubtypeWildcardMatchesFirstOfType() {
        List<String> supported = Arrays.asList("image/jpeg", "image/png", "image/gif");
        String result = MediaTypeNegotiator.negotiate(
                "image/*", null, supported, "image/jpeg");
        assertEquals("image/jpeg", result);
    }

    @Test
    public void negotiate_NoMatchWithDefault_ReturnsDefault() {
        List<String> supported = Arrays.asList("application/dicom+json", "application/dicom+xml");
        String result = MediaTypeNegotiator.negotiate(
                "image/jpeg", null, supported, "application/dicom+json");
        assertEquals("application/dicom+json", result);
    }

    @Test(expected = NotAcceptableException.class)
    public void negotiate_NoMatchNoDefault_ThrowsNotAcceptable() {
        List<String> supported = Arrays.asList("application/dicom+json", "application/dicom+xml");
        MediaTypeNegotiator.negotiate("image/jpeg", null, supported, null);
    }

    @Test
    public void negotiate_AcceptParamTakesPrecedence() {
        List<String> supported = Arrays.asList("application/dicom+json", "application/dicom+xml");
        // Header says JSON, but query param says XML — param wins
        String result = MediaTypeNegotiator.negotiate(
                "application/dicom+json", "application/dicom+xml",
                supported, "application/dicom+json");
        assertEquals("application/dicom+xml", result);
    }

    // Wildcards in the accept query parameter are rejected by
    // AcceptParamWildcardInterceptor before reaching this method;
    // see AcceptParamWildcardInterceptorTest.

    @Test
    public void negotiate_MultipleAcceptedTypes_SelectsBestSupported() {
        List<String> supported = Arrays.asList("application/dicom+json", "application/dicom+xml");
        // Client prefers XML over JSON
        String result = MediaTypeNegotiator.negotiate(
                "application/dicom+xml;q=1.0, application/dicom+json;q=0.8",
                null, supported, "application/dicom+json");
        assertEquals("application/dicom+xml", result);
    }

    @Test
    public void negotiate_RenderedImageTypes() {
        List<String> supported = Arrays.asList("image/jpeg", "image/png", "image/gif");
        // Client wants PNG
        String result = MediaTypeNegotiator.negotiate(
                "image/png", null, supported, "image/jpeg");
        assertEquals("image/png", result);
    }

    @Test
    public void negotiate_RenderedImageTypes_GifPreferred() {
        List<String> supported = Arrays.asList("image/jpeg", "image/png", "image/gif");
        String result = MediaTypeNegotiator.negotiate(
                "image/gif;q=1.0, image/jpeg;q=0.5", null, supported, "image/jpeg");
        assertEquals("image/gif", result);
    }

    @Test
    public void negotiate_EmptyAcceptParam_UsesHeader() {
        List<String> supported = Arrays.asList("application/dicom+json", "application/dicom+xml");
        String result = MediaTypeNegotiator.negotiate(
                "application/dicom+xml", "  ", supported, "application/dicom+json");
        assertEquals("application/dicom+xml", result);
    }
}
