# DICOMweb Conformance Statement

## XNAT DICOMweb Plugin

**Product:** XNAT DICOMweb Plugin
**Version:** 1.3.0-SNAPSHOT
**DICOM Standard reference:** PS3.18
**Draft date:** 2026-07-06

> **Drafting note.** Behavioral claims in this document are tagged
> with the implementing file and line range so readers can confirm
> them in source. Data-element tag numbers and PS3.18 section
> references have been verified against the current DICOM standard
> at `dicom.nema.org/medical/dicom/current/output/html/` (verification
> pass 2026-07-02, covering PS3.6, PS3.15, PS3.16, PS3.18). Any
> subsequent edits to spec-touching text should be re-verified before
> publication.

---

## 0. Notable Deviations from Typical DICOMweb Implementations

XNAT organizes imaging sessions and related data into a data model
similar, but not identical, to the DICOM data model. The plugin
projects the XNAT data model through the DICOMweb services; the
differences between data models leads in some cases to possibly
surprising behavior. This section describes some differences from
typical DICOMweb implementations.

### 0.1 QIDO-RS study-level attributes are synthesized from XNAT, not read from DICOM headers

Study-level attribute values returned in a QIDO-RS response are read
from XNAT's schema (sessions, subjects, projects). The
instance files returned via WADO-RS retain their **original DICOM
header values**. A client that retrieves a study after seeing it in
QIDO-RS and then re-reads the headers will see mismatches in:

| QIDO-RS reports                     | What's actually in the DICOM files |
|-------------------------------------|------------------------------------|
| Patient Name = XNAT session label   | original DICOM `(0010,0010)` value |
| Patient ID = XNAT subject label     | original DICOM `(0010,0020)` value |
| Accession Number = XNAT session ID  | original DICOM `(0008,0050)` value |
| Study ID = XNAT session ID          | original DICOM `(0020,0010)` value |
| Study Description = XNAT project ID | original DICOM `(0008,1030)` value |
| Referring Physician Name = empty    | original DICOM `(0008,0090)` value |

Implementation: `XnatDicomServiceImpl.java:464-540` (study attribute
assembly).

### 0.2 Series- and instance-level attributes do come from DICOM headers

Series and instance attributes in QIDO-RS responses are extracted
from a representative DICOM file in the corresponding XNAT scan, so
their values match the retrieved files. This produces an asymmetry:
the same logical concept can be reported with two different values
depending on the query level. For example `ModalitiesInStudy` at the
study level is computed from XNAT scan modality, while `Modality` at
the series level is the DICOM header.

Implementation: `XnatDicomServiceImpl.java:596-598` (series/instance
file parsing), `:504-509` (`ModalitiesInStudy` from scan modality).

### 0.3 `fuzzymatching` and `includefield` are silently ignored

Neither parameter is processed by the plugin. The returned attribute
set at each level is fixed (Section 6.5). A client that supplies
`fuzzymatching=true` gets exact matching; a client that supplies
`includefield=...` gets the fixed set.

Implementation: `QidoRsApi.java:279-345`.

### 0.4 No transcoding; the `transfer-syntax` Accept parameter is silently ignored

WADO-RS returns instances byte-for-byte from the archive. The
plugin does not parse, match against, or honor the `transfer-syntax`
parameter of the `Accept` header, and does not return 406 when a
requested syntax differs from what is on disk. Clients that need a
specific transfer syntax must accept the archived one or transcode
client-side.

Implementation: `WadoRsApi.java:1217-1219`
(`streamFilesAsMultipart`; transfer via `Files.newInputStream`).

### 0.5 STOW-RS is asynchronous

A successful 200 from STOW-RS means the upload was accepted into
XNAT (prearchive or direct-archive), not that the data is
immediately retrievable via QIDO-RS / WADO-RS.

- **GradualDicomImporter** (default) stages files in the prearchive;
  an asynchronous import daemon builds the session.
- **DirectArchive** writes directly into the archive and debounces
  the build by `buildDelayMs` (default 5000 ms).

Round-trip clients that issue STOW then QIDO must poll.

Implementation: `StowRsServiceImpl.java:166-176` (strategy
selection), `:593-670` (DirectArchive debounce).

### 0.6 Site-wide STOW with no resolvable project can orphan data

When site-wide STOW receives data whose embedded metadata does not
identify a known XNAT project, the resulting session is routed to
the unassigned prearchive and is **not automatically built or
archived**. STOW returns 200 but the data is not discoverable
through QIDO/WADO until an operator promotes it from the prearchive
UI.

Implementation: `StowRsServiceImpl.java:485-502`.

### 0.7 Missing patient identifiers are emitted as the literal string `UNKNOWN`

When XNAT has no patient name or patient ID for a subject, QIDO-RS
returns the literal string `UNKNOWN` rather than an empty PN/LO
value. DICOM allows zero-length values; this plugin emits a literal
placeholder. Clients that filter or group by patient identifier may
treat all such subjects as a single patient.

Implementation: `XnatDicomServiceImpl.java:466, 470`.

### 0.8 Authentication is XNAT-session-based, not bearer

All endpoints except `/xapi/dicomweb/test` require an authenticated
XNAT user (cookie session, basic auth via `XNAT-User`/`XNAT-Pass`,
or alias token). There is no OAuth/bearer integration in the plugin.
The test page is declared as an open URL.

Implementation: `DicomWebPlugin.java:14` (`openUrls`).

### 0.9 The STOW per-project endpoint returns 403 (not 404) for unknown projects

The endpoint returns HTTP 403 whether the project does not exist or
the caller lacks edit access. PACS clients that distinguish 404
from 403 to disambiguate "wrong URL" from "wrong permissions"
cannot tell the two apart here.

Implementation: `StowRsApi.java:92-95` (throws
`ForbiddenException`).

### 0.10 STOW-RS response is a JSON array of per-study objects

PS3.18 §10.5.3.3 (Store Instances Response Module, Annex I
Table I.1-1) specifies a **single** response object with top-level
Retrieve URL `(0008,1190)`, Referenced SOP Sequence `(0008,1199)`,
Failed SOP Sequence `(0008,1198)`, and Other Failures Sequence
`(0008,119A)` — a flat list of instance references, with each
Referenced/Failed item independently identifying its Study via the
SOP Instance Reference Macro.

The plugin instead returns a **JSON array** with one object per
distinct Study Instance UID observed in the uploaded parts, each
object carrying a study-scoped Retrieve URL, Referenced SOP
Sequence, and Failed SOP Sequence. The plugin does not emit
`(0008,119A)` Other Failures Sequence. Clients that parse per the
Store Instances Response Module will see an array where they
expected an object, and will not find failures that could not be
associated with a study (they are logged but not included in the
response).

Implementation: `StowRsServiceImpl.java:720-...` (`buildStowRsResponse`,
per-study grouping starts at line 732).

### 0.11 QIDO-RS pagination uses `X-Total-Count`, not PS3.18's `Warning: 299`

PS3.18 §8.3.4.4.1 defines the pagination remainder-count signal as
an HTTP `Warning: 299 <service>: There are <remaining> additional
results that can be requested` header. The plugin instead emits a
custom `X-Total-Count` response header carrying the *total* number
of matches (before pagination), and does not emit `Warning: 299`.

Clients relying on the PS3.18 convention will not detect that more
results remain. Clients aware of the plugin's `X-Total-Count` can
compute the remainder as `X-Total-Count − offset − returned`.

Implementation: `QidoRsApi.java:165, 217, 270`;
`SiteWideQidoRsApi.java:88, 124, 162`.

---

## 1. Overview

### 1.1 Application Description

The XNAT DICOMweb Plugin adds DICOMweb REST endpoints to an XNAT
installation. It implements three of the DICOMweb services:

- **QIDO-RS** — Query based on ID for DICOM Objects
- **WADO-RS** — Web Access to DICOM Objects (retrieval)
- **STOW-RS** — Store Over the Web

The intended use is to allow web-based viewers (OHIF, VolView,
Weasis) and DICOMweb-aware research tools to browse, retrieve, and
upload DICOM data archived in XNAT.

### 1.2 Supported DICOMweb Transactions

| Transaction                              | Path family                        | Section |
|------------------------------------------|------------------------------------|---------|
| Search for Studies / Series / Instances  | `/studies[/{s}/series[/{s}/instances]]` | 6 |
| Retrieve Study / Series / Instance       | `/studies/{s}[...]`                | 7       |
| Retrieve Metadata                        | `.../metadata`                     | 7       |
| Retrieve Rendered                        | `.../rendered`                     | 7       |
| Retrieve Frames                          | `.../frames/{list}[/rendered]`     | 7       |
| Retrieve Thumbnail                       | `.../thumbnail`                    | 7       |
| Retrieve Bulk Data / Pixel Data          | `.../bulkdata[/{tag}]`, `.../pixeldata` | 7  |
| Store Instances                          | `/studies`                         | 8       |

Each is exposed in two scopes:
- **Per-project:** under `/xapi/dicomweb/projects/{projectId}/`
- **Site-wide:**   under `/xapi/dicomweb/`

### 1.3 Not Implemented

- UPS-RS (Workitem service)
- Capabilities transaction (`OPTIONS /dicomweb`)
- DELETE on any resource
- QIDO `includefield` and `fuzzymatching`
- Server-side transcoding / `transfer-syntax` Accept parameter
- DICOM Audit Trail (PS3.15) messages

### 1.4 Dependencies

The plugin uses `dcm4che-core` 5.x for DICOM parsing and
`dcm4che-json` for JSON serialization.

---

## 2. Implementation Model

### 2.1 Data Flow

```
            DICOM Viewer / DICOMweb Client (HTTPS)
                          |
                    +-----v-----+
                    |  Tomcat   |
                    |  + XNAT   |
                    |  Filters  |   (authentication)
                    +-----+-----+
                          |
              +-----------v-------------+
              |   DICOMweb Plugin       |
              |   QIDO / WADO / STOW    |
              +-+----------+----------+-+
                |          |          |
        +-------v---+ +----v----+ +---v--------+
        | XNAT      | | XNAT    | | XNAT       |
        | database  | | archive | | import     |
        | (sessions,| | file    | | pipeline   |
        |  scans)   | | system  | | (prearchive|
        |           | |         | |  / direct  |
        |           | |         | |  archive)  |
        +-----------+ +---------+ +------------+
```

### 2.2 Request Handling

1. The XNAT filter stack authenticates the request.
2. A REST controller resolves the project (per-project routes) or the
   set of accessible projects (site-wide routes).
3. **QIDO-RS** queries XNAT's relational schema for study-level
   results; series- and instance-level results parse representative
   DICOM files from the XNAT archive.
4. **WADO-RS** locates the relevant XNAT scan and serves DICOM files
   (or derived metadata / rendered images / bulk data) directly from
   disk.
5. **STOW-RS** parses the `multipart/related` body manually — the
   controller declares `consumes = "*/*"` and does not rely on
   Spring's multipart resolver — then hands the resulting DICOM
   files to one of two import strategies (Section 8.2).

Implementation of manual multipart parsing: `StowRsApi.java:74`
(controller `consumes`); body parser is `Mime4jHybridParser`.

### 2.3 Sequencing

Requests are independent. There is no application-level locking
across requests. STOW-RS uploads are processed asynchronously
(Section 0.5).

---

## 3. Endpoints

All paths below are prefixed with `/xapi`. Per-project and site-wide
variants share the same query semantics; the only difference is the
scope and the URL.

Implementation: the prefix is added by the `@XapiRequestMapping`
annotation on each controller.

### 3.1 QIDO-RS

| Method | Path                                                                            |
|--------|---------------------------------------------------------------------------------|
| GET    | `/xapi/dicomweb/projects/{projectId}/studies`                                   |
| GET    | `/xapi/dicomweb/projects/{projectId}/studies/{study}/series`                    |
| GET    | `/xapi/dicomweb/projects/{projectId}/studies/{study}/series/{series}/instances` |
| GET    | `/xapi/dicomweb/studies`                                                        |
| GET    | `/xapi/dicomweb/studies/{study}/series`                                         |
| GET    | `/xapi/dicomweb/studies/{study}/series/{series}/instances`                      |

### 3.2 WADO-RS

Each per-project route below has a corresponding site-wide route
without the `/projects/{projectId}` segment.

| Method | Path                                                                                 |
|--------|--------------------------------------------------------------------------------------|
| GET    | `/xapi/dicomweb/projects/{projectId}/studies/{study}`                                |
| GET    | `.../studies/{study}/metadata`                                                       |
| GET    | `.../studies/{study}/rendered`                                                       |
| GET    | `.../studies/{study}/thumbnail`                                                      |
| GET    | `.../studies/{study}/bulkdata`                                                       |
| GET    | `.../studies/{study}/pixeldata`                                                      |
| GET    | `.../studies/{study}/series/{series}`                                                |
| GET    | `.../series/{series}/metadata`                                                       |
| GET    | `.../series/{series}/rendered`                                                       |
| GET    | `.../series/{series}/thumbnail`                                                      |
| GET    | `.../series/{series}/bulkdata`                                                       |
| GET    | `.../series/{series}/pixeldata`                                                      |
| GET    | `.../instances/{instance}`                                                           |
| GET    | `.../instances/{instance}/metadata`                                                  |
| GET    | `.../instances/{instance}/rendered`                                                  |
| GET    | `.../instances/{instance}/thumbnail`                                                 |
| GET    | `.../instances/{instance}/bulkdata`                                                  |
| GET    | `.../instances/{instance}/bulkdata/{tag}`                                            |
| GET    | `.../instances/{instance}/pixeldata`                                                 |
| GET    | `.../instances/{instance}/frames/{frameList}`                                        |
| GET    | `.../instances/{instance}/frames/{frameList}/rendered`                               |
| GET    | `.../instances/{instance}/frames/{frameList}/thumbnail`                              |

### 3.3 STOW-RS

| Method | Path                                              |
|--------|---------------------------------------------------|
| POST   | `/xapi/dicomweb/projects/{projectId}/studies`     |
| POST   | `/xapi/dicomweb/studies` (site-wide)              |

### 3.4 Configuration / Utility

| Method | Path                                                       | Access   |
|--------|------------------------------------------------------------|----------|
| GET    | `/xapi/dicomweb/prefs`                                     | Admin    |
| POST   | `/xapi/dicomweb/prefs`                                     | Admin    |
| GET    | `/xapi/dicomweb/projects/{projectId}/config/site-wide`     | Admin    |
| PUT    | `/xapi/dicomweb/projects/{projectId}/config/site-wide`     | Admin    |
| GET    | `/xapi/dicomweb/test`                                      | Open URL |

---

## 4. Media Types

### 4.1 Request

| Transaction | Accepted request media types                                                                  |
|-------------|-----------------------------------------------------------------------------------------------|
| QIDO-RS     | (no body)                                                                                     |
| WADO-RS     | (no body)                                                                                     |
| STOW-RS     | `*/*` — body must be `multipart/related; type="application/dicom"`, parsed manually (see 2.2) |

### 4.2 Response

The plugin negotiates content via the `Accept` header. The
query-string shortcut `accept=<type>` may be used in place of the
header.

Implementation: negotiation logic in `MediaTypeNegotiator`.

| Transaction              | Default response type                               | Also negotiable                  |
|--------------------------|-----------------------------------------------------|----------------------------------|
| QIDO-RS                  | `application/dicom+json`                            | —                                |
| WADO-RS instance         | `application/dicom` when no `Accept`; `multipart/related; type="application/dicom"` on wildcard | both forms negotiated per §7.1 |
| WADO-RS series / study   | `multipart/related; type="application/dicom"` (only) | —                               |
| WADO-RS metadata         | `application/dicom+json`                            | `application/dicom+xml`          |
| WADO-RS rendered / thumbnail | `image/jpeg`                                    | `image/png`, `image/gif`         |
| WADO-RS bulk / pixel     | `multipart/related; type="application/octet-stream"` | `application/octet-stream`      |
| STOW-RS response         | `application/dicom+json`                            | —                                |

---

## 5. Authentication and Authorization

### 5.1 Authentication

All endpoints except `/xapi/dicomweb/test` require an authenticated
XNAT user. The test page is declared as an open URL. Authentication
mechanisms supported by XNAT (cookie session, basic auth via
`XNAT-User`/`XNAT-Pass`, alias tokens) all apply. There is no
DICOMweb-specific authentication.

Implementation: `DicomWebPlugin.java:14` (`openUrls`).

### 5.2 Authorization

| Transaction                | Required XNAT permission                                      |
|----------------------------|---------------------------------------------------------------|
| QIDO-RS (per-project)      | Read access on the project                                    |
| QIDO-RS (site-wide)        | Read access on each contributing project                      |
| WADO-RS                    | Read access on the project containing the instance            |
| STOW-RS                    | Edit access on the target project                             |
| `prefs` / `site-wide`      | Site administrator                                            |

Site-wide responses are filtered per user: a non-admin user sees only
projects they can read, intersected with the site-wide-included set
(Section 9.1). Admin roles (`SITE_ADMIN`, `ALL_DATA_ACCESS`,
`ALL_DATA_ADMIN`) bypass per-project permission filtering.

### 5.3 Transport / CORS

The plugin operates over whatever transport XNAT itself is configured
for (typically HTTPS via a reverse proxy). The plugin does not
enforce TLS itself and does not configure CORS (see Section 10.2).

---

## 6. QIDO-RS

### 6.1 Search Levels

| Level     | Path                                          |
|-----------|-----------------------------------------------|
| Studies   | `/studies`                                    |
| Series    | `/studies/{study}/series`                     |
| Instances | `/studies/{study}/series/{series}/instances`  |

### 6.2 Supported Query Parameters

| Parameter            | Levels                  | DICOM Tag    | Notes                                  |
|----------------------|-------------------------|--------------|----------------------------------------|
| `StudyInstanceUID`   | Study, Series, Instance | (0020,000D)  | UID-list (comma-separated) not supported |
| `StudyDate`          | Study                   | (0008,0020)  | Exact, wildcard, or range              |
| `StudyTime`          | Study                   | (0008,0030)  | Exact, wildcard, or range              |
| `PatientName`        | Study                   | (0010,0010)  |                                        |
| `PatientID`          | Study                   | (0010,0020)  |                                        |
| `AccessionNumber`    | Study                   | (0008,0050)  | Matches the XNAT session ID (see 6.5)  |
| `Modality`           | Study, Series           | (0008,0060)  | Reverse-mapped (PT→PET) for matching   |
| `SeriesInstanceUID`  | Series, Instance        | (0020,000E)  | Post-filtered in Java                  |
| `SeriesNumber`       | Series                  | (0020,0011)  | Post-filtered in Java                  |
| `SeriesDescription`  | Series                  | (0008,103E)  | Post-filtered in Java                  |
| `SOPInstanceUID`     | Instance                | (0008,0018)  |                                        |
| `SOPClassUID`        | Instance                | (0008,0016)  |                                        |
| `InstanceNumber`     | Instance                | (0020,0013)  |                                        |
| `limit`              | All                     | —            | Default 100, max 1000                  |
| `offset`             | All                     | —            | Default 0                              |

**Silently ignored:** `includefield`, `fuzzymatching`,
`timezoneadjustment`, `dateFormat`, multi-valued UID-list matching.

Implementation: parameter parsing at `QidoRsApi.java:279-345`;
matching in `XnatDicomServiceImpl.java`.

### 6.3 Matching Semantics

- **Exact** — equality. Study-level keys (StudyDate, PatientName,
  PatientID, AccessionNumber, etc.) are pushed to SQL; series and
  instance keys are post-filtered in Java after parsing candidate
  files.
- **Wildcard** — `*` and `?`, translated to SQL `ILIKE` patterns for
  study-level keys and to regex-like Java matching for lower levels.
  No escape mechanism for literal `*` / `?`.
- **Range** — supported at all levels, per PS3.4 §C.2.2.2.5.1 (DA)
  and §C.2.2.2.5.2 (TM). Range endpoints must be full 8-digit
  `yyyyMMdd` (DA) or 6-digit `HHmmss` (TM); wildcards inside a range
  endpoint are not permitted. Malformed range values at the study
  level return HTTP 400.
- **Universal** — empty parameter value matches everything.
  The DICOM range marker `-` (both bounds omitted) is treated the
  same way at the study level.
- **Date format** — DICOM `yyyyMMdd` only.

Implementation of range parsing: `DicomRangeParser.java` (shared
parser for DA/TM ranges). Study-level SQL emission:
`XnatDicomServiceImpl.addQueryAttributeFilters` (StudyDate and
StudyTime branches around lines 372-410). Series/instance-level
Java matching: `XnatDicomServiceImpl.matchesDicomDate` and
`matchesDicomTime` around lines 2645-2724.

### 6.4 Pagination

`limit` and `offset` follow DICOMweb conventions. The plugin emits
an `X-Total-Count` response header carrying the total match count
before pagination. It does **not** emit the PS3.18 `Warning: 299`
remainder-count header — see 0.11.

Bounds:
- `limit ≤ 0` → reset to default
- `limit > max` → clamped to max
- non-numeric → reset to default (no 400 returned)

Implementation: `QidoRsApi.java:57-101` (pagination parsing), `:165`
(`X-Total-Count` header), `:65-79` (bounds).

### 6.5 Returned Attributes and XNAT → DICOM Mapping

The returned attribute set at each level is **fixed**. `includefield`
is ignored.

#### 6.5.1 Study Level (XNAT-derived)

| Attribute                       | Tag         | XNAT source                                       |
|---------------------------------|-------------|---------------------------------------------------|
| Study Instance UID              | (0020,000D) | `xnat_imagesessiondata.uid`                       |
| Patient Name                    | (0010,0010) | Session label (`xnat_experimentdata.label`); `UNKNOWN` if absent |
| Patient ID                      | (0010,0020) | Subject label (project-scoped); `UNKNOWN` if absent |
| Study Date                      | (0008,0020) | `xnat_experimentdata.date`                        |
| Study Time                      | (0008,0030) | `xnat_experimentdata.time`                        |
| Study Description               | (0008,1030) | Project ID                                        |
| Accession Number                | (0008,0050) | XNAT session ID                                   |
| Study ID                        | (0020,0010) | XNAT session ID                                   |
| Modalities in Study             | (0008,0061) | Joined scan modalities (PET→PT pass-through)      |
| Number of Study Related Series  | (0020,1206) | Count of `xnat_imagescandata` rows                |
| Number of Study Related Instances | (0020,1208) | Sum of file counts across scans                 |
| Patient Sex                     | (0010,0040) | XNAT demographics (m→M, f→F, other→O)             |
| Patient Birth Date              | (0010,0030) | XNAT demographics DOB                             |
| Referring Physician Name        | (0008,0090) | Empty (not modeled in XNAT)                       |
| Retrieve URL                    | (0008,1190) | Built from base URL + project + study             |

Implementation: `XnatDicomServiceImpl.java:464-540`.

#### 6.5.2 Series Level (DICOM-derived)

Parsed from a representative file.

| Attribute                          | Tag         | Source                       |
|------------------------------------|-------------|------------------------------|
| Study Instance UID                 | (0020,000D) | From query path              |
| Series Instance UID                | (0020,000E) | DICOM header                 |
| Modality                           | (0008,0060) | DICOM header                 |
| Series Number                      | (0020,0011) | DICOM header                 |
| Series Description                 | (0008,103E) | DICOM header                 |
| Number of Series Related Instances | (0020,1209) | File count in the XNAT scan  |
| Retrieve URL                       | (0008,1190) | Constructed                  |

Implementation: `XnatDicomServiceImpl.java:596-598`.

#### 6.5.3 Instance Level (DICOM-derived)

| Attribute             | Tag         | Source       |
|-----------------------|-------------|--------------|
| SOP Instance UID      | (0008,0018) | DICOM header |
| SOP Class UID         | (0008,0016) | DICOM header |
| Instance Number       | (0020,0013) | DICOM header |
| Instance Availability | (0008,0056) | Always `ONLINE` |
| Retrieve URL          | (0008,1190) | Constructed  |

### 6.6 Modality Mapping

| XNAT value          | DICOM value |
|---------------------|-------------|
| `PET`               | `PT`        |
| Anything else       | Pass-through (truncated to 16 chars) |

Reverse mapping (DICOM → XNAT) is applied to incoming `Modality`
query parameters.

### 6.7 Status Codes

| Status | Meaning                                                             |
|--------|---------------------------------------------------------------------|
| 200    | Matches returned                                                    |
| 204    | No matches                                                          |
| 400    | Malformed parameter                                                 |
| 401    | Not authenticated                                                   |
| 403    | Authenticated user lacks read access                                |
| 404    | Project, study, or series not found                                 |

---

## 7. WADO-RS

### 7.1 Retrieve Instances / Series / Studies

Returns DICOM Part-10 files with File Meta Information preserved.

- **Series and study** retrieval (`/studies/{s}` and
  `.../series/{s}`) **always** return
  `multipart/related; type="application/dicom"`; clients that ask
  for bare `application/dicom` get 406.
- **Single-instance** retrieval negotiates between single-part
  `application/dicom` and multipart:
  - `Accept: application/dicom` → single-part `application/dicom`
  - `Accept: multipart/related[;type="application/dicom"]` → multipart
  - `Accept: */*` → multipart (multipart is listed first in the
    supported set, so a wildcard resolves to it)
  - No `Accept` header → single-part `application/dicom`

**Transcoding.** None. Instances are returned in the transfer syntax
under which they were archived. The `transfer-syntax` parameter of
the `Accept` header is silently ignored — the plugin does not match
it against the archived syntax and does not return 406 on mismatch.
See 0.4.

Implementation: `WadoRsApi.java:91-123` (single-instance retrieval
and multipart branch); `WadoMediaTypes.DICOM_TYPES` and
`INSTANCE_DEFAULT` for the negotiated set and default; multipart
framing via `streamFilesAsMultipart` / `DicomMultipartWriter`.

### 7.2 Retrieve Metadata

`/metadata` endpoints return DICOM JSON (or XML) for the requested
study, series, or instance. Bulk data attributes — by default,
anything larger than `dicomweb.bulkDataThreshold` bytes (default
1024) — are replaced by `BulkDataURI` references pointing to
`.../bulkdata/{tag}` on the same plugin. File Meta Information is
omitted from metadata responses.

### 7.3 Retrieve Rendered

`/rendered` endpoints return rendered images of an instance, a
specific frame, a series (first instance), or a study (first
instance). The offered media types are `image/jpeg` (default),
`image/png`, and `image/gif`; a request that specifies any other
type gets 406. Multi-frame instances may be rendered as animated
GIFs when GIF is requested.

Supported query parameters: `frame`, `viewport`, `window`, `quality`.
Values are not strictly validated; they are forwarded to dcm4che's
renderer.

### 7.4 Retrieve Thumbnail

Behaves like `/rendered` but at lower resolution. The same media
types are offered: `image/jpeg` (default), `image/png`, `image/gif`.

### 7.5 Retrieve Bulk Data / Pixel Data

`/bulkdata`, `/bulkdata/{tag}`, and `/pixeldata` return raw bytes for
the requested attribute, by default as
`multipart/related; type="application/octet-stream"`. These endpoints
are the targets of the `BulkDataURI` references produced in metadata
responses.

### 7.6 Retrieve Frames

`/frames/{frameList}` returns one or more frames.
`{frameList}` is a comma-separated list of 1-based frame numbers.
With a single frame, the response is `application/octet-stream`;
with multiple, `multipart/related` of octet-stream parts.
Out-of-range frames are logged and skipped.

Implementation: `WadoRsApi.java:725-744`.

### 7.7 Status Codes

| Status | Meaning                                                |
|--------|--------------------------------------------------------|
| 200    | Resource returned                                      |
| 400    | Malformed path or unsupported parameter                |
| 401    | Not authenticated                                      |
| 403    | Authenticated user lacks read access                   |
| 404    | Resource not found                                     |
| 406    | Requested media type cannot be served                  |

---

## 8. STOW-RS

### 8.1 Request Format

STOW-RS uploads use `multipart/related; type="application/dicom"`.
The controller declares `consumes = "*/*"` because the body is
parsed manually rather than by Spring's multipart resolver. Each
part must be `Content-Type: application/dicom`.

Memory handling: parts up to `dicomweb.memoryThreshold` bytes
(default 10 MiB) are held in memory; larger parts spill to temp
files.

Implementation: `StowRsApi.java:74` (`consumes`); body parser is
`Mime4jHybridParser` (Apache Mime4j).

### 8.2 Import Strategies

| Strategy               | Default | Behavior                                                                                       | Requires       |
|------------------------|---------|------------------------------------------------------------------------------------------------|----------------|
| `GradualDicomImporter` | Yes     | Standard XNAT import: files staged in prearchive; build is triggered asynchronously by the prearchive daemon. | All versions   |
| `DirectArchive`        | No      | Files written directly into the archive directory, bypassing prearchive. Build is debounced by `dicomweb.buildDelayMs` (default 5000 ms) to coalesce batched uploads. | XNAT 1.9.1+ |

Selection: `strategy` query parameter or
`dicomweb.defaultStrategy` preference. If `DirectArchive` is
selected but the host XNAT does not support it, the plugin falls
back to `GradualDicomImporter`.

Both strategies respond as soon as files are accepted. See 0.5 for
visibility implications.

Implementation: `StowRsServiceImpl.java:166-176` (strategy
selection), `:593-670` (DirectArchive debounce), `:103-116`
(fallback); strategy classes under `service/impl/strategy/`.

### 8.3 Project Resolution

- **Per-project STOW** (`/xapi/dicomweb/projects/{projectId}/studies`):
  the target project is the path parameter. Files whose DICOM
  metadata implies a different project are still routed to the
  path-specified project.
- **Site-wide STOW** (`/xapi/dicomweb/studies`): the plugin attempts
  to derive a project from the DICOM data. If no project can be
  determined, the data is routed to the unassigned prearchive and
  **not built** (see 0.6 for citation).

Implementation of site-wide project derivation: XNAT's
`DicomObjectIdentifier`.

### 8.4 Response

DICOM JSON response. **The plugin returns a JSON array** with one
object per Study Instance UID observed in the upload, each object
carrying:

- `00081190` (Retrieve URL) — study-level retrieve URL
- `00081199` (Referenced SOP Sequence) — successful instances, with
  per-instance retrieve URLs
- `00081198` (Failed SOP Sequence) — failed instances with `00081197`
  (Failure Reason)

This departs from the PS3.18 Store Instances Response Module
(§10.5.3.3 / Annex I Table I.1-1), which is a single object with
flat Referenced/Failed sequences. `(0008,119A)` Other Failures
Sequence is not emitted. See 0.10.

### 8.5 Status Codes

| Status | Meaning                                                                            |
|--------|------------------------------------------------------------------------------------|
| 200    | All or some instances accepted (see Referenced/Failed SOP sequences in the body)   |
| 400    | Malformed multipart body or no DICOM parts                                         |
| 401    | Not authenticated                                                                  |
| 403    | Per-project: project does not exist OR caller lacks edit access (see 0.9)          |
| 500    | Internal failure of the import pipeline                                            |

---

## 9. Site-Wide Endpoints

Site-wide variants omit the `/projects/{projectId}` segment. These
are not part of the DICOMweb standard.

### 9.1 Site-Wide Filtering

| `dicomweb.filterMode` | Behavior                                                                                |
|-----------------------|-----------------------------------------------------------------------------------------|
| `blacklist` (default) | All projects participate **except** those in `dicomweb.projectList`                     |
| `whitelist`           | **Only** projects in `dicomweb.projectList` participate                                  |

Independently, any project can opt out via
`PUT /xapi/dicomweb/projects/{projectId}/config/site-wide`. A project
excluded at the project level is excluded regardless of mode.

Per-user permission filtering is applied **in addition**: a
non-admin user sees only projects they can read, intersected with
the site-wide-included set.

Implementation: `SiteWideProjectFilter.java:44-73`.

### 9.2 Behavioral Differences from Per-Project Routes

- **Deduplication.** Sessions shared across multiple projects appear
  once in site-wide responses, identified by Study Instance UID.
- **Retrieve URLs.** Site-wide responses contain retrieve URLs that
  point at the site-wide WADO-RS endpoints
  (`/xapi/dicomweb/studies/...`), not at any single project's routes.
- **STOW-RS.** Site-wide upload requires the plugin to derive a
  target project (Section 8.3), with the orphan risk in 0.6.

Implementation of dedup: `XnatDicomServiceImpl.java:545-549`.

---

## 10. Configuration

All settings are managed through the XNAT admin UI
(**Administer > Plugin Settings > DICOMweb Plugin Configuration**) or
via the REST endpoints in Section 3.4. Properties may also be set in
`xnat.properties` or `xnat-conf.properties`.

### 10.1 Settings

| Property                       | Default              | Meaning                                                       |
|--------------------------------|----------------------|---------------------------------------------------------------|
| `dicomweb.defaultPageSize`     | 100                  | Default QIDO-RS page size                                     |
| `dicomweb.maxPageSize`         | 1000                 | Upper bound on QIDO-RS page size                              |
| `dicomweb.memoryThreshold`     | 10 MiB               | STOW-RS multipart parts above this size spill to disk         |
| `dicomweb.bulkDataThreshold`   | 1024                 | Metadata attributes above this size become `BulkDataURI`      |
| `dicomweb.defaultStrategy`     | GradualDicomImporter | Default STOW-RS strategy                                      |
| `dicomweb.buildDelayMs`        | 5000                 | Debounce window for DirectArchive build triggering            |
| `dicomweb.siteWideEnabled`     | true                 | Enable `/xapi/dicomweb/studies` (no-project) endpoints        |
| `dicomweb.filterMode`          | blacklist            | Site-wide project filtering mode                              |
| `dicomweb.projectList`         | (empty)              | Projects in the whitelist or blacklist                        |
| `dicomweb.baseUrl`             | XNAT site URL        | Override base URL used in `Retrieve URL` attributes           |

### 10.2 CORS

The plugin does not emit `Access-Control-*` response headers and
does not register a CORS filter of its own. Cross-origin request
handling is left to Tomcat (which hosts the XNAT `.war`) or to
whatever reverse proxy sits in front of Tomcat.

This matters when the DICOMweb clients are browser-based viewers
(OHIF, VolView, Weasis Web) served from a different origin than the
XNAT installation. Without a CORS policy that allows the viewer's
origin and the relevant methods and headers (`Authorization`,
`Content-Type`, `Accept`, `X-XSRF-TOKEN`, and any custom XNAT
headers the viewer uses), the browser will block the QIDO-RS,
WADO-RS, and STOW-RS calls before they reach the plugin.

Configure CORS at one of:

- **Reverse proxy** (nginx, Apache) — recommended for production;
  add `Access-Control-Allow-Origin` and related headers on the
  `/xapi/dicomweb/*` location.
- **Tomcat** — Tomcat's `CorsFilter` in `web.xml` covers all XNAT
  endpoints uniformly.

Same-origin deployments (the viewer served from the same host and
port as XNAT) need no CORS configuration.

Note that some PACS-oriented DICOMweb servers ship a CORS
configuration control in the server's own admin UI. This plugin does
not; a first-time browser-viewer CORS error is most likely a
misconfiguration in Tomcat or the reverse proxy, not a plugin
issue.

### 10.3 Audit

DICOMweb requests are logged via SLF4J using
`META-INF/resources/dicomweb-logback.xml`. The plugin does not emit
DICOM Audit Trail (PS3.15) messages.

---

## 11. Known Limitations (Summary)

This is the cross-reference index for §0; each item points to its
detailed warning above.

1. Study-level attributes reflect XNAT, not DICOM headers — §0.1
2. Same logical concept can have two values across query levels — §0.2
3. `fuzzymatching` / `includefield` silently ignored — §0.3
4. No transcoding; `transfer-syntax` silently ignored — §0.4
5. STOW-RS visibility is asynchronous — §0.5
6. Site-wide STOW can orphan data — §0.6
7. Missing patient identifiers become literal `UNKNOWN` — §0.7
8. XNAT-session auth only — §0.8
9. STOW per-project returns 403 (not 404) for unknown projects — §0.9
10. STOW-RS response is a JSON array of per-study objects, not the
    PS3.18 Store Instances Response Module — §0.10
11. QIDO-RS pagination uses `X-Total-Count`, not PS3.18's
    `Warning: 299` — §0.11
12. No UPS-RS, no Capabilities transaction, no DELETE — §1.3
13. No DICOM Audit Trail (PS3.15) messages — §10.3
14. CORS is not configured by the plugin; must be handled in Tomcat
    or a reverse proxy — §10.2
15. Series- and instance-level QIDO parses files on disk; large
    studies may be slow.

---

## Appendix A. Verification Pointers

The following code locations were re-read during this drafting pass.
A re-verification pass should start here.

- `src/main/java/org/nrg/xnat/dicomweb/plugin/DicomWebPlugin.java`
- `src/main/java/org/nrg/xnat/dicomweb/rest/QidoRsApi.java`
- `src/main/java/org/nrg/xnat/dicomweb/rest/WadoRsApi.java`
- `src/main/java/org/nrg/xnat/dicomweb/rest/StowRsApi.java`
- `src/main/java/org/nrg/xnat/dicomweb/config/DicomWebProperties.java`
- `src/main/java/org/nrg/xnat/dicomweb/service/impl/XnatDicomServiceImpl.java`
  (focused on lines 372-410 for study-level query, 464-540,
  545-549, 596-598, 2645-2724 for DA/TM matching)
- `src/main/java/org/nrg/xnat/dicomweb/service/impl/StowRsServiceImpl.java`
  (focused on lines 103-116, 166-176, 485-502, 593-670, 720-...
  for `buildStowRsResponse`)
- `src/main/java/org/nrg/xnat/dicomweb/service/SiteWideProjectFilter.java`
  (focused on lines 44-73)
- `src/main/java/org/nrg/xnat/dicomweb/util/DicomRangeParser.java`
  (DA/TM range parsing shared by the study-level SQL emission)

Files **not** re-read in this pass (claims about them are inherited
from the prior draft and should be re-verified before publication):

- `MediaTypeNegotiator.java` — exact Accept-parsing rules
- `BulkDataHandler.java` — `BulkDataURI` shape and base-URI extraction
- `DicomMultipartWriter.java` — multipart framing details
- `Mime4jHybridParser.java` — STOW-RS body parsing
- `service/impl/strategy/GradualDicomImporterStrategy.java`
- `service/impl/strategy/DirectArchiveStrategy.java`
- `DicomWebPreferenceBean.java` — verify the literal default values
  in §10.1 against the bean's `@NrgPreferenceBean` defaults

DICOM standard references were verified on 2026-07-02 against
`dicom.nema.org/medical/dicom/current/output/html/` (parts 3, 4, 6,
15, 16, 18). The verification pass produced the following findings,
which have been folded into the current document:

- All Data Element tag numbers used in this document appear in
  PS3.6 with the keywords used here.
- STOW-RS response structure diverges from PS3.18 §10.5.3.3 / Annex I
  (see §0.10, §8.4).
- Pagination remainder signal diverges from PS3.18 §8.3.4.4.1
  (see §0.11, §6.4).
- QIDO-RS UID-list separator is comma per PS3.18 §8.3.4.1
  (previously mis-cited as backslash in §6.2 — fixed).
- DA/TM range matching semantics implemented per PS3.4
  §C.2.2.2.5.1 (DA) and §C.2.2.2.5.2 (TM) at all query levels.
  Prior to the study-level range fix (2026-07-06), study-level
  hyphenated inputs silently matched nothing; now they parse as
  ranges or return HTTP 400 on malformed input.

If any spec-touching text is edited after 2026-07-02, that specific
claim should be re-verified against the standard.
