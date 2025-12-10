/*
 * XNAT DICOMweb Proxy Plugin
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 */

package org.nrg.xnat.dicomweb.service;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for FailedInstance data class
 */
public class FailedInstanceTest {

    private static final String SOP_CLASS_UID = "1.2.840.10008.5.1.4.1.1.2";
    private static final String SOP_INSTANCE_UID = "1.2.3.4.5.6.7.8.9";
    private static final String STUDY_UID = "1.2.3.4.5";

    @Test
    public void testFailureReasonConstants() {
        // Verify DICOM standard failure reason codes
        assertEquals(0x0110, FailedInstance.PROCESSING_FAILURE);
        assertEquals(0x0111, FailedInstance.NO_SUCH_OBJECT_INSTANCE);
        assertEquals(0x0117, FailedInstance.INVALID_OBJECT_INSTANCE);
        assertEquals(0xA700, FailedInstance.OUT_OF_RESOURCES);
        assertEquals(0xA900, FailedInstance.DATA_SET_DOES_NOT_MATCH_SOP_CLASS);
        assertEquals(0xC000, FailedInstance.CANNOT_UNDERSTAND);
    }

    @Test
    public void testFullConstructor() {
        FailedInstance instance = new FailedInstance(
            0, SOP_CLASS_UID, SOP_INSTANCE_UID, STUDY_UID,
            FailedInstance.PROCESSING_FAILURE, "Test error");

        assertEquals(0, instance.getInstanceIndex());
        assertEquals(SOP_CLASS_UID, instance.getSopClassUid());
        assertEquals(SOP_INSTANCE_UID, instance.getSopInstanceUid());
        assertEquals(STUDY_UID, instance.getStudyInstanceUid());
        assertEquals(FailedInstance.PROCESSING_FAILURE, instance.getFailureReason());
        assertEquals("Test error", instance.getErrorMessage());
        assertTrue(instance.hasSopUids());
    }

    @Test
    public void testConstructorWithoutStudyUid() {
        FailedInstance instance = new FailedInstance(
            1, SOP_CLASS_UID, SOP_INSTANCE_UID,
            FailedInstance.DATA_SET_DOES_NOT_MATCH_SOP_CLASS, "Invalid SOP class");

        assertEquals(1, instance.getInstanceIndex());
        assertEquals(SOP_CLASS_UID, instance.getSopClassUid());
        assertEquals(SOP_INSTANCE_UID, instance.getSopInstanceUid());
        assertNull(instance.getStudyInstanceUid());
        assertEquals(FailedInstance.DATA_SET_DOES_NOT_MATCH_SOP_CLASS, instance.getFailureReason());
        assertTrue(instance.hasSopUids());
    }

    @Test
    public void testMinimalConstructor() {
        FailedInstance instance = new FailedInstance(
            2, FailedInstance.CANNOT_UNDERSTAND, "Not a DICOM file");

        assertEquals(2, instance.getInstanceIndex());
        assertNull(instance.getSopClassUid());
        assertNull(instance.getSopInstanceUid());
        assertNull(instance.getStudyInstanceUid());
        assertEquals(FailedInstance.CANNOT_UNDERSTAND, instance.getFailureReason());
        assertEquals("Not a DICOM file", instance.getErrorMessage());
        assertFalse(instance.hasSopUids());
    }

    @Test
    public void testProcessingFailureFactory() {
        FailedInstance instance = FailedInstance.processingFailure(3, "Processing error");

        assertEquals(3, instance.getInstanceIndex());
        assertEquals(FailedInstance.PROCESSING_FAILURE, instance.getFailureReason());
        assertEquals("Processing error", instance.getErrorMessage());
        assertFalse(instance.hasSopUids());
    }

    @Test
    public void testCannotUnderstandFactory() {
        FailedInstance instance = FailedInstance.cannotUnderstand(4, "Unknown format");

        assertEquals(4, instance.getInstanceIndex());
        assertEquals(FailedInstance.CANNOT_UNDERSTAND, instance.getFailureReason());
        assertEquals("Unknown format", instance.getErrorMessage());
        assertFalse(instance.hasSopUids());
    }

    @Test
    public void testHasSopUidsWithPartialNull() {
        // Only sopClassUid set
        FailedInstance instance1 = new FailedInstance(
            0, SOP_CLASS_UID, null, FailedInstance.PROCESSING_FAILURE, "error");
        assertFalse(instance1.hasSopUids());

        // Only sopInstanceUid set
        FailedInstance instance2 = new FailedInstance(
            0, null, SOP_INSTANCE_UID, FailedInstance.PROCESSING_FAILURE, "error");
        assertFalse(instance2.hasSopUids());
    }

    @Test
    public void testToString() {
        FailedInstance instance = new FailedInstance(
            5, FailedInstance.PROCESSING_FAILURE, "Test message");

        String str = instance.toString();
        assertTrue(str.contains("FailedInstance"));
        assertTrue(str.contains("index=5"));
        assertTrue(str.contains("0x0110")); // hex format
        assertTrue(str.contains("Test message"));
    }

    @Test
    public void testDifferentStudyUids() {
        // Test grouping capability - failures from different studies
        FailedInstance instance1 = new FailedInstance(
            0, SOP_CLASS_UID, "1.1.1", "study1",
            FailedInstance.PROCESSING_FAILURE, "error1");
        FailedInstance instance2 = new FailedInstance(
            1, SOP_CLASS_UID, "1.1.2", "study2",
            FailedInstance.PROCESSING_FAILURE, "error2");

        assertNotEquals(instance1.getStudyInstanceUid(), instance2.getStudyInstanceUid());
    }

    @Test
    public void testAllFailureReasonCodesAreValid() {
        // Verify all codes are positive integers within expected range
        assertTrue(FailedInstance.PROCESSING_FAILURE > 0);
        assertTrue(FailedInstance.NO_SUCH_OBJECT_INSTANCE > 0);
        assertTrue(FailedInstance.INVALID_OBJECT_INSTANCE > 0);
        assertTrue(FailedInstance.OUT_OF_RESOURCES > 0);
        assertTrue(FailedInstance.DATA_SET_DOES_NOT_MATCH_SOP_CLASS > 0);
        assertTrue(FailedInstance.CANNOT_UNDERSTAND > 0);

        // All codes should fit in unsigned short (0x0000 - 0xFFFF)
        assertTrue(FailedInstance.PROCESSING_FAILURE <= 0xFFFF);
        assertTrue(FailedInstance.CANNOT_UNDERSTAND <= 0xFFFF);
    }
}
