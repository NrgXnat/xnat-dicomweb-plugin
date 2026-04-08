/*
 * XNAT DICOMweb Plugin
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 */

package org.nrg.xnat.dicomweb.service;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for SuccessfulInstance data class
 */
public class SuccessfulInstanceTest {

    private static final String SOP_CLASS_UID = "1.2.840.10008.5.1.4.1.1.2";
    private static final String SOP_INSTANCE_UID = "1.2.3.4.5.6.7.8.9";
    private static final String STUDY_UID = "1.2.3.4.5";
    private static final String SERIES_UID = "1.2.3.4.5.6";
    private static final String RETRIEVE_URL = "http://localhost:8080/xapi/dicomweb/projects/Test/studies/1.2.3.4.5";

    @Test
    public void testBasicConstructor() {
        SuccessfulInstance instance = new SuccessfulInstance(SOP_CLASS_UID, SOP_INSTANCE_UID, RETRIEVE_URL);

        assertEquals(SOP_CLASS_UID, instance.getSopClassUid());
        assertEquals(SOP_INSTANCE_UID, instance.getSopInstanceUid());
        assertEquals(RETRIEVE_URL, instance.getRetrieveUrl());
        assertNull(instance.getStudyInstanceUid());
        assertNull(instance.getSeriesInstanceUid());
    }

    @Test
    public void testFullConstructor() {
        SuccessfulInstance instance = new SuccessfulInstance(
            SOP_CLASS_UID, SOP_INSTANCE_UID, STUDY_UID, SERIES_UID, RETRIEVE_URL);

        assertEquals(SOP_CLASS_UID, instance.getSopClassUid());
        assertEquals(SOP_INSTANCE_UID, instance.getSopInstanceUid());
        assertEquals(STUDY_UID, instance.getStudyInstanceUid());
        assertEquals(SERIES_UID, instance.getSeriesInstanceUid());
        assertEquals(RETRIEVE_URL, instance.getRetrieveUrl());
    }

    @Test
    public void testNullValues() {
        SuccessfulInstance instance = new SuccessfulInstance(null, null, null, null, null);

        assertNull(instance.getSopClassUid());
        assertNull(instance.getSopInstanceUid());
        assertNull(instance.getStudyInstanceUid());
        assertNull(instance.getSeriesInstanceUid());
        assertNull(instance.getRetrieveUrl());
    }

    @Test
    public void testToString() {
        SuccessfulInstance instance = new SuccessfulInstance(
            SOP_CLASS_UID, SOP_INSTANCE_UID, STUDY_UID, SERIES_UID, RETRIEVE_URL);

        String str = instance.toString();
        assertTrue(str.contains("SuccessfulInstance"));
        assertTrue(str.contains(SOP_CLASS_UID));
        assertTrue(str.contains(SOP_INSTANCE_UID));
        assertTrue(str.contains(STUDY_UID));
        assertTrue(str.contains(SERIES_UID));
    }

    @Test
    public void testDifferentStudyUids() {
        // Test grouping capability - instances from different studies
        SuccessfulInstance instance1 = new SuccessfulInstance(
            SOP_CLASS_UID, "1.1.1", "study1", "series1", RETRIEVE_URL);
        SuccessfulInstance instance2 = new SuccessfulInstance(
            SOP_CLASS_UID, "1.1.2", "study2", "series2", RETRIEVE_URL);

        assertNotEquals(instance1.getStudyInstanceUid(), instance2.getStudyInstanceUid());
    }
}
