# DICOMweb Conformance — Developer Annotations

Supplement to `CONFORMANCE.md`. The conformance statement itself
describes plugin behavior in behavioral and specification terms
only; this document pairs each behavioral claim with the
implementing code so a reviewer or a maintainer can go from
"the plugin does X" to "here is where and how it does X" without
needing to grep the codebase.

Section numbering mirrors `CONFORMANCE.md` exactly: an item in
§0.1 of the conformance statement is annotated in §0.1 here.
Sections without any implementation notes are omitted.

Line numbers reflect the source tree as of the date at the foot of
this document; they will drift as the code changes and should be
treated as pointers rather than durable citations.

---

## 0. Notable Deviations from Typical DICOMweb Implementations

### 0.1 Study-level attributes synthesized from XNAT

`XnatDicomServiceImpl.java:464-540` (study attribute assembly).

### 0.2 Series/instance attributes from DICOM headers

`XnatDicomServiceImpl.java:596-598` (series/instance file parsing),
`:504-509` (`ModalitiesInStudy` computed from scan modality).

### 0.3 `fuzzymatching` / `includefield` silently ignored

`QidoRsApi.java:279-345` (`parseQueryParameters`).

### 0.4 No transcoding; `transfer-syntax` ignored

`WadoRsApi.java:1217-1219` (`streamFilesAsMultipart`; transfer via
`Files.newInputStream`).

### 0.5 STOW-RS asynchronous

`StowRsServiceImpl.java:166-176` (strategy selection), `:593-670`
(DirectArchive debounce).

### 0.6 Site-wide STOW orphan risk

`StowRsServiceImpl.java:485-502` — unassigned-project path.

### 0.7 `UNKNOWN` literal for missing patient identifiers

`XnatDicomServiceImpl.java:466, 470`.

### 0.8 STOW per-project 403 for unknown projects

`StowRsApi.java:92-95` — throws `ForbiddenException`.

### 0.9 STOW response is a JSON array of per-study objects

`StowRsServiceImpl.java:720-…` (`buildStowRsResponse`, per-study
grouping starts at line 732).

### 0.10 `X-Total-Count` instead of `Warning: 299`

`QidoRsApi.java:165, 217, 270`; `SiteWideQidoRsApi.java:88, 124,
162`. No `Warning: 299` header is emitted anywhere.

---

## 2. Implementation Model

### 2.2 Request Handling

Manual multipart parsing for STOW-RS: `StowRsApi.java:74`
(controller `consumes = "*/*"`); body parser is
`Mime4jHybridParser` (Apache Mime4j).

---

## 3. Endpoints

Path prefix: the `/xapi/` prefix is added by the
`@XapiRequestMapping` annotation on each controller.

---

## 4. Media Types

### 4.2 Response

Content negotiation logic lives in `MediaTypeNegotiator`.

---

## 5. Authentication and Authorization

### 5.1 Authentication

`DicomWebPlugin.java:14` — `openUrls` attribute of `@XnatPlugin`
declares `/xapi/dicomweb/test` as the sole open URL.

---

## 6. QIDO-RS

### 6.2 Supported Query Parameters

Parameter parsing at `QidoRsApi.java:279-345`; the parsed
attributes are then matched in `XnatDicomServiceImpl.java` (SQL
side for study-level keys, Java-side for series/instance keys).

### 6.3 Matching Semantics

Range parsing: `DicomRangeParser.java` (shared parser for DA/TM
ranges). Study-level SQL emission:
`XnatDicomServiceImpl.addQueryAttributeFilters` — the StudyDate
and StudyTime branches at approximately lines 372-410 hand-off to
`DicomRangeParser.parseDicomDateRange` /
`parseDicomTimeRange`, and combine into a DT range via
`DicomRangeParser.combineIntoDateTimeRange` before falling back to
independent handling. Series/instance-level Java matching lives in
`XnatDicomServiceImpl.matchesDicomDate` and `matchesDicomTime` at
approximately lines 2645-2724.

### 6.4 Pagination

`QidoRsApi.java:57-101` (pagination parsing), `:165`
(`X-Total-Count` header), `:65-79` (bounds).

### 6.5.1 Study Level attribute assembly

`XnatDicomServiceImpl.java:464-540`.

### 6.5.2 Series Level attribute assembly

`XnatDicomServiceImpl.java:596-598` — parsed from a representative
file per XNAT scan.

---

## 7. WADO-RS

### 7.1 Retrieve Instances / Series / Studies

`WadoRsApi.java:91-123` (single-instance retrieval and its
multipart branch); `WadoMediaTypes.DICOM_TYPES` and
`WadoMediaTypes.INSTANCE_DEFAULT` provide the negotiated set and
default; multipart framing goes through `streamFilesAsMultipart` /
`DicomMultipartWriter`.

### 7.6 Retrieve Frames

`WadoRsApi.java:725-744`.

---

## 8. STOW-RS

### 8.1 Request Format

`StowRsApi.java:74` (`consumes = "*/*"`); body parser is
`Mime4jHybridParser` (Apache Mime4j).

### 8.2 Import Strategies

`StowRsServiceImpl.java:166-176` (strategy selection), `:593-670`
(DirectArchive debounce), `:103-116` (fallback when DirectArchive
is unsupported). Strategy classes under
`service/impl/strategy/`.

### 8.3 Project Resolution

Site-wide project derivation uses XNAT's `DicomObjectIdentifier`.

---

## 9. Site-Wide Endpoints

### 9.1 Site-Wide Filtering

`SiteWideProjectFilter.java:44-73`.

### 9.2 Behavioral Differences from Per-Project Routes

Study-Instance-UID deduplication in site-wide responses:
`XnatDicomServiceImpl.java:545-549`.

---

## Verification Pointers

The following source files were verified against the conformance
statement at release. Any subsequent re-verification pass should
start here.

- `src/main/java/org/nrg/xnat/dicomweb/plugin/DicomWebPlugin.java`
- `src/main/java/org/nrg/xnat/dicomweb/rest/QidoRsApi.java`
- `src/main/java/org/nrg/xnat/dicomweb/rest/WadoRsApi.java`
- `src/main/java/org/nrg/xnat/dicomweb/rest/StowRsApi.java`
- `src/main/java/org/nrg/xnat/dicomweb/config/DicomWebProperties.java`
- `src/main/java/org/nrg/xnat/dicomweb/service/impl/XnatDicomServiceImpl.java`
  (focused on lines 372-410 for study-level query, 464-540,
  545-549, 596-598, and 2645-2724 for DA/TM matching)
- `src/main/java/org/nrg/xnat/dicomweb/service/impl/StowRsServiceImpl.java`
  (focused on lines 103-116, 166-176, 485-502, 593-670, and
  720-… for `buildStowRsResponse`)
- `src/main/java/org/nrg/xnat/dicomweb/service/SiteWideProjectFilter.java`
  (focused on lines 44-73)
- `src/main/java/org/nrg/xnat/dicomweb/util/DicomRangeParser.java`
  (DA/TM range parsing shared by the study-level SQL emission)

Files **not** re-read for this release — claims about them are
inherited from earlier drafts and should be re-verified in the
next revision:

- `MediaTypeNegotiator.java` — exact Accept-parsing rules
- `BulkDataHandler.java` — `BulkDataURI` shape and base-URI
  extraction
- `DicomMultipartWriter.java` — multipart framing details
- `Mime4jHybridParser.java` — STOW-RS body parsing
- `service/impl/strategy/GradualDicomImporterStrategy.java`
- `service/impl/strategy/DirectArchiveStrategy.java`
- `DicomWebPreferenceBean.java` — verify the literal default
  values in `CONFORMANCE.md` §10.1 against the bean's
  `@NrgPreferenceBean` defaults

---

## Standard Verification

DICOM standard references in the conformance statement were
verified on 2026-07-02 against
`dicom.nema.org/medical/dicom/current/output/html/` (parts 3, 4,
6, 15, 16, 18). Findings, folded into `CONFORMANCE.md`:

- All Data Element tag numbers used in the conformance statement
  appear in PS3.6 with the keywords used there.
- STOW-RS response structure diverges from PS3.18 §10.5.3.3 /
  Annex I (see conformance §0.9, §8.4).
- Pagination remainder signal diverges from PS3.18 §8.3.4.4.1
  (see conformance §0.10, §6.4).
- QIDO-RS UID-list separator is comma per PS3.18 §8.3.4.1
  (previously mis-cited as backslash in §6.2 — corrected).
- DA/TM range matching semantics implemented per PS3.4
  §C.2.2.2.5.1 (DA) and §C.2.2.2.5.2 (TM) at all query levels.
  Prior to the study-level range fix (2026-07-06), study-level
  hyphenated inputs silently matched nothing; now they parse as
  ranges or return HTTP 400 on malformed input.

If spec-touching text is edited after 2026-07-02, that specific
claim should be re-verified against the standard as part of the
next revision.

---

**Date:** 2026-07-08
