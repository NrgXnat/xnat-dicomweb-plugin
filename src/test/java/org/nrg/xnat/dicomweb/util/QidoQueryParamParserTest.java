/*
 * XNAT DICOMweb Plugin
 * Copyright (c) 2026 XNATWorks.
 * All rights reserved.
 */

package org.nrg.xnat.dicomweb.util;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link QidoQueryParamParser}, covering the DICOM
 * keyword and 8-hex-digit tag forms of QIDO-RS {@code {attributeID}}
 * (PS3.18 &sect;6.7.1.1) plus the tolerated DIMSE-style spellings
 * used by some clients.
 */
public class QidoQueryParamParserTest {

    @Test
    public void nullMapReturnsEmptyAttrs() {
        Attributes attrs = QidoQueryParamParser.parse(null);
        assertTrue(attrs.isEmpty());
    }

    @Test
    public void emptyMapReturnsEmptyAttrs() {
        Attributes attrs = QidoQueryParamParser.parse(Collections.<String, String>emptyMap());
        assertTrue(attrs.isEmpty());
    }

    // ---- Keyword form ----

    @Test
    public void keywordFormMatchesStudyDate() {
        Map<String, String> q = new HashMap<>();
        q.put("StudyDate", "20200101-20201231");
        Attributes attrs = QidoQueryParamParser.parse(q);
        assertEquals("20200101-20201231", attrs.getString(Tag.StudyDate));
    }

    @Test
    public void keywordFormIsCaseInsensitive() {
        Map<String, String> q = new HashMap<>();
        q.put("studydate", "20200101");
        Attributes attrs = QidoQueryParamParser.parse(q);
        assertEquals("20200101", attrs.getString(Tag.StudyDate));
    }

    @Test
    public void keywordFormMixedCase() {
        Map<String, String> q = new HashMap<>();
        q.put("PATIENTNAME", "Doe^John");
        q.put("PatientId", "P001");
        Attributes attrs = QidoQueryParamParser.parse(q);
        assertEquals("Doe^John", attrs.getString(Tag.PatientName));
        assertEquals("P001", attrs.getString(Tag.PatientID));
    }

    // ---- Hex tag-number form (the fix) ----

    @Test
    public void hexTagFormMatchesStudyDate() {
        Map<String, String> q = new HashMap<>();
        q.put("00080020", "20200101-20201231");
        Attributes attrs = QidoQueryParamParser.parse(q);
        assertEquals("20200101-20201231", attrs.getString(Tag.StudyDate));
    }

    @Test
    public void hexTagFormMatchesStudyTime() {
        Map<String, String> q = new HashMap<>();
        q.put("00080030", "080000-170000");
        Attributes attrs = QidoQueryParamParser.parse(q);
        assertEquals("080000-170000", attrs.getString(Tag.StudyTime));
    }

    @Test
    public void hexTagFormIsCaseInsensitive() {
        // Studies with sequence tags include hex letters; verify upper
        // and lower case both work.
        Map<String, String> upper = new HashMap<>();
        upper.put("0008103E", "AX T1");
        assertEquals("AX T1",
                QidoQueryParamParser.parse(upper).getString(Tag.SeriesDescription));

        Map<String, String> lower = new HashMap<>();
        lower.put("0008103e", "AX T1");
        assertEquals("AX T1",
                QidoQueryParamParser.parse(lower).getString(Tag.SeriesDescription));
    }

    @Test
    public void parenthesizedTagFormTolerated() {
        Map<String, String> q = new HashMap<>();
        q.put("(0008,0020)", "20200101-");
        Attributes attrs = QidoQueryParamParser.parse(q);
        assertEquals("20200101-", attrs.getString(Tag.StudyDate));
    }

    @Test
    public void commaSeparatedTagFormTolerated() {
        Map<String, String> q = new HashMap<>();
        q.put("0008,0020", "-20201231");
        Attributes attrs = QidoQueryParamParser.parse(q);
        assertEquals("-20201231", attrs.getString(Tag.StudyDate));
    }

    // ---- Full parameter surface ----

    @Test
    public void allSupportedKeywordsRoundTrip() {
        Map<String, String> q = new HashMap<>();
        q.put("PatientName",       "Doe^John");
        q.put("PatientID",         "P001");
        q.put("StudyDate",         "20200101");
        q.put("StudyTime",         "120000");
        q.put("StudyInstanceUID",  "1.2.3");
        q.put("AccessionNumber",   "A100");
        q.put("Modality",          "MR");
        q.put("SeriesDescription", "AX T1");
        q.put("SeriesInstanceUID", "1.2.3.4");
        q.put("SeriesNumber",      "3");
        q.put("SOPInstanceUID",    "1.2.3.4.5");
        q.put("SOPClassUID",       "1.2.840.10008.5.1.4.1.1.4");
        q.put("InstanceNumber",    "7");

        Attributes attrs = QidoQueryParamParser.parse(q);

        assertEquals("Doe^John",                    attrs.getString(Tag.PatientName));
        assertEquals("P001",                        attrs.getString(Tag.PatientID));
        assertEquals("20200101",                    attrs.getString(Tag.StudyDate));
        assertEquals("120000",                      attrs.getString(Tag.StudyTime));
        assertEquals("1.2.3",                       attrs.getString(Tag.StudyInstanceUID));
        assertEquals("A100",                        attrs.getString(Tag.AccessionNumber));
        assertEquals("MR",                          attrs.getString(Tag.Modality));
        assertEquals("AX T1",                       attrs.getString(Tag.SeriesDescription));
        assertEquals("1.2.3.4",                     attrs.getString(Tag.SeriesInstanceUID));
        assertEquals("3",                           attrs.getString(Tag.SeriesNumber));
        assertEquals("1.2.3.4.5",                   attrs.getString(Tag.SOPInstanceUID));
        assertEquals("1.2.840.10008.5.1.4.1.1.4",   attrs.getString(Tag.SOPClassUID));
        assertEquals("7",                           attrs.getString(Tag.InstanceNumber));
    }

    // ---- Aliasing: ModalitiesInStudy → Modality ----

    @Test
    public void modalitiesInStudyKeywordAliasesModality() {
        Map<String, String> q = new HashMap<>();
        q.put("ModalitiesInStudy", "CT");
        Attributes attrs = QidoQueryParamParser.parse(q);
        assertEquals("CT", attrs.getString(Tag.Modality));
        // Alias, not a separate value:
        assertNull(attrs.getString(Tag.ModalitiesInStudy));
    }

    @Test
    public void modalitiesInStudyHexAliasesModality() {
        Map<String, String> q = new HashMap<>();
        q.put("00080061", "CT");
        Attributes attrs = QidoQueryParamParser.parse(q);
        assertEquals("CT", attrs.getString(Tag.Modality));
        assertNull(attrs.getString(Tag.ModalitiesInStudy));
    }

    // ---- Skipping ----

    @Test
    public void unknownKeyDropped() {
        Map<String, String> q = new HashMap<>();
        q.put("NotADicomKeyword", "anything");
        Attributes attrs = QidoQueryParamParser.parse(q);
        assertTrue(attrs.isEmpty());
    }

    @Test
    public void emptyValueDropped() {
        Map<String, String> q = new HashMap<>();
        q.put("StudyDate", "");
        Attributes attrs = QidoQueryParamParser.parse(q);
        assertTrue(attrs.isEmpty());
    }

    @Test
    public void nullValueDropped() {
        Map<String, String> q = new HashMap<>();
        q.put("StudyDate", null);
        Attributes attrs = QidoQueryParamParser.parse(q);
        assertTrue(attrs.isEmpty());
    }

    @Test
    public void paginationParamsAreIgnored() {
        // limit/offset are handled elsewhere; they must not appear as
        // DICOM attributes here.
        Map<String, String> q = new HashMap<>();
        q.put("limit", "50");
        q.put("offset", "100");
        Attributes attrs = QidoQueryParamParser.parse(q);
        assertTrue(attrs.isEmpty());
    }

    // ---- Mixing forms ----

    @Test
    public void mixedKeywordAndHexInSameQuery() {
        // Iteration order matters for last-write-wins semantics, so
        // pin it with LinkedHashMap.
        Map<String, String> q = new LinkedHashMap<>();
        q.put("PatientName", "Doe^John");
        q.put("00080020",    "20200101-20201231");
        q.put("Modality",    "MR");

        Attributes attrs = QidoQueryParamParser.parse(q);
        assertEquals("Doe^John",           attrs.getString(Tag.PatientName));
        assertEquals("20200101-20201231",  attrs.getString(Tag.StudyDate));
        assertEquals("MR",                 attrs.getString(Tag.Modality));
    }

    @Test
    public void hexAndKeywordCollideLastWriteWins() {
        // If a pathological client sends both spellings, we honor the
        // second one seen. LinkedHashMap pins iteration order.
        Map<String, String> q = new LinkedHashMap<>();
        q.put("StudyDate", "20200101");
        q.put("00080020", "20210101");

        Attributes attrs = QidoQueryParamParser.parse(q);
        assertEquals("20210101", attrs.getString(Tag.StudyDate));
    }

    @Test
    public void unrelatedKeywordDoesNotShadowStudyDate() {
        // Regression guard: normalize() must not accidentally match
        // an unrelated key to StudyDate's hex form.
        Map<String, String> q = new HashMap<>();
        q.put("studydateplus", "20200101");
        Attributes attrs = QidoQueryParamParser.parse(q);
        assertFalse(attrs.contains(Tag.StudyDate));
    }
}
