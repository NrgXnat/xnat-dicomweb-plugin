package org.nrg.xnat.dicomweb.parser;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.when;

/**
 * Mime4jHybridParser unit tests
 */
public class Mime4jHybridParserTest {

    private Mime4jHybridParser parser;

    @Mock
    private HttpServletRequest mockRequest;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        parser = new Mime4jHybridParser();
    }

    @After
    public void tearDown() {
        if (parser != null) {
            parser.cleanupTempDirectory();
        }
    }

    /**
     * Test: non-multipart request should return empty list
     */
    @Test
    public void testNonMultipartRequest() throws Exception {
        when(mockRequest.getContentType()).thenReturn("application/json");
        when(mockRequest.getInputStream()).thenReturn(createEmptyInputStream());

        List<Mime4jHybridParser.MultipartPart> parts = parser.parse(mockRequest);

        assertNotNull(parts);
        assertEquals(0, parts.size());
    }

    /**
     * Test: parse multipart/related request containing small files
     */
    @Test
    public void testParseSmallFiles() throws Exception {
        // Create multipart request with 2 small files
        String boundary = "----TestBoundary123";
        byte[] multipartData = createMultipartData(boundary,
            new Part("Hello, World!".getBytes(), "text/plain"),
            new Part("Another small file".getBytes(), "text/plain")
        );

        when(mockRequest.getContentType())
            .thenReturn("multipart/related; boundary=" + boundary);
        when(mockRequest.getInputStream())
            .thenReturn(createServletInputStream(multipartData));

        List<Mime4jHybridParser.MultipartPart> parts = parser.parse(mockRequest);

        assertEquals(2, parts.size());

        // Verify first part
        Mime4jHybridParser.MultipartPart part1 = parts.get(0);
        assertTrue(part1.isInMemory());
        assertEquals("text/plain", part1.getContentType());
        assertEquals(13, part1.getSize()); // "Hello, World!".length()

        // Verify data can be read
        try (InputStream is = part1.getInputStream()) {
            byte[] data = readAll(is);
            assertEquals("Hello, World!", new String(data));
        }

        // Verify second part
        Mime4jHybridParser.MultipartPart part2 = parts.get(1);
        assertTrue(part2.isInMemory());

        // Cleanup
        parser.cleanup(parts);
    }

    /**
     * Test: parse multipart/related request containing large files
     */
    @Test
    public void testParseLargeFiles() throws Exception {
        // Create a 12MB large file (exceeds 10MB threshold)
        byte[] largeData = createLargeData(12 * 1024 * 1024); // 12MB

        String boundary = "----TestBoundary456";
        byte[] multipartData = createMultipartData(boundary,
            new Part(largeData, "application/octet-stream")
        );

        when(mockRequest.getContentType())
            .thenReturn("multipart/related; boundary=" + boundary);
        when(mockRequest.getInputStream())
            .thenReturn(createServletInputStream(multipartData));

        List<Mime4jHybridParser.MultipartPart> parts = parser.parse(mockRequest);

        assertEquals(1, parts.size());

        // Verify stored on disk
        Mime4jHybridParser.MultipartPart part = parts.get(0);
        assertFalse(part.isInMemory());
        assertNotNull(part.getDiskFile());
        assertTrue(part.getDiskFile().exists());
        assertEquals(largeData.length, part.getSize());

        // Verify data can be read
        try (InputStream is = part.getInputStream()) {
            byte[] readData = readAll(is);
            assertEquals(largeData.length, readData.length);
        }

        // Cleanup
        parser.cleanup(parts);

        // Verify file is deleted
        assertFalse(part.getDiskFile().exists());
    }

    /**
     * Test: mixed size files
     */
    @Test
    public void testParseMixedSizes() throws Exception {
        byte[] smallData = "Small file".getBytes();
        byte[] largeData = createLargeData(15 * 1024 * 1024); // 15MB

        String boundary = "----TestBoundary789";
        byte[] multipartData = createMultipartData(boundary,
            new Part(smallData, "text/plain"),
            new Part(largeData, "application/octet-stream"),
            new Part("Another small".getBytes(), "text/plain")
        );

        when(mockRequest.getContentType())
            .thenReturn("multipart/related; boundary=" + boundary);
        when(mockRequest.getInputStream())
            .thenReturn(createServletInputStream(multipartData));

        List<Mime4jHybridParser.MultipartPart> parts = parser.parse(mockRequest);

        assertEquals(3, parts.size());

        // First: small file, memory
        assertTrue(parts.get(0).isInMemory());

        // Second: large file, disk
        assertFalse(parts.get(1).isInMemory());

        // Third: small file, memory
        assertTrue(parts.get(2).isInMemory());

        parser.cleanup(parts);
    }

    /**
     * Test: reusable input stream
     */
    @Test
    public void testReusableInputStream() throws Exception {
        String content = "Reusable content";
        String boundary = "----TestBoundary";
        byte[] multipartData = createMultipartData(boundary,
            new Part(content.getBytes(), "text/plain")
        );

        when(mockRequest.getContentType())
            .thenReturn("multipart/related; boundary=" + boundary);
        when(mockRequest.getInputStream())
            .thenReturn(createServletInputStream(multipartData));

        List<Mime4jHybridParser.MultipartPart> parts = parser.parse(mockRequest);
        assertEquals(1, parts.size());

        Mime4jHybridParser.MultipartPart part = parts.get(0);

        // First read
        try (InputStream is1 = part.getInputStream()) {
            String read1 = new String(readAll(is1));
            assertEquals(content, read1);
        }

        // Second read - should succeed
        try (InputStream is2 = part.getInputStream()) {
            String read2 = new String(readAll(is2));
            assertEquals(content, read2);
        }

        parser.cleanup(parts);
    }

    // ========== Helper methods ==========

    /**
     * Create multipart data
     * Creates a complete MIME message with headers that Mime4J can parse
     */
    private byte[] createMultipartData(String boundary, Part... parts) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        // Add MIME message headers (required by Mime4J)
        output.write(("Content-Type: multipart/related; boundary=" + boundary + "\r\n").getBytes());
        output.write("\r\n".getBytes());

        for (Part part : parts) {
            // Boundary
            output.write(("--" + boundary + "\r\n").getBytes());

            // Headers
            output.write(("Content-Type: " + part.contentType + "\r\n").getBytes());
            output.write("\r\n".getBytes());

            // Body
            output.write(part.data);
            output.write("\r\n".getBytes());
        }

        // End boundary
        output.write(("--" + boundary + "--\r\n").getBytes());

        return output.toByteArray();
    }

    /**
     * Part data class
     */
    private static class Part {
        final byte[] data;
        final String contentType;

        Part(byte[] data, String contentType) {
            this.data = data;
            this.contentType = contentType;
        }
    }

    /**
     * Create large data block (for testing large files)
     */
    private byte[] createLargeData(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (i % 256);
        }
        return data;
    }

    /**
     * Create ServletInputStream
     */
    private ServletInputStream createServletInputStream(final byte[] data) {
        final ByteArrayInputStream bis = new ByteArrayInputStream(data);
        return new ServletInputStream() {
            @Override
            public int read() {
                return bis.read();
            }

            @Override
            public int read(byte[] b, int off, int len) {
                return bis.read(b, off, len);
            }

            @Override
            public boolean isFinished() {
                return bis.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(javax.servlet.ReadListener readListener) {
                throw new UnsupportedOperationException("setReadListener not supported");
            }
        };
    }

    /**
     * Create empty ServletInputStream
     */
    private ServletInputStream createEmptyInputStream() {
        return createServletInputStream(new byte[0]);
    }

    /**
     * Read all data from InputStream
     */
    private byte[] readAll(InputStream is) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[8192];
        int bytesRead;
        while ((bytesRead = is.read(data)) != -1) {
            buffer.write(data, 0, bytesRead);
        }
        return buffer.toByteArray();
    }
}
