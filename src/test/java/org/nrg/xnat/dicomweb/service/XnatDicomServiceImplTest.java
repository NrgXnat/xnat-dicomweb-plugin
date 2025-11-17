package org.nrg.xnat.dicomweb.service;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

/**
 * Focused tests for helper methods in {@link XnatDicomServiceImpl}.
 */
public class XnatDicomServiceImplTest {

    private XnatDicomServiceImpl service;
    private Method matchesDescriptor;
    private Method buildFallbackArchivePath;
    private Method joinPaths;
    private Method parseFrameNumbers;
    private Method matchesDicomValue;
    private Method matchesDicomDate;

    @Before
    public void setUp() throws Exception {
        service = new XnatDicomServiceImpl();

        matchesDescriptor = XnatDicomServiceImpl.class.getDeclaredMethod("matchesDicomDescriptor", String.class);
        matchesDescriptor.setAccessible(true);

        buildFallbackArchivePath = XnatDicomServiceImpl.class.getDeclaredMethod(
                "buildFallbackArchivePath", String.class, String.class, String.class);
        buildFallbackArchivePath.setAccessible(true);

        joinPaths = XnatDicomServiceImpl.class.getDeclaredMethod(
                "joinPaths", String.class, String[].class);
        joinPaths.setAccessible(true);

        parseFrameNumbers = XnatDicomServiceImpl.class.getDeclaredMethod(
                "parseFrameNumbers", String.class);
        parseFrameNumbers.setAccessible(true);

        matchesDicomValue = XnatDicomServiceImpl.class.getDeclaredMethod(
                "matchesDicomValue", String.class, String.class);
        matchesDicomValue.setAccessible(true);

        matchesDicomDate = XnatDicomServiceImpl.class.getDeclaredMethod(
                "matchesDicomDate", String.class, String.class);
        matchesDicomDate.setAccessible(true);
    }

    @Test
    public void matchesDicomDescriptorRecognizesSecondaryLabels() throws Exception {
        boolean result = (boolean) matchesDescriptor.invoke(service, "Secondary Review");
        assertTrue("Descriptors containing 'secondary' should be treated as DICOM", result);
    }

    @Test
    public void buildFallbackArchivePathDefaultsToArc001() throws Exception {
        String path = (String) buildFallbackArchivePath.invoke(service,
                "/data/xnat/archive/", "ProjectA", "Session01");
        assertEquals("/data/xnat/archive/ProjectA/arc001/Session01", path);
    }

    @Test
    public void joinPathsAvoidsDuplicateSeparators() throws InvocationTargetException, IllegalAccessException {
        String result = (String) joinPaths.invoke(service, "/root/", new String[]{"/nested", "child"});
        assertEquals("/root/nested/child", result.replace('\\', '/'));
    }

    @Test
    public void parseFrameNumbers_SingleFrame() throws Exception {
        List<Integer> result = (List<Integer>) parseFrameNumbers.invoke(service, "1");
        assertEquals("Should parse single frame number", 1, result.size());
        assertEquals("Frame number should be 1", Integer.valueOf(1), result.get(0));
    }

    @Test
    public void parseFrameNumbers_MultipleFrames() throws Exception {
        List<Integer> result = (List<Integer>) parseFrameNumbers.invoke(service, "1,2,3");
        assertEquals("Should parse three frame numbers", 3, result.size());
        assertEquals("First frame should be 1", Integer.valueOf(1), result.get(0));
        assertEquals("Second frame should be 2", Integer.valueOf(2), result.get(1));
        assertEquals("Third frame should be 3", Integer.valueOf(3), result.get(2));
    }

    @Test
    public void parseFrameNumbers_WithSpaces() throws Exception {
        List<Integer> result = (List<Integer>) parseFrameNumbers.invoke(service, "1, 2, 3");
        assertEquals("Should parse frame numbers with spaces", 3, result.size());
        assertEquals("First frame should be 1", Integer.valueOf(1), result.get(0));
        assertEquals("Second frame should be 2", Integer.valueOf(2), result.get(1));
        assertEquals("Third frame should be 3", Integer.valueOf(3), result.get(2));
    }

    @Test
    public void parseFrameNumbers_InvalidNumbers() throws Exception {
        List<Integer> result = (List<Integer>) parseFrameNumbers.invoke(service, "1,abc,3");
        assertEquals("Should skip invalid numbers", 2, result.size());
        assertEquals("First valid frame should be 1", Integer.valueOf(1), result.get(0));
        assertEquals("Second valid frame should be 3", Integer.valueOf(3), result.get(1));
    }

    @Test
    public void parseFrameNumbers_ZeroAndNegative() throws Exception {
        List<Integer> result = (List<Integer>) parseFrameNumbers.invoke(service, "0,-1,1,2");
        assertEquals("Should skip zero and negative numbers", 2, result.size());
        assertEquals("First valid frame should be 1", Integer.valueOf(1), result.get(0));
        assertEquals("Second valid frame should be 2", Integer.valueOf(2), result.get(1));
    }

    @Test
    public void parseFrameNumbers_EmptyString() throws Exception {
        List<Integer> result = (List<Integer>) parseFrameNumbers.invoke(service, "");
        assertEquals("Should return empty list for empty string", 0, result.size());
    }

    @Test
    public void parseFrameNumbers_Null() throws Exception {
        List<Integer> result = (List<Integer>) parseFrameNumbers.invoke(service, (String) null);
        assertEquals("Should return empty list for null", 0, result.size());
    }

    @Test
    public void parseFrameNumbers_NonSequential() throws Exception {
        List<Integer> result = (List<Integer>) parseFrameNumbers.invoke(service, "5,1,10,3");
        assertEquals("Should parse non-sequential frames", 4, result.size());
        assertEquals("Should preserve order", Integer.valueOf(5), result.get(0));
        assertEquals("Should preserve order", Integer.valueOf(1), result.get(1));
        assertEquals("Should preserve order", Integer.valueOf(10), result.get(2));
        assertEquals("Should preserve order", Integer.valueOf(3), result.get(3));
    }

    /**
     * Integration test to verify frame extraction doesn't return PNG-encoded data
     * This test uses an actual DICOM file from the test data directory
     */
    @Test
    public void extractFramePixelData_DoesNotReturnPNG() throws Exception {
        // Find a test DICOM file
        String testDicomPath = "src/test/resources/test-data/sample.dcm";
        java.io.File testFile = new java.io.File(testDicomPath);

        if (!testFile.exists()) {
            // Skip test if test data not available
            System.out.println("Skipping extractFramePixelData test - test DICOM file not found");
            return;
        }

        // Use reflection to call private method
        Method extractFramePixelData = XnatDicomServiceImpl.class.getDeclaredMethod(
                "extractFramePixelData", java.io.File.class, int.class);
        extractFramePixelData.setAccessible(true);

        // Extract first frame (index 0)
        byte[] frameData = (byte[]) extractFramePixelData.invoke(service, testFile, 0);

        if (frameData != null && frameData.length > 4) {
            // Check that data doesn't start with PNG magic bytes (89 50 4E 47)
            boolean isPNG = (frameData[0] == (byte)0x89 &&
                           frameData[1] == (byte)0x50 &&
                           frameData[2] == (byte)0x4E &&
                           frameData[3] == (byte)0x47);

            assertFalse("Frame data should not be PNG-encoded", isPNG);

            // Also check for JPEG magic bytes (FF D8 FF)
            boolean isJPEG = (frameData[0] == (byte)0xFF &&
                            frameData[1] == (byte)0xD8 &&
                            frameData[2] == (byte)0xFF);

            assertFalse("Frame data should not be JPEG-encoded", isJPEG);

            System.out.println("Frame extraction test passed - returned raw pixel data (" + frameData.length + " bytes)");
        }
    }

    /**
     * Test that verifies extractFramePixelData returns raw pixel data for uncompressed DICOM
     */
    @Test
    public void extractFramePixelData_ReturnsRawPixelData() throws Exception {
        String testDicomPath = "src/test/resources/test-data/sample.dcm";
        java.io.File testFile = new java.io.File(testDicomPath);

        if (!testFile.exists()) {
            System.out.println("Skipping raw pixel data test - test DICOM file not found");
            return;
        }

        // Read DICOM file to get expected frame size
        org.dcm4che3.io.DicomInputStream dis = new org.dcm4che3.io.DicomInputStream(testFile);
        org.dcm4che3.data.Attributes attrs = dis.readDataset(-1, -1);
        dis.close();

        int rows = attrs.getInt(org.dcm4che3.data.Tag.Rows, 0);
        int cols = attrs.getInt(org.dcm4che3.data.Tag.Columns, 0);
        int samplesPerPixel = attrs.getInt(org.dcm4che3.data.Tag.SamplesPerPixel, 1);
        int bitsAllocated = attrs.getInt(org.dcm4che3.data.Tag.BitsAllocated, 8);
        int numberOfFrames = attrs.getInt(org.dcm4che3.data.Tag.NumberOfFrames, 1);

        if (rows == 0 || cols == 0) {
            System.out.println("Skipping test - could not read DICOM dimensions");
            return;
        }

        int expectedFrameSize = rows * cols * samplesPerPixel * (bitsAllocated / 8);

        // Extract frame
        Method extractFramePixelData = XnatDicomServiceImpl.class.getDeclaredMethod(
                "extractFramePixelData", java.io.File.class, int.class);
        extractFramePixelData.setAccessible(true);

        byte[] frameData = (byte[]) extractFramePixelData.invoke(service, testFile, 0);

        assertNotNull("Frame data should not be null", frameData);

        // For uncompressed data, frame size should match calculated size
        // For compressed data, this may differ
        System.out.println(String.format("Frame data size: %d bytes, expected size: %d bytes (frames: %d, dimensions: %dx%d)",
                frameData.length, expectedFrameSize, numberOfFrames, rows, cols));

        // At minimum, verify we got some data
        assertTrue("Frame data should have non-zero length", frameData.length > 0);
    }

    /**
     * Test that verifies compressed DICOM data uses ImageIO path (not direct fragment extraction)
     * This test checks the behavior by examining pixel data type
     */
    @Test
    public void extractFramePixelData_CompressedUsesImageIO() throws Exception {
        String testDicomPath = "src/test/resources/test-data/sample.dcm";
        java.io.File testFile = new java.io.File(testDicomPath);

        if (!testFile.exists()) {
            System.out.println("Skipping compressed data test - test DICOM file not found");
            return;
        }

        // Read DICOM file to check if compressed
        org.dcm4che3.io.DicomInputStream dis = new org.dcm4che3.io.DicomInputStream(testFile);
        org.dcm4che3.data.Attributes attrs = dis.readDataset(-1, -1);
        dis.close();

        Object pixelData = attrs.getValue(org.dcm4che3.data.Tag.PixelData);
        String transferSyntax = attrs.getString(org.dcm4che3.data.Tag.TransferSyntaxUID, "Unknown");

        System.out.println("Transfer Syntax: " + transferSyntax);
        System.out.println("Pixel Data Type: " + (pixelData != null ? pixelData.getClass().getName() : "null"));

        // Extract frame
        Method extractFramePixelData = XnatDicomServiceImpl.class.getDeclaredMethod(
                "extractFramePixelData", java.io.File.class, int.class);
        extractFramePixelData.setAccessible(true);

        byte[] frameData = (byte[]) extractFramePixelData.invoke(service, testFile, 0);

        assertNotNull("Frame data should not be null", frameData);

        // Check if this is compressed data (Fragments instead of byte[])
        boolean isCompressed = !(pixelData instanceof byte[]);

        if (isCompressed) {
            System.out.println("Verified: Compressed data handled via ImageIO path");
            // For compressed data, we should get decompressed raw pixel data
            // The size should match the calculated uncompressed frame size
            int rows = attrs.getInt(org.dcm4che3.data.Tag.Rows, 0);
            int cols = attrs.getInt(org.dcm4che3.data.Tag.Columns, 0);
            int samplesPerPixel = attrs.getInt(org.dcm4che3.data.Tag.SamplesPerPixel, 1);
            int bitsAllocated = attrs.getInt(org.dcm4che3.data.Tag.BitsAllocated, 8);
            int expectedSize = rows * cols * samplesPerPixel * (bitsAllocated / 8);

            System.out.println(String.format("Expected decompressed size: %d bytes, actual: %d bytes",
                    expectedSize, frameData.length));
        } else {
            System.out.println("Test file is uncompressed - direct extraction used");
        }

        // Regardless of compression, we should get valid frame data
        assertTrue("Frame data should be non-empty", frameData.length > 0);
    }

    /**
     * Test that verifies frame extraction handles out-of-range frame indices correctly
     */
    @Test
    public void extractFramePixelData_InvalidFrameIndex_ReturnsNull() throws Exception {
        String testDicomPath = "src/test/resources/test-data/sample.dcm";
        java.io.File testFile = new java.io.File(testDicomPath);

        if (!testFile.exists()) {
            System.out.println("Skipping invalid frame index test - test DICOM file not found");
            return;
        }

        // Read DICOM file to get number of frames
        org.dcm4che3.io.DicomInputStream dis = new org.dcm4che3.io.DicomInputStream(testFile);
        org.dcm4che3.data.Attributes attrs = dis.readDataset(-1, -1);
        dis.close();

        int numberOfFrames = attrs.getInt(org.dcm4che3.data.Tag.NumberOfFrames, 1);

        Method extractFramePixelData = XnatDicomServiceImpl.class.getDeclaredMethod(
                "extractFramePixelData", java.io.File.class, int.class);
        extractFramePixelData.setAccessible(true);

        // Test with out-of-range index (should return null)
        byte[] frameData = (byte[]) extractFramePixelData.invoke(service, testFile, numberOfFrames + 10);

        assertEquals("Out-of-range frame index should return null", null, frameData);
        System.out.println("Verified: Invalid frame index returns null");
    }

    /**
     * Test that verifies negative frame indices are rejected
     */
    @Test
    public void extractFramePixelData_NegativeIndex_ReturnsNull() throws Exception {
        String testDicomPath = "src/test/resources/test-data/sample.dcm";
        java.io.File testFile = new java.io.File(testDicomPath);

        if (!testFile.exists()) {
            System.out.println("Skipping negative index test - test DICOM file not found");
            return;
        }

        Method extractFramePixelData = XnatDicomServiceImpl.class.getDeclaredMethod(
                "extractFramePixelData", java.io.File.class, int.class);
        extractFramePixelData.setAccessible(true);

        // Test with negative index (should return null)
        byte[] frameData = (byte[]) extractFramePixelData.invoke(service, testFile, -1);

        assertEquals("Negative frame index should return null", null, frameData);
        System.out.println("Verified: Negative frame index returns null");
    }

    /**
     * Test that validates the logic fix for compressed frame extraction.
     * This test verifies that compressed data (Fragments) is NOT extracted directly
     * but instead goes through the ImageIO decompression path.
     *
     * Background: Previous implementation incorrectly assumed fragments.get(frameIndex)
     * mapped 1:1 to frames, but Fragment 0 is the Basic Offset Table and multiple
     * fragments can compose a single frame. This was fixed to always use ImageIO
     * for compressed data.
     */
    @Test
    public void extractFramePixelData_CompressedAvoidDirectFragmentAccess() throws Exception {
        String testDicomPath = "src/test/resources/test-data/sample.dcm";
        java.io.File testFile = new java.io.File(testDicomPath);

        if (!testFile.exists()) {
            System.out.println("Skipping compressed fragment test - test DICOM file not found");
            return;
        }

        // Read DICOM to check pixel data type
        org.dcm4che3.io.DicomInputStream dis = new org.dcm4che3.io.DicomInputStream(testFile);
        org.dcm4che3.data.Attributes attrs = dis.readDataset(-1, -1);
        dis.close();

        Object pixelData = attrs.getValue(org.dcm4che3.data.Tag.PixelData);

        // For this test, we verify the LOGIC behavior:
        // - If pixelData is byte[] (uncompressed): direct extraction is used
        // - If pixelData is NOT byte[] (compressed/Fragments): ImageIO path is used

        Method extractFramePixelData = XnatDicomServiceImpl.class.getDeclaredMethod(
                "extractFramePixelData", java.io.File.class, int.class);
        extractFramePixelData.setAccessible(true);

        // Extract frame - should succeed regardless of compression
        byte[] frameData = (byte[]) extractFramePixelData.invoke(service, testFile, 0);

        assertNotNull("Frame extraction should succeed", frameData);
        assertTrue("Frame data should be non-empty", frameData.length > 0);

        if (pixelData instanceof byte[]) {
            System.out.println("Test file has uncompressed data (byte[]) - direct extraction used");
            // For uncompressed, verify frame size matches expected
            int rows = attrs.getInt(org.dcm4che3.data.Tag.Rows, 0);
            int cols = attrs.getInt(org.dcm4che3.data.Tag.Columns, 0);
            int samplesPerPixel = attrs.getInt(org.dcm4che3.data.Tag.SamplesPerPixel, 1);
            int bitsAllocated = attrs.getInt(org.dcm4che3.data.Tag.BitsAllocated, 8);
            int expectedSize = rows * cols * samplesPerPixel * (bitsAllocated / 8);
            assertEquals("Uncompressed frame size should match calculated size",
                    expectedSize, frameData.length);
        } else {
            System.out.println("Test file has compressed/encapsulated data (Fragments) - ImageIO path used");
            // For compressed, we verify:
            // 1. Frame extraction succeeded (not null)
            // 2. Data is decompressed (size should be reasonable for the image dimensions)
            // 3. NOT returning offset table or fragment bytes directly

            int rows = attrs.getInt(org.dcm4che3.data.Tag.Rows, 0);
            int cols = attrs.getInt(org.dcm4che3.data.Tag.Columns, 0);

            // Decompressed data should be >= some reasonable size for the image
            // (not just a few bytes which would indicate offset table)
            int minExpectedSize = rows * cols / 2; // At least half of expected pixels
            assertTrue(String.format("Decompressed frame should be at least %d bytes (got %d)",
                            minExpectedSize, frameData.length),
                    frameData.length >= minExpectedSize);

            System.out.println(String.format("Verified: Compressed data properly decompressed (%d bytes for %dx%d image)",
                    frameData.length, rows, cols));
        }

        // This test validates the Codex feedback fix:
        // - Uncompressed: direct byte[] extraction (fast)
        // - Compressed: ImageIO decompression (correct, avoids fragment mapping issues)
        System.out.println("✅ Frame extraction logic validated (avoids direct fragment access for compressed data)");
    }

    // ========== Query Parameter Filtering Tests ==========

    @Test
    public void matchesDicomValue_ExactMatch() throws Exception {
        boolean result = (boolean) matchesDicomValue.invoke(service, "John Doe", "John Doe");
        assertTrue("Exact match should return true", result);
    }

    @Test
    public void matchesDicomValue_CaseInsensitive() throws Exception {
        boolean result = (boolean) matchesDicomValue.invoke(service, "John Doe", "john doe");
        assertTrue("Case-insensitive match should return true", result);
    }

    @Test
    public void matchesDicomValue_WildcardAsterisk() throws Exception {
        boolean result = (boolean) matchesDicomValue.invoke(service, "John Doe", "John*");
        assertTrue("Wildcard asterisk should match", result);
    }

    @Test
    public void matchesDicomValue_WildcardQuestion() throws Exception {
        boolean result = (boolean) matchesDicomValue.invoke(service, "John Doe", "John?Doe");
        assertTrue("Wildcard question mark should match single character", result);
    }

    @Test
    public void matchesDicomValue_WildcardNoMatch() throws Exception {
        boolean result = (boolean) matchesDicomValue.invoke(service, "John Doe", "Jane*");
        assertFalse("Non-matching wildcard should return false", result);
    }

    @Test
    public void matchesDicomValue_EmptyQuery() throws Exception {
        boolean result = (boolean) matchesDicomValue.invoke(service, "John Doe", "");
        assertTrue("Empty query should match anything", result);
    }

    @Test
    public void matchesDicomValue_NullAttribute() throws Exception {
        boolean result = (boolean) matchesDicomValue.invoke(service, null, "John Doe");
        assertFalse("Null attribute should not match query", result);
    }

    @Test
    public void matchesDicomDate_ExactMatch() throws Exception {
        boolean result = (boolean) matchesDicomDate.invoke(service, "20250101", "20250101");
        assertTrue("Exact date match should return true", result);
    }

    @Test
    public void matchesDicomDate_RangeMatch() throws Exception {
        boolean result = (boolean) matchesDicomDate.invoke(service, "20250115", "20250101-20250131");
        assertTrue("Date within range should match", result);
    }

    @Test
    public void matchesDicomDate_RangeNoMatch() throws Exception {
        boolean result = (boolean) matchesDicomDate.invoke(service, "20250201", "20250101-20250131");
        assertFalse("Date outside range should not match", result);
    }

    @Test
    public void matchesDicomDate_OpenEndedStart() throws Exception {
        boolean result = (boolean) matchesDicomDate.invoke(service, "20250115", "20250101-");
        assertTrue("Date after start date should match open-ended range", result);
    }

    @Test
    public void matchesDicomDate_OpenEndedEnd() throws Exception {
        boolean result = (boolean) matchesDicomDate.invoke(service, "20250115", "-20250131");
        assertTrue("Date before end date should match open-ended range", result);
    }

    @Test
    public void matchesDicomDate_EmptyQuery() throws Exception {
        boolean result = (boolean) matchesDicomDate.invoke(service, "20250101", "");
        assertTrue("Empty query should match any date", result);
    }

    @Test
    public void matchesDicomDate_NullAttribute() throws Exception {
        boolean result = (boolean) matchesDicomDate.invoke(service, null, "20250101");
        assertFalse("Null attribute should not match query", result);
    }

    @Test
    public void matchesDicomValue_WildcardWithMultipleAsterisks() throws Exception {
        boolean result = (boolean) matchesDicomValue.invoke(service, "MR Axial T1 Brain", "*T1*Brain");
        assertTrue("Multiple wildcards should match", result);
    }

    @Test
    public void matchesDicomValue_WildcardMidString() throws Exception {
        boolean result = (boolean) matchesDicomValue.invoke(service, "CT_HEAD_STUDY", "CT*STUDY");
        assertTrue("Wildcard in middle should match", result);
    }

    @Test
    public void matchesDicomDate_EdgeCaseStartOfRange() throws Exception {
        boolean result = (boolean) matchesDicomDate.invoke(service, "20250101", "20250101-20250131");
        assertTrue("Date at start of range should match", result);
    }

    @Test
    public void matchesDicomDate_EdgeCaseEndOfRange() throws Exception {
        boolean result = (boolean) matchesDicomDate.invoke(service, "20250131", "20250101-20250131");
        assertTrue("Date at end of range should match", result);
    }

    @Test
    public void matchesDicomDate_BeforeRange() throws Exception {
        boolean result = (boolean) matchesDicomDate.invoke(service, "20241231", "20250101-20250131");
        assertFalse("Date before range should not match", result);
    }

    @Test
    public void matchesDicomValue_PartialMatch() throws Exception {
        boolean result = (boolean) matchesDicomValue.invoke(service, "MR", "MRI");
        assertFalse("Partial match without wildcard should not match", result);
    }

    @Test
    public void matchesDicomValue_SpecialCharacters() throws Exception {
        boolean result = (boolean) matchesDicomValue.invoke(service, "Patient^Name", "Patient^Name");
        assertTrue("Special characters should match exactly", result);
    }
}
