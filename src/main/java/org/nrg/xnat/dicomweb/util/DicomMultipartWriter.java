package org.nrg.xnat.dicomweb.util;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Writes a multipart/related response incrementally to the given {@link OutputStream},
 * flushing after each part so the client begins receiving bytes immediately and per-part
 * writes keep its socket-read timer fresh. Replaces the previous pattern of buffering
 * every part into a {@link java.io.ByteArrayOutputStream} before transmitting.
 *
 * <p>One DICOM file's worth of bytes is held at a time. The caller is responsible for
 * opening and closing each instance's {@link InputStream}.
 *
 * <p>Not thread-safe. Designed for single-threaded use inside a Spring
 * {@code StreamingResponseBody} or direct {@code HttpServletResponse} write loop.
 */
public class DicomMultipartWriter implements Closeable {
    private static final int BUFFER_SIZE = 8192;
    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.US_ASCII);

    private final OutputStream out;
    private final String boundary;
    private final String partContentType;
    private boolean epilogueWritten = false;

    public DicomMultipartWriter(final OutputStream out, final String boundary, final String partContentType) {
        this.out = out;
        this.boundary = boundary;
        this.partContentType = partContentType;
    }

    /**
     * Write one multipart part: boundary line, Content-Type header, blank line, then the
     * payload copied from {@code in}, then a CRLF. Flushes the underlying stream so the
     * bytes hit the client immediately.
     *
     * <p>The caller owns {@code in}; this method does not close it.
     */
    public void writePart(final InputStream in) throws IOException {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(("Content-Type: " + partContentType + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        final byte[] buffer = new byte[BUFFER_SIZE];
        int n;
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
        }
        out.write(CRLF);
        out.flush();
    }

    /**
     * Write the closing boundary line. Idempotent; safe to call from {@link #close()}.
     */
    public void writeEpilogue() throws IOException {
        if (!epilogueWritten) {
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            epilogueWritten = true;
        }
    }

    @Override
    public void close() throws IOException {
        // Intentionally does not close `out`: the wrapped OutputStream is owned by Spring
        // (HttpServletResponse#getOutputStream) and closing it here would break the servlet
        // container's response lifecycle.
        writeEpilogue();
    }
}
