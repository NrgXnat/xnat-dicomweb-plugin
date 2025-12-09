/*
 * XNAT DICOMweb Proxy Plugin
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */

package org.nrg.xnat.dicomweb.helpers;

import org.apache.commons.io.IOUtils;
import org.nrg.xnat.dicomweb.parser.Mime4jHybridParser.MultipartPart;
import org.nrg.xnat.restlet.util.FileWriterWrapperI;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * FileWriterWrapper implementation for STOW-RS MultipartPart
 * Wraps a MultipartPart to provide FileWriterWrapperI interface
 */
public class InputStreamFileWriterWrapper implements FileWriterWrapperI {

    private final MultipartPart part;
    private final String name;
    private final int index;

    /**
     * Constructor
     * @param part The MultipartPart to wrap
     * @param index The index of this part in the multipart request
     */
    public InputStreamFileWriterWrapper(final MultipartPart part, final int index) {
        this.part = part;
        this.index = index;
        // Generate a globally unique name to avoid file lock conflicts in concurrent uploads
        // Using UUID ensures different concurrent requests won't clash on the same filename
        this.name = UUID.randomUUID().toString() + ".dcm";
    }

    @Override
    public void write(File f) throws Exception {
        try (InputStream is = part.getInputStream();
             FileOutputStream fos = new FileOutputStream(f)) {
            if (part.getSize() > 2000000) {
                IOUtils.copyLarge(is, fos);
            } else {
                IOUtils.copy(is, fos);
            }
        }
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getNestedPath() {
        return null;
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return part.getInputStream();
    }

    @Override
    public void delete() {
        // MultipartPart cleanup is handled by Mime4jHybridParser
        part.cleanup();
    }

    @Override
    public UPLOAD_TYPE getType() {
        return UPLOAD_TYPE.MULTIPART;
    }
}
