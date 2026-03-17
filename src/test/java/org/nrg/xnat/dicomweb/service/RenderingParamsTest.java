package org.nrg.xnat.dicomweb.service;

import org.junit.Test;
import org.nrg.xnat.dicomweb.exceptions.BadRequestException;

import static org.junit.Assert.*;

public class RenderingParamsTest {

    @Test
    public void testParseViewport() {
        RenderingParams params = RenderingParams.parse("640,480", null, null);
        assertNotNull(params);
        assertTrue(params.hasViewport());
        assertEquals(Integer.valueOf(640), params.getViewportWidth());
        assertEquals(Integer.valueOf(480), params.getViewportHeight());
    }

    @Test
    public void testParseWindow() {
        RenderingParams params = RenderingParams.parse(null, "400,2000", null);
        assertNotNull(params);
        assertTrue(params.hasWindow());
        assertEquals(Double.valueOf(400.0), params.getWindowCenter());
        assertEquals(Double.valueOf(2000.0), params.getWindowWidth());
    }

    @Test
    public void testParseQuality() {
        RenderingParams params = RenderingParams.parse(null, null, "85");
        assertNotNull(params);
        assertEquals(Integer.valueOf(85), params.getQuality());
    }

    @Test
    public void testParseAllParams() {
        RenderingParams params = RenderingParams.parse("320,240", "100,500", "90");
        assertNotNull(params);
        assertTrue(params.hasViewport());
        assertTrue(params.hasWindow());
        assertEquals(Integer.valueOf(320), params.getViewportWidth());
        assertEquals(Integer.valueOf(240), params.getViewportHeight());
        assertEquals(Double.valueOf(100.0), params.getWindowCenter());
        assertEquals(Double.valueOf(500.0), params.getWindowWidth());
        assertEquals(Integer.valueOf(90), params.getQuality());
    }

    @Test
    public void testParseAllNull_ReturnsNull() {
        assertNull(RenderingParams.parse(null, null, null));
    }

    @Test
    public void testParseAllEmpty_ReturnsNull() {
        assertNull(RenderingParams.parse("", "", ""));
    }

    @Test
    public void testNoViewport() {
        RenderingParams params = RenderingParams.parse(null, "400,2000", null);
        assertNotNull(params);
        assertFalse(params.hasViewport());
        assertNull(params.getViewportWidth());
        assertNull(params.getViewportHeight());
    }

    @Test
    public void testNoWindow() {
        RenderingParams params = RenderingParams.parse("640,480", null, null);
        assertNotNull(params);
        assertFalse(params.hasWindow());
        assertNull(params.getWindowCenter());
        assertNull(params.getWindowWidth());
    }

    @Test(expected = BadRequestException.class)
    public void testInvalidViewport_SingleValue() {
        RenderingParams.parse("640", null, null);
    }

    @Test(expected = BadRequestException.class)
    public void testInvalidViewport_NotNumbers() {
        RenderingParams.parse("abc,def", null, null);
    }

    @Test(expected = BadRequestException.class)
    public void testInvalidViewport_ZeroWidth() {
        RenderingParams.parse("0,480", null, null);
    }

    @Test(expected = BadRequestException.class)
    public void testInvalidViewport_NegativeHeight() {
        RenderingParams.parse("640,-1", null, null);
    }

    @Test(expected = BadRequestException.class)
    public void testInvalidWindow_SingleValue() {
        RenderingParams.parse(null, "400", null);
    }

    @Test(expected = BadRequestException.class)
    public void testInvalidWindow_ZeroWidth() {
        RenderingParams.parse(null, "400,0", null);
    }

    @Test(expected = BadRequestException.class)
    public void testInvalidWindow_NegativeWidth() {
        RenderingParams.parse(null, "400,-100", null);
    }

    @Test(expected = BadRequestException.class)
    public void testInvalidQuality_Zero() {
        RenderingParams.parse(null, null, "0");
    }

    @Test(expected = BadRequestException.class)
    public void testInvalidQuality_Over100() {
        RenderingParams.parse(null, null, "101");
    }

    @Test(expected = BadRequestException.class)
    public void testInvalidQuality_NotNumber() {
        RenderingParams.parse(null, null, "abc");
    }

    @Test
    public void testWithDefaultThumbnailSize_NullParams() {
        RenderingParams result = RenderingParams.withDefaultThumbnailSize(null);
        assertNotNull(result);
        assertTrue(result.hasViewport());
        assertEquals(Integer.valueOf(128), result.getViewportWidth());
        assertEquals(Integer.valueOf(128), result.getViewportHeight());
        assertFalse(result.hasWindow());
        assertNull(result.getQuality());
    }

    @Test
    public void testWithDefaultThumbnailSize_NoViewport() {
        RenderingParams params = RenderingParams.parse(null, "400,2000", "85");
        RenderingParams result = RenderingParams.withDefaultThumbnailSize(params);
        assertNotNull(result);
        assertTrue(result.hasViewport());
        assertEquals(Integer.valueOf(128), result.getViewportWidth());
        assertEquals(Integer.valueOf(128), result.getViewportHeight());
        // Preserves other params
        assertTrue(result.hasWindow());
        assertEquals(Double.valueOf(400.0), result.getWindowCenter());
        assertEquals(Double.valueOf(2000.0), result.getWindowWidth());
        assertEquals(Integer.valueOf(85), result.getQuality());
    }

    @Test
    public void testWithDefaultThumbnailSize_ExistingViewport_Preserved() {
        RenderingParams params = RenderingParams.parse("256,256", null, null);
        RenderingParams result = RenderingParams.withDefaultThumbnailSize(params);
        assertNotNull(result);
        assertTrue(result.hasViewport());
        assertEquals(Integer.valueOf(256), result.getViewportWidth());
        assertEquals(Integer.valueOf(256), result.getViewportHeight());
    }

    @Test
    public void testQualityBoundary_Min() {
        RenderingParams params = RenderingParams.parse(null, null, "1");
        assertNotNull(params);
        assertEquals(Integer.valueOf(1), params.getQuality());
    }

    @Test
    public void testQualityBoundary_Max() {
        RenderingParams params = RenderingParams.parse(null, null, "100");
        assertNotNull(params);
        assertEquals(Integer.valueOf(100), params.getQuality());
    }

    @Test
    public void testViewportWithSpaces() {
        RenderingParams params = RenderingParams.parse(" 640 , 480 ", null, null);
        assertNotNull(params);
        assertEquals(Integer.valueOf(640), params.getViewportWidth());
        assertEquals(Integer.valueOf(480), params.getViewportHeight());
    }

    @Test
    public void testWindowNegativeCenter() {
        // Negative center is valid (e.g. for certain modalities)
        RenderingParams params = RenderingParams.parse(null, "-100,500", null);
        assertNotNull(params);
        assertEquals(Double.valueOf(-100.0), params.getWindowCenter());
        assertEquals(Double.valueOf(500.0), params.getWindowWidth());
    }
}
