/*
 * XNAT DICOMweb Plugin
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 */

package org.nrg.xnat.dicomweb.service;

/**
 * Result object containing rendered DICOM image data and metadata.
 * Used for multi-frame aware rendering with frame information.
 */
public class RenderedInstanceResult {

    private final byte[] imageData;
    private final int totalFrames;
    private final int renderedFrame;
    private final Double frameRate;  // frames per second, may be null
    private final ImageFormat format;  // output format (JPEG or GIF)

    public RenderedInstanceResult(byte[] imageData, int totalFrames, int renderedFrame, Double frameRate) {
        this(imageData, totalFrames, renderedFrame, frameRate, ImageFormat.JPEG);
    }

    public RenderedInstanceResult(byte[] imageData, int totalFrames, int renderedFrame, Double frameRate, ImageFormat format) {
        this.imageData = imageData;
        this.totalFrames = totalFrames;
        this.renderedFrame = renderedFrame;
        this.frameRate = frameRate;
        this.format = format;
    }

    /**
     * Get the rendered image data (JPEG bytes)
     */
    public byte[] getImageData() {
        return imageData;
    }

    /**
     * Get total number of frames in the DICOM instance
     */
    public int getTotalFrames() {
        return totalFrames;
    }

    /**
     * Get the frame number that was rendered (1-based)
     */
    public int getRenderedFrame() {
        return renderedFrame;
    }

    /**
     * Get frame rate in frames per second (may be null if not available)
     */
    public Double getFrameRate() {
        return frameRate;
    }

    /**
     * Check if this is a multi-frame instance
     */
    public boolean isMultiFrame() {
        return totalFrames > 1;
    }

    /**
     * Get the image format (JPEG or GIF)
     */
    public ImageFormat getFormat() {
        return format;
    }

    /**
     * Get the MIME type for the image format
     */
    public String getMimeType() {
        return format.getMimeType();
    }
}
