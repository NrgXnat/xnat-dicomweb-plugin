/*
 * XNAT DICOMweb Plugin
 * Copyright (c) 2025 XNATWorks.
 * All rights reserved.
 */

package org.nrg.xnat.dicomweb.service.impl;

import org.junit.Test;
import org.nrg.xnat.dicomweb.service.FailedInstance;
import org.nrg.xnat.dicomweb.service.SuccessfulInstance;

import java.util.*;

import static org.junit.Assert.*;

/**
 * Unit tests for StowRsServiceImpl logic
 *
 * Note: StowRsServiceImpl requires XNAT context for instantiation, so these tests
 * focus on the data model and response grouping logic rather than the service methods directly.
 * Full integration tests are in test-stowrs-suite.sh.
 */
public class StowRsServiceImplTest {

    // ========== extractSessionKey logic tests ==========

    @Test
    public void testSessionKeyExtraction_ValidUri() {
        // Test the session key extraction logic
        String uri = "/prearchive/projects/TestProject/20251208_085152/STS_045";
        String expectedKey = "TestProject/STS_045";

        String result = extractSessionKey(uri);

        assertEquals(expectedKey, result);
    }

    @Test
    public void testSessionKeyExtraction_DifferentTimestamps() {
        // Same project and session but different timestamps should produce same key
        String uri1 = "/prearchive/projects/MyProject/20251208_100000/Patient001";
        String uri2 = "/prearchive/projects/MyProject/20251208_120000/Patient001";

        String key1 = extractSessionKey(uri1);
        String key2 = extractSessionKey(uri2);

        assertEquals("MyProject/Patient001", key1);
        assertEquals(key1, key2);
    }

    @Test
    public void testSessionKeyExtraction_DifferentProjects() {
        String uri1 = "/prearchive/projects/ProjectA/20251208_100000/Session1";
        String uri2 = "/prearchive/projects/ProjectB/20251208_100000/Session1";

        String key1 = extractSessionKey(uri1);
        String key2 = extractSessionKey(uri2);

        assertEquals("ProjectA/Session1", key1);
        assertEquals("ProjectB/Session1", key2);
        assertNotEquals(key1, key2);
    }

    @Test
    public void testSessionKeyExtraction_InvalidUri() {
        // URI with fewer than 6 elements should return the original URI
        String shortUri = "/prearchive/short";
        String result = extractSessionKey(shortUri);

        assertEquals(shortUri, result);
    }

    // ========== Response grouping logic tests ==========

    @Test
    public void testSuccessfulInstanceGroupingByStudy() {
        // Test that instances can be grouped by study UID
        List<SuccessfulInstance> instances = Arrays.asList(
            new SuccessfulInstance("1.2.3", "inst1", "study1", "series1", "url1"),
            new SuccessfulInstance("1.2.3", "inst2", "study1", "series1", "url2"),
            new SuccessfulInstance("1.2.3", "inst3", "study2", "series2", "url3")
        );

        Map<String, List<SuccessfulInstance>> grouped = new HashMap<>();
        for (SuccessfulInstance instance : instances) {
            String studyUid = instance.getStudyInstanceUid();
            grouped.computeIfAbsent(studyUid, k -> new ArrayList<>()).add(instance);
        }

        assertEquals(2, grouped.size());
        assertEquals(2, grouped.get("study1").size());
        assertEquals(1, grouped.get("study2").size());
    }

    @Test
    public void testFailedInstanceGroupingByStudy() {
        // Test that failed instances can be grouped by study UID
        List<FailedInstance> failures = Arrays.asList(
            new FailedInstance(0, "1.2.3", "inst1", "study1", FailedInstance.PROCESSING_FAILURE, "err1"),
            new FailedInstance(1, "1.2.3", "inst2", "study1", FailedInstance.PROCESSING_FAILURE, "err2"),
            new FailedInstance(2, "1.2.3", "inst3", "study2", FailedInstance.PROCESSING_FAILURE, "err3"),
            new FailedInstance(3, FailedInstance.CANNOT_UNDERSTAND, "err4") // No study UID
        );

        Map<String, List<FailedInstance>> grouped = new HashMap<>();
        List<FailedInstance> withoutStudy = new ArrayList<>();

        for (FailedInstance failure : failures) {
            String studyUid = failure.getStudyInstanceUid();
            if (studyUid != null) {
                grouped.computeIfAbsent(studyUid, k -> new ArrayList<>()).add(failure);
            } else {
                withoutStudy.add(failure);
            }
        }

        assertEquals(2, grouped.size());
        assertEquals(2, grouped.get("study1").size());
        assertEquals(1, grouped.get("study2").size());
        assertEquals(1, withoutStudy.size());
    }

    @Test
    public void testMixedSuccessAndFailureGrouping() {
        // Test multi-study response with mixed success and failures
        List<SuccessfulInstance> successes = Arrays.asList(
            new SuccessfulInstance("1.2.3", "s1", "study1", "ser1", "url"),
            new SuccessfulInstance("1.2.3", "s2", "study2", "ser2", "url")
        );

        List<FailedInstance> failures = Arrays.asList(
            new FailedInstance(0, "1.2.3", "f1", "study1", FailedInstance.PROCESSING_FAILURE, "err"),
            new FailedInstance(1, "1.2.3", "f2", "study2", FailedInstance.CANNOT_UNDERSTAND, "err")
        );

        // Get all unique study UIDs
        Set<String> allStudies = new HashSet<>();
        successes.forEach(s -> allStudies.add(s.getStudyInstanceUid()));
        failures.stream()
            .filter(f -> f.getStudyInstanceUid() != null)
            .forEach(f -> allStudies.add(f.getStudyInstanceUid()));

        assertEquals(2, allStudies.size());
        assertTrue(allStudies.contains("study1"));
        assertTrue(allStudies.contains("study2"));
    }

    @Test
    public void testEmptyInstanceLists() {
        List<SuccessfulInstance> successes = Collections.emptyList();
        List<FailedInstance> failures = Collections.emptyList();

        Map<String, List<SuccessfulInstance>> grouped = new HashMap<>();
        for (SuccessfulInstance instance : successes) {
            String studyUid = instance.getStudyInstanceUid();
            grouped.computeIfAbsent(studyUid, k -> new ArrayList<>()).add(instance);
        }

        assertTrue(grouped.isEmpty());
    }

    @Test
    public void testSingleStudyMultipleInstances() {
        // All instances from same study
        List<SuccessfulInstance> instances = Arrays.asList(
            new SuccessfulInstance("1.2.3", "inst1", "study1", "series1", "url1"),
            new SuccessfulInstance("1.2.3", "inst2", "study1", "series1", "url2"),
            new SuccessfulInstance("1.2.3", "inst3", "study1", "series2", "url3"),
            new SuccessfulInstance("1.2.3", "inst4", "study1", "series2", "url4")
        );

        Map<String, List<SuccessfulInstance>> grouped = new HashMap<>();
        for (SuccessfulInstance instance : instances) {
            String studyUid = instance.getStudyInstanceUid();
            grouped.computeIfAbsent(studyUid, k -> new ArrayList<>()).add(instance);
        }

        assertEquals(1, grouped.size());
        assertEquals(4, grouped.get("study1").size());
    }

    // ========== Helper method - mirrors StowRsServiceImpl.extractSessionKey() ==========

    /**
     * Extract session key from prearchive URI.
     * Mirrors the logic in StowRsServiceImpl.extractSessionKey()
     */
    private String extractSessionKey(String sessionUri) {
        // /prearchive/projects/TestProject/20251208_085152/STS_045
        // → TestProject/STS_045 (without timestamp)
        String[] elements = sessionUri.split("/");
        if (elements.length >= 6) {
            return elements[3] + "/" + elements[5];  // project + sessionName
        }
        return sessionUri;
    }
}
