/*
 * XNAT DICOMweb Proxy Plugin
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 */

package org.nrg.xnat.dicomweb.service;

/**
 * Supported image output formats for rendered DICOM instances
 */
public enum ImageFormat {
    /**
     * JPEG format - single static image
     */
    JPEG("image/jpeg"),

    /**
     * GIF format - can be static or animated for multi-frame instances
     */
    GIF("image/gif");

    private final String mimeType;

    ImageFormat(String mimeType) {
        this.mimeType = mimeType;
    }

    public String getMimeType() {
        return mimeType;
    }

    /**
     * Parse format from Accept header or media type
     */
    public static ImageFormat fromMimeType(String mimeType) {
        if (mimeType == null) {
            return JPEG;  // Default
        }
        String lower = mimeType.toLowerCase();
        if (lower.contains("image/gif") || lower.contains("gif")) {
            return GIF;
        }
        return JPEG;
    }
}
