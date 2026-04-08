# STOW-RS Implementation

## Status: Implemented

**Version:** 1.1.3
**Last Updated:** December 10, 2025

This document describes the STOW-RS (STore Over the Web by RESTful Services) implementation for the XNAT DICOMweb Plugin.

## Overview

STOW-RS enables uploading DICOM instances to XNAT via RESTful HTTP POST requests. The implementation supports two import strategies and returns DICOMweb-compliant JSON responses.

## API Endpoint

```
POST /xapi/dicomweb/projects/{projectId}/studies[?strategy=<strategy>]
```

**Content-Type:** `multipart/related; type="application/dicom"; boundary=<boundary>`
**Accept:** `application/dicom+json`
**Authentication:** Required (Edit permission on project)

### Query Parameters

| Parameter | Values | Default | Description |
|-----------|--------|---------|-------------|
| `strategy` | `GradualDicomImporter`, `DirectArchive` | `GradualDicomImporter` | Import strategy selection |

## Import Strategies

### 1. GradualDicomImporter (Default, Recommended)

Uses XNAT's native `GradualDicomImporter` for a complete import pipeline.

**Features:**
- Full DICOM validation
- Automatic session/scan creation
- Prearchive workflow support
- Session merging for concurrent uploads
- Automatic archiving after build

**Process Flow:**
```
Multipart Request → Parse → GradualDicomImporter → Prearchive → Build → Archive
```

**Concurrent Upload Handling:**
- Per-session build locks prevent duplicate builds
- Last-activity-time approach delays build until uploads complete
- Import futures ensure all files are processed before building

### 2. DirectArchive (Experimental)

Direct archive writing bypassing prearchive.

> ⚠️ **EXPERIMENTAL**: This strategy has significant limitations. Use only when you understand the trade-offs.

**Limitations:**
- **No session append support**: Cannot add files to existing sessions
- **No duplicate detection**: Will fail if session already exists
- **Limited concurrent support**: Concurrent protection is less robust than GradualDicomImporter
- **No prearchive review**: Files go directly to archive without review opportunity

**Use Cases:**
- Fresh uploads to new sessions only
- When prearchive workflow is not needed
- Performance-critical scenarios with single-patient uploads

**Process Flow:**
```
Multipart Request → Parse → DirectArchiveSession → Archive (direct)
```

## Response Format

The response is a JSON array with one object per study, each containing the instances for that study.

### Success Response (HTTP 200)

```json
[
  {
    "00081190": {
      "vr": "UR",
      "Value": ["http://host/xapi/dicomweb/projects/PROJECT/studies/1.2.3.4"]
    },
    "00081198": {
      "vr": "SQ"
    },
    "00081199": {
      "vr": "SQ",
      "Value": [
        {
          "00081150": {"vr": "UI", "Value": ["1.2.840.10008.5.1.4.1.1.2"]},
          "00081155": {"vr": "UI", "Value": ["1.2.3.4.5.6.7.8.9"]},
          "00081190": {"vr": "UR", "Value": ["http://host/.../instances/1.2.3.4.5.6.7.8.9"]}
        }
      ]
    }
  }
]
```

### Response Tags

| Tag | Name | Description |
|-----|------|-------------|
| `00081190` | RetrieveURL | URL to retrieve the study |
| `00081198` | FailedSOPSequence | Failed instances with failure reasons |
| `00081199` | ReferencedSOPSequence | Successfully stored instances |

### Multi-Study Response

When a single request contains instances from multiple studies (e.g., different patients), the response contains one object per study:

```json
[
  { "study1 with its instances" },
  { "study2 with its instances" }
]
```

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    StowRsApi (REST)                          │
│  - Validate project access                                   │
│  - Parse query parameters                                    │
│  - Select import strategy                                    │
└────────────────────────────┬────────────────────────────────┘
                             │
┌────────────────────────────▼────────────────────────────────┐
│                  StowRsServiceImpl                           │
│  - Parse multipart request (Mime4jHybridParser)             │
│  - Delegate to strategy                                      │
│  - Build JSON response (grouped by study)                    │
│  - Handle concurrent build coordination                      │
└────────────────────────────┬────────────────────────────────┘
                             │
        ┌────────────────────┴────────────────────┐
        │                                         │
┌───────▼──────────┐                    ┌────────▼─────────┐
│ GradualDicomImporter │                │  DirectArchive    │
│    Strategy          │                │    Strategy       │
│                      │                │  (Experimental)   │
│ - FileWriterWrapper  │                │                   │
│ - GradualDicomImporter │              │ - DirectArchive   │
│ - PrearcDatabase     │                │   SessionService  │
│ - Build/Archive      │                │                   │
└──────────────────────┘                └───────────────────┘
```

## Key Implementation Files

| File | Description |
|------|-------------|
| `StowRsApi.java` | REST controller for STOW-RS endpoint |
| `StowRsServiceImpl.java` | Service layer with concurrent build management |
| `GradualDicomImporterStrategy.java` | Default import strategy |
| `DirectArchiveStrategy.java` | Experimental direct archive strategy |
| `Mime4jHybridParser.java` | Multipart parser (hybrid memory/disk) |
| `InputStreamFileWriterWrapper.java` | FileWriterWrapper for DICOM instances |
| `SuccessfulInstance.java` | Success instance data holder |
| `FailedInstance.java` | Failed instance data holder |

## Multipart Parsing

Uses custom `Mime4jHybridParser` based on Apache Mime4J:

- **Memory threshold**: 10MB (configurable)
- **Small files** (<10MB): Kept in memory for performance
- **Large files** (>10MB): Written to temp directory
- **Cleanup**: Automatic cleanup after request processing

## Concurrent Upload Handling

### GradualDicomImporter Strategy

1. **Import Future Tracking**: Each upload thread registers a CompletableFuture
2. **Session Key Grouping**: Uploads grouped by `project/sessionName`
3. **Last Activity Time**: Tracks when last upload completed per session
4. **Build Delay**: Waits 500ms after last activity before building
5. **Build Lock**: Only one thread performs the build per session

### File Naming

Uses UUID-based filenames to prevent collisions:
```java
this.name = UUID.randomUUID().toString() + ".dcm";
```

## Error Handling

### HTTP Status Codes

| Code | Meaning |
|------|---------|
| 200 | All instances stored successfully |
| 400 | Invalid request format |
| 403 | Project not found or no access |
| 409 | Conflict (partial failure) |
| 500 | Server error |

### DICOM Failure Codes

| Code | Meaning |
|------|---------|
| 0x0110 | Processing failure |
| 0xA900 | Data set does not match SOP class |
| 0xC000 | Cannot understand |

## Testing

### Regression Test Suite

Run after any refactoring:

```bash
./test-stowrs-suite.sh
```

**Test Cases:**

| # | Test | Description |
|---|------|-------------|
| 1 | Single file upload | Basic functionality |
| 2 | Multi-file same study | Batch upload |
| 3 | Multi-patient single request | Response grouping by study |
| 4 | Concurrent uploads (3 threads) | Thread safety |
| 5 | Invalid file rejection | Error handling |
| 6 | DirectArchive strategy | Alternative strategy |
| 7 | Mixed success/failure | Partial success |

**All 7 tests must pass before committing changes.**

### Manual Testing

```bash
# Single file upload
curl -u admin:admin -X POST \
  -H "Content-Type: multipart/related; type=\"application/dicom\"; boundary=myboundary" \
  --data-binary @request.multipart \
  "http://localhost:8080/xapi/dicomweb/projects/TestProject/studies"

# With DirectArchive strategy
curl -u admin:admin -X POST \
  -H "Content-Type: multipart/related; type=\"application/dicom\"; boundary=myboundary" \
  --data-binary @request.multipart \
  "http://localhost:8080/xapi/dicomweb/projects/TestProject/studies?strategy=DirectArchive"
```

## Configuration

### DicomWebProperties

```yaml
dicomweb:
  multipart:
    memoryThreshold: 10485760  # 10MB
```

## Known Limitations

1. **No Study-level endpoint**: Only project-level endpoint is supported (`/projects/{projectId}/studies`)
2. **No DICOM JSON+Bulkdata format**: Only `multipart/related; type="application/dicom"` is supported
3. **No duplicate detection**: Uploading same instance twice creates duplicates
4. **DirectArchive limitations**: See "DirectArchive (Experimental)" section above

## Future Enhancements

1. **Study-level endpoint**: `POST /studies/{studyUID}` with UID validation
2. **Duplicate detection**: Check existing SOP Instance UIDs
3. **DICOM JSON+Bulkdata**: Support metadata + bulkdata format
4. **Warning codes**: Return warnings for data coercion
5. **Progress tracking**: WebSocket-based upload progress
6. **Async uploads**: Return 202 Accepted for large uploads

## References

- [DICOM PS3.18 Section 10.5 - STOW-RS](https://dicom.nema.org/medical/dicom/current/output/html/part18.html#sect_10.5)
- [DICOMweb Standard](https://www.dicomstandard.org/using/dicomweb/store-stow-rs)
