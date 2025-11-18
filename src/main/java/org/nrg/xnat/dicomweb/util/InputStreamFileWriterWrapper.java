/*
 * XNAT DICOMweb Proxy Plugin
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 *
 * This software is distributed under the terms described in the LICENSE file.
 */

package org.nrg.xnat.dicomweb.util;

import org.nrg.xnat.restlet.util.FileWriterWrapperI;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * FileWriterWrapperI implementation that wraps an InputStream.
 * Used to adapt STOW-RS multipart DICOM instances to XNAT's importer framework.
 */
public class InputStreamFileWriterWrapper implements FileWriterWrapperI {

    private final InputStream inputStream;
    private final String fileName;
    private final byte[] data;

    /**
     * Create a wrapper from an InputStream.
     * The stream is immediately read into memory to allow multiple reads.
     */
    public InputStreamFileWriterWrapper(InputStream inputStream, String fileName) throws IOException {
        this.fileName = fileName;
        // Read the entire stream into memory so it can be read multiple times
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] temp = new byte[8192];
        int bytesRead;
        while ((bytesRead = inputStream.read(temp)) != -1) {
            buffer.write(temp, 0, bytesRead);
        }
        this.data = buffer.toByteArray();
        this.inputStream = new ByteArrayInputStream(this.data);
    }

    @Override
    public void write(File f) throws Exception {
        // Create parent directories if needed
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        // Write data to file
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(data);
        }
    }

    @Override
    public String getName() {
        return fileName;
    }

    @Override
    public String getNestedPath() {
        // Not a nested archive
        return null;
    }

    @Override
    public InputStream getInputStream() throws IOException {
        // Return a fresh stream each time
        return new ByteArrayInputStream(data);
    }

    @Override
    public void delete() {
        // No cleanup needed for in-memory data
    }

    @Override
    public UPLOAD_TYPE getType() {
        return UPLOAD_TYPE.OTHER;
    }
}
