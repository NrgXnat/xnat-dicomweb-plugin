/*
 * XNAT DICOMweb Proxy Plugin
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 */

package org.nrg.xnat.dicomweb.utils;

import org.nrg.xnat.dicomweb.parser.Mime4jHybridParser.MultipartPart;
import org.nrg.xnat.dicomweb.service.FailedInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Utility class for DICOM validation operations.
 * Provides common validation methods used by import strategies.
 */
public final class DicomValidationUtils {

    private static final Logger logger = LoggerFactory.getLogger(DicomValidationUtils.class);

    // DICOM file prefix offset and magic bytes
    public static final int DICOM_PREFIX_OFFSET = 128;
    public static final byte[] DICOM_MAGIC_BYTES = "DICM".getBytes(StandardCharsets.US_ASCII);

    // Valid DICOM content types
    public static final Set<String> VALID_DICOM_CONTENT_TYPES = new HashSet<>(Arrays.asList(
        "application/dicom",
        "application/octet-stream"  // Some clients use this
    ));

    private DicomValidationUtils() {
        // Utility class - prevent instantiation
    }

    /**
     * Validate that a multipart part is likely a DICOM file.
     * Performs content-type and magic bytes validation.
     *
     * @param part The multipart part to validate
     * @param index The part index for error reporting
     * @return FailedInstance if validation fails, null if validation passes
     */
    public static FailedInstance validateDicomPart(MultipartPart part, int index) {
        String contentType = part.getContentType();

        // Check 1: Content-Type validation
        if (contentType != null && !contentType.isEmpty()) {
            String normalizedType = contentType.toLowerCase().split(";")[0].trim();
            if (!VALID_DICOM_CONTENT_TYPES.contains(normalizedType) &&
                !normalizedType.startsWith("application/dicom")) {
                logger.info("Part {} has non-DICOM Content-Type: {}", index, contentType);
                return FailedInstance.cannotUnderstand(index,
                    "Invalid Content-Type: " + contentType + ". Expected application/dicom");
            }
        }

        // Check 2: DICOM magic bytes validation
        if (part.getSize() >= DICOM_PREFIX_OFFSET + DICOM_MAGIC_BYTES.length) {
            try (InputStream is = part.getInputStream()) {
                byte[] header = new byte[DICOM_PREFIX_OFFSET + DICOM_MAGIC_BYTES.length];
                int bytesRead = 0;
                while (bytesRead < header.length) {
                    int read = is.read(header, bytesRead, header.length - bytesRead);
                    if (read == -1) break;
                    bytesRead += read;
                }

                if (bytesRead >= DICOM_PREFIX_OFFSET + DICOM_MAGIC_BYTES.length) {
                    byte[] magicBytes = new byte[DICOM_MAGIC_BYTES.length];
                    System.arraycopy(header, DICOM_PREFIX_OFFSET, magicBytes, 0, DICOM_MAGIC_BYTES.length);

                    if (!Arrays.equals(magicBytes, DICOM_MAGIC_BYTES)) {
                        String foundMagic = new String(magicBytes, StandardCharsets.US_ASCII);
                        logger.info("Part {} does not have DICOM magic bytes. Found: '{}' at offset {}",
                            index, foundMagic, DICOM_PREFIX_OFFSET);
                        return FailedInstance.cannotUnderstand(index,
                            "Not a valid DICOM file: missing DICM header");
                    }
                }
            } catch (IOException e) {
                logger.warn("Failed to read part {} for validation: {}", index, e.getMessage());
            }
        } else if (part.getSize() > 0 && part.getSize() < DICOM_PREFIX_OFFSET) {
            logger.info("Part {} is too small to be a valid DICOM file: {} bytes", index, part.getSize());
            return FailedInstance.cannotUnderstand(index,
                "File too small to be a valid DICOM file: " + part.getSize() + " bytes");
        }

        return null;
    }

    /**
     * Check if the given content type is a valid DICOM content type.
     *
     * @param contentType The content type to check
     * @return true if valid DICOM content type, false otherwise
     */
    public static boolean isValidDicomContentType(String contentType) {
        if (contentType == null || contentType.isEmpty()) {
            return true;  // Allow missing content type
        }
        String normalizedType = contentType.toLowerCase().split(";")[0].trim();
        return VALID_DICOM_CONTENT_TYPES.contains(normalizedType) ||
               normalizedType.startsWith("application/dicom");
    }
}
