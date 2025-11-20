package org.nrg.xnat.dicomweb.parser;

import org.apache.james.mime4j.parser.ContentHandler;
import org.apache.james.mime4j.parser.MimeStreamParser;
import org.apache.james.mime4j.stream.BodyDescriptor;
import org.apache.james.mime4j.stream.Field;
import org.apache.james.mime4j.stream.MimeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Hybrid Multipart Parser
 * Small files are stored in memory, large files are stored on disk
 *
 * Strategy:
 * - Files <= 10MB: Use memory buffering
 * - Files > 10MB: Use disk temporary files
 */
public class Mime4jHybridParser {

    private static final Logger logger = LoggerFactory.getLogger(Mime4jHybridParser.class);

    /**
     * Memory/disk threshold: 10MB
     */
    private static final long MEMORY_THRESHOLD = 10 * 1024 * 1024;

    /**
     * Temporary file directory
     */
    private final File tempDirectory;

    /**
     * Default constructor - uses system temporary directory
     */
    public Mime4jHybridParser() {
        this(createDefaultTempDirectory());
    }

    /**
     * Constructor with custom temporary directory
     */
    public Mime4jHybridParser(File tempDirectory) {
        this.tempDirectory = tempDirectory;
        if (!this.tempDirectory.exists()) {
            this.tempDirectory.mkdirs();
        }
        logger.info("Mime4jHybridParser initialized with temp directory: {}",
            this.tempDirectory.getAbsolutePath());
    }

    /**
     * Create default temporary directory
     */
    private static File createDefaultTempDirectory() {
        String baseTempDir = System.getProperty("java.io.tmpdir");
        File dir = new File(baseTempDir, "stow-rs-" + System.currentTimeMillis());
        dir.mkdirs();
        return dir;
    }

    /**
     * Parse multipart/related request
     *
     * @param contentType Content-Type header
     * @param bodyBytes HTTP request body byte array
     * @return List of parts
     * @throws IOException Parse error
     */
    public List<MultipartPart> parse(String contentType, byte[] bodyBytes) throws IOException {

        // Validate Content-Type
        if (!isMultipartRelated(contentType)) {
            logger.warn("Request is not multipart/related, Content-Type: {}", contentType);
            return new ArrayList<>();
        }

        logger.info("Parsing multipart/related request, Content-Type: {}", contentType);
        logger.debug("Body size: {} bytes", bodyBytes.length);

        List<MultipartPart> parts = new ArrayList<>();

        try {
            // Configure MimeStreamParser
            MimeConfig config = MimeConfig.custom()
                .setMaxLineLen(-1)           // No line length limit
                .setMaxHeaderLen(-1)         // No header length limit
                .setMaxContentLen(-1)        // No content length limit
                .setStrictParsing(false)     // Lenient parsing
                .build();

            MimeStreamParser parser = new MimeStreamParser(config);

            // Set ContentHandler
            HybridContentHandler handler = new HybridContentHandler(parts, tempDirectory);
            parser.setContentHandler(handler);

            // MimeStreamParser requires complete MIME message (including headers)
            // HTTP request body only contains content, need to manually add Content-Type header

            // Construct MIME message headers
            String mimeHeaders = String.format("Content-Type: %s\r\n\r\n", contentType);
            ByteArrayInputStream headerStream = new ByteArrayInputStream(mimeHeaders.getBytes("UTF-8"));
            ByteArrayInputStream bodyStream = new ByteArrayInputStream(bodyBytes);

            // Use SequenceInputStream to combine headers and body
            try (SequenceInputStream combinedStream = new SequenceInputStream(headerStream, bodyStream);
                 BufferedInputStream bufferedStream = new BufferedInputStream(combinedStream, 8192)) {
                parser.parse(bufferedStream);
            }

            logger.info("Parsing completed: {} parts extracted ({} in memory, {} on disk)",
                parts.size(),
                parts.stream().filter(MultipartPart::isInMemory).count(),
                parts.stream().filter(p -> !p.isInMemory()).count());

        } catch (Exception e) {
            logger.error("Error parsing multipart request", e);
            // Clean up created resources
            cleanup(parts);
            throw new IOException("Failed to parse multipart request", e);
        }

        return parts;
    }

    /**
     * Check if content type is multipart/related
     */
    private boolean isMultipartRelated(String contentType) {
        if (contentType == null) {
            return false;
        }
        String normalized = contentType.toLowerCase().trim();
        return normalized.startsWith("multipart/related") ||
               normalized.startsWith("multipart/");
    }

    /**
     * Clean up resources in the part list
     */
    public void cleanup(List<MultipartPart> parts) {
        if (parts == null) {
            return;
        }

        int cleanedCount = 0;
        for (MultipartPart part : parts) {
            if (part != null) {
                part.cleanup();
                cleanedCount++;
            }
        }

        logger.debug("Cleaned up {} parts", cleanedCount);
    }

    /**
     * Clean up temporary directory
     */
    public void cleanupTempDirectory() {
        if (tempDirectory != null && tempDirectory.exists()) {
            File[] files = tempDirectory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.delete()) {
                        logger.debug("Deleted temp file: {}", file.getName());
                    }
                }
            }
            if (tempDirectory.delete()) {
                logger.info("Deleted temp directory: {}", tempDirectory.getAbsolutePath());
            }
        }
    }

    /**
     * Multipart Part data wrapper class
     * Provides unified access interface, hiding memory/disk storage differences
     */
    public static class MultipartPart {

        private final byte[] memoryData;      // Memory storage (small files)
        private final File diskFile;          // Disk storage (large files)
        private final long size;
        private final String contentType;
        private final String contentId;
        private final String contentLocation;

        /**
         * Constructor for memory storage (small files)
         */
        public MultipartPart(byte[] data, String contentType, String contentId, String contentLocation) {
            this.memoryData = data;
            this.diskFile = null;
            this.size = data.length;
            this.contentType = contentType;
            this.contentId = contentId;
            this.contentLocation = contentLocation;
        }

        /**
         * Constructor for disk storage (large files)
         */
        public MultipartPart(File file, String contentType, String contentId, String contentLocation) {
            this.memoryData = null;
            this.diskFile = file;
            this.size = file.length();
            this.contentType = contentType;
            this.contentId = contentId;
            this.contentLocation = contentLocation;
        }

        /**
         * Get input stream - can be called multiple times
         */
        public InputStream getInputStream() throws IOException {
            if (memoryData != null) {
                return new ByteArrayInputStream(memoryData);
            } else if (diskFile != null) {
                return new FileInputStream(diskFile);
            } else {
                throw new IOException("Part has no data");
            }
        }

        /**
         * Check if stored in memory
         */
        public boolean isInMemory() {
            return memoryData != null;
        }

        /**
         * Get data size
         */
        public long getSize() {
            return size;
        }

        /**
         * Get Content-Type
         */
        public String getContentType() {
            return contentType;
        }

        /**
         * Get Content-ID
         */
        public String getContentId() {
            return contentId;
        }

        /**
         * Get Content-Location
         */
        public String getContentLocation() {
            return contentLocation;
        }

        /**
         * Get disk file (if stored on disk)
         */
        public File getDiskFile() {
            return diskFile;
        }

        /**
         * Clean up resources
         */
        public void cleanup() {
            if (diskFile != null && diskFile.exists()) {
                if (diskFile.delete()) {
                    logger.debug("Deleted part file: {}", diskFile.getName());
                } else {
                    logger.warn("Failed to delete part file: {}", diskFile.getAbsolutePath());
                }
            }
        }

        @Override
        public String toString() {
            return String.format("MultipartPart[type=%s, size=%d, storage=%s]",
                contentType, size, isInMemory() ? "memory" : "disk");
        }
    }

    /**
     * ContentHandler implementation - handles each multipart part
     */
    private static class HybridContentHandler implements ContentHandler {

        private final List<MultipartPart> parts;
        private final File tempDirectory;

        // Current part state
        private String currentContentType;
        private String currentContentId;
        private String currentContentLocation;
        private ByteArrayOutputStream memoryBuffer;
        private FileOutputStream fileOutputStream;
        private File currentTempFile;
        private long currentSize;

        public HybridContentHandler(List<MultipartPart> parts, File tempDirectory) {
            this.parts = parts;
            this.tempDirectory = tempDirectory;
        }

        @Override
        public void startMessage() {
            logger.debug("Start message");
        }

        @Override
        public void endMessage() {
            logger.debug("End message");
        }

        @Override
        public void startMultipart(BodyDescriptor bd) {
            logger.debug("Start multipart: {}", bd.getMimeType());
        }

        @Override
        public void endMultipart() {
            logger.debug("End multipart");
        }

        @Override
        public void startBodyPart() {
            logger.debug("Start body part");

            // Initialize new part state
            currentContentType = null;
            currentContentId = null;
            currentContentLocation = null;
            memoryBuffer = new ByteArrayOutputStream();
            fileOutputStream = null;
            currentTempFile = null;
            currentSize = 0;
        }

        @Override
        public void field(Field field) {
            String name = field.getName();
            String value = field.getBody();

            logger.debug("Header: {} = {}", name, value);

            // Extract common MIME headers
            if ("Content-Type".equalsIgnoreCase(name)) {
                currentContentType = value;
            } else if ("Content-ID".equalsIgnoreCase(name)) {
                currentContentId = value;
            } else if ("Content-Location".equalsIgnoreCase(name)) {
                currentContentLocation = value;
            }
        }

        @Override
        public void startHeader() {
            logger.debug("Start header");
        }

        @Override
        public void endHeader() {
            logger.debug("End header - Content-Type: {}", currentContentType);
        }

        @Override
        public void body(BodyDescriptor bd, InputStream is) throws IOException {
            logger.debug("Processing body, MIME type: {}", bd.getMimeType());

            // Skip if not in a body part (e.g., multipart container itself)
            if (memoryBuffer == null && fileOutputStream == null) {
                logger.debug("Skipping body - not in a body part");
                return;
            }

            byte[] buffer = new byte[8192];
            int bytesRead;

            while ((bytesRead = is.read(buffer)) != -1) {
                currentSize += bytesRead;

                // Decide storage strategy based on current size
                if (currentSize <= MEMORY_THRESHOLD) {
                    // Small file: write to memory
                    if (memoryBuffer != null) {
                        memoryBuffer.write(buffer, 0, bytesRead);
                    }

                } else {
                    // Large file: switch to disk
                    if (fileOutputStream == null) {
                        // First time exceeding threshold: create temporary file
                        currentTempFile = File.createTempFile(
                            "part-", ".tmp", tempDirectory);
                        fileOutputStream = new FileOutputStream(currentTempFile);

                        // Write buffered memory data to file
                        if (memoryBuffer.size() > 0) {
                            memoryBuffer.writeTo(fileOutputStream);
                            memoryBuffer = null; // Release memory
                        }

                        logger.info("Part size exceeded threshold ({}), switching to disk: {}",
                            formatSize(MEMORY_THRESHOLD), currentTempFile.getName());
                    }

                    // Write to disk
                    fileOutputStream.write(buffer, 0, bytesRead);
                }
            }

            // Flush file buffer
            if (fileOutputStream != null) {
                fileOutputStream.flush();
            }

            logger.debug("Body processed: {} bytes", currentSize);
        }

        @Override
        public void endBodyPart() {
            logger.debug("End body part");

            try {
                MultipartPart part;

                if (fileOutputStream != null) {
                    // Large file: close stream and create Part
                    fileOutputStream.close();
                    part = new MultipartPart(
                        currentTempFile,
                        currentContentType,
                        currentContentId,
                        currentContentLocation);

                    logger.info("Added large part: {} ({})",
                        currentTempFile.getName(), formatSize(currentSize));

                } else if (memoryBuffer != null && memoryBuffer.size() > 0) {
                    // Small file: create Part from memory
                    part = new MultipartPart(
                        memoryBuffer.toByteArray(),
                        currentContentType,
                        currentContentId,
                        currentContentLocation);

                    logger.info("Added small part: {} bytes", currentSize);

                } else {
                    // Empty part, skip
                    logger.debug("Skipping empty part");
                    return;
                }

                // Add to result list
                parts.add(part);

            } catch (IOException e) {
                logger.error("Error finalizing body part", e);

                // Clean up failed temporary file
                if (currentTempFile != null && currentTempFile.exists()) {
                    currentTempFile.delete();
                }

            } finally {
                // Reset state
                memoryBuffer = null;
                fileOutputStream = null;
                currentTempFile = null;
            }
        }

        @Override
        public void preamble(InputStream is) throws IOException {
            // Multipart preamble (content before boundary)
            // Note: Do not read InputStream, otherwise data will be consumed
            logger.debug("Preamble");
        }

        @Override
        public void epilogue(InputStream is) throws IOException {
            // Multipart epilogue (content after last boundary)
            logger.debug("Epilogue");
        }

        @Override
        public void raw(InputStream is) throws IOException {
            // Raw data (usually not used)
            logger.debug("Raw data");
        }

        /**
         * Format byte count to readable string
         */
        private String formatSize(long bytes) {
            if (bytes < 1024) {
                return bytes + " B";
            } else if (bytes < 1024 * 1024) {
                return String.format("%.2f KB", bytes / 1024.0);
            } else {
                return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
            }
        }
    }
}
