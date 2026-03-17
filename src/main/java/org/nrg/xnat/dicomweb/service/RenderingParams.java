package org.nrg.xnat.dicomweb.service;

import org.nrg.xnat.dicomweb.exceptions.BadRequestException;

/**
 * Value object that parses and validates PS 3.18 Section 8.3.5 rendering query parameters.
 * <p>
 * Supported parameters:
 * <ul>
 *   <li>{@code viewport=vw,vh} — target width and height for scaling</li>
 *   <li>{@code window=center,width} — VOI LUT window center and width</li>
 *   <li>{@code quality=1-100} — JPEG compression quality</li>
 * </ul>
 */
public class RenderingParams {

    private static final int DEFAULT_THUMBNAIL_SIZE = 128;

    private final Integer viewportWidth;
    private final Integer viewportHeight;
    private final Double windowCenter;
    private final Double windowWidth;
    private final Integer quality;

    private RenderingParams(Integer viewportWidth, Integer viewportHeight,
                            Double windowCenter, Double windowWidth, Integer quality) {
        this.viewportWidth = viewportWidth;
        this.viewportHeight = viewportHeight;
        this.windowCenter = windowCenter;
        this.windowWidth = windowWidth;
        this.quality = quality;
    }

    /**
     * Parse rendering parameters from request parameter strings.
     *
     * @param viewport viewport string in format "width,height" (e.g. "640,480"), or null
     * @param window window string in format "center,width" (e.g. "400,2000"), or null
     * @param qualityStr quality string "1"-"100", or null
     * @return parsed RenderingParams, or null if all parameters are null/empty
     * @throws BadRequestException if any parameter value is invalid
     */
    public static RenderingParams parse(String viewport, String window, String qualityStr) {
        if ((viewport == null || viewport.isEmpty()) &&
            (window == null || window.isEmpty()) &&
            (qualityStr == null || qualityStr.isEmpty())) {
            return null;
        }

        Integer vw = null;
        Integer vh = null;
        Double wc = null;
        Double ww = null;
        Integer q = null;

        if (viewport != null && !viewport.isEmpty()) {
            String[] parts = viewport.split(",");
            if (parts.length != 2) {
                throw new BadRequestException("viewport", "must be in format 'width,height'");
            }
            try {
                vw = Integer.parseInt(parts[0].trim());
                vh = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException e) {
                throw new BadRequestException("viewport", "width and height must be integers");
            }
            if (vw <= 0 || vh <= 0) {
                throw new BadRequestException("viewport", "width and height must be positive");
            }
        }

        if (window != null && !window.isEmpty()) {
            String[] parts = window.split(",");
            if (parts.length != 2) {
                throw new BadRequestException("window", "must be in format 'center,width'");
            }
            try {
                wc = Double.parseDouble(parts[0].trim());
                ww = Double.parseDouble(parts[1].trim());
            } catch (NumberFormatException e) {
                throw new BadRequestException("window", "center and width must be numbers");
            }
            if (ww <= 0) {
                throw new BadRequestException("window", "width must be positive");
            }
        }

        if (qualityStr != null && !qualityStr.isEmpty()) {
            try {
                q = Integer.parseInt(qualityStr.trim());
            } catch (NumberFormatException e) {
                throw new BadRequestException("quality", "must be an integer");
            }
            if (q < 1 || q > 100) {
                throw new BadRequestException("quality", "must be between 1 and 100");
            }
        }

        return new RenderingParams(vw, vh, wc, ww, q);
    }

    /**
     * Create a copy of the given params with default thumbnail viewport (128x128)
     * if no viewport is specified. Other params are preserved.
     *
     * @param params existing params (may be null)
     * @return params with viewport set to 128x128 if not already specified
     */
    public static RenderingParams withDefaultThumbnailSize(RenderingParams params) {
        if (params != null && params.hasViewport()) {
            return params;
        }
        return new RenderingParams(
                DEFAULT_THUMBNAIL_SIZE,
                DEFAULT_THUMBNAIL_SIZE,
                params != null ? params.windowCenter : null,
                params != null ? params.windowWidth : null,
                params != null ? params.quality : null
        );
    }

    public boolean hasViewport() {
        return viewportWidth != null && viewportHeight != null;
    }

    public boolean hasWindow() {
        return windowCenter != null && windowWidth != null;
    }

    public Integer getViewportWidth() {
        return viewportWidth;
    }

    public Integer getViewportHeight() {
        return viewportHeight;
    }

    public Double getWindowCenter() {
        return windowCenter;
    }

    public Double getWindowWidth() {
        return windowWidth;
    }

    public Integer getQuality() {
        return quality;
    }
}
