# QIDO-RS Implementation

## Status: Implemented

**Version:** 1.1.3
**Last Updated:** December 11, 2025

This document describes the QIDO-RS (Query based on ID for DICOM Objects over RESTful Services) implementation for the XNAT DICOMweb Proxy Plugin.

## Overview

QIDO-RS enables searching for DICOM studies, series, and instances in XNAT projects via RESTful HTTP GET requests. The implementation provides DICOMweb-compliant query capabilities with support for filtering, pagination, and DICOM attribute matching.

## API Endpoints

All endpoints require authentication and appropriate read permissions on the project.

### 1. Search for Studies

```
GET /xapi/dicomweb/projects/{projectId}/studies
```

**Accept:** `application/dicom+json` (default)
**Response:** JSON array of study-level DICOM attributes

**Query Parameters:**
- `PatientName` - Patient name (wildcards supported: `*`, `?`)
- `PatientID` - Patient identifier
- `StudyDate` - Study date (YYYYMMDD or range: `YYYYMMDD-YYYYMMDD`)
- `StudyTime` - Study time (HHMMSS)
- `StudyInstanceUID` - Study instance UID
- `AccessionNumber` - Accession number
- `Modality` - Modality (e.g., `CT`, `MR`)
- `limit` - Maximum results per page (default: 100, max: 1000)
- `offset` - Number of results to skip (default: 0)

**Example:**
```bash
# Search all studies
curl -u admin:admin \
  "http://localhost:8080/xapi/dicomweb/projects/MyProject/studies"

# Search by patient name with wildcards
curl -u admin:admin \
  "http://localhost:8080/xapi/dicomweb/projects/MyProject/studies?PatientName=DOE*"

# Search by modality with pagination
curl -u admin:admin \
  "http://localhost:8080/xapi/dicomweb/projects/MyProject/studies?Modality=CT&limit=50&offset=0"

# Search by study date range
curl -u admin:admin \
  "http://localhost:8080/xapi/dicomweb/projects/MyProject/studies?StudyDate=20240101-20241231"
```

**Response:**
```json
[
  {
    "00080020": {"vr": "DA", "Value": ["20241201"]},
    "00080030": {"vr": "TM", "Value": ["143022"]},
    "00080050": {"vr": "SH", "Value": ["ACC123456"]},
    "00080060": {"vr": "CS", "Value": ["CT"]},
    "00080061": {"vr": "CS", "Value": ["CT\\MR"]},
    "0008103E": {"vr": "LO", "Value": ["CT HEAD"]},
    "00100010": {"vr": "PN", "Value": [{"Alphabetic": "DOE^JOHN"}]},
    "00100020": {"vr": "LO", "Value": ["P12345"]},
    "0020000D": {"vr": "UI", "Value": ["1.2.840.113619.2.55.3.12345"]},
    "00201206": {"vr": "IS", "Value": ["1"]},
    "00201208": {"vr": "IS", "Value": ["150"]}
  }
]
```

**Response Headers:**
```
Content-Type: application/dicom+json
X-Total-Count: 42
```

The `X-Total-Count` header provides the total number of matching results (before pagination).

---

### 2. Search for Series in a Study

```
GET /xapi/dicomweb/projects/{projectId}/studies/{studyUID}/series
```

**Accept:** `application/dicom+json`
**Response:** JSON array of series-level DICOM attributes

**Query Parameters:**
- `Modality` - Series modality
- `SeriesDescription` - Series description (wildcards supported)
- `SeriesInstanceUID` - Series instance UID
- `SeriesNumber` - Series number
- `limit` - Maximum results per page
- `offset` - Number of results to skip

**Example:**
```bash
# Get all series in a study
curl -u admin:admin \
  "http://localhost:8080/xapi/dicomweb/projects/MyProject/studies/1.2.840.113619.2.55.3.12345/series"

# Filter by modality
curl -u admin:admin \
  "http://localhost:8080/xapi/dicomweb/projects/MyProject/studies/1.2.840.113619.2.55.3.12345/series?Modality=CT"

# Filter by series description with wildcard
curl -u admin:admin \
  "http://localhost:8080/xapi/dicomweb/projects/MyProject/studies/1.2.840.113619.2.55.3.12345/series?SeriesDescription=*HEAD*"
```

**Response:**
```json
[
  {
    "00080060": {"vr": "CS", "Value": ["CT"]},
    "0008103E": {"vr": "LO", "Value": ["CT HEAD AXIAL"]},
    "0020000E": {"vr": "UI", "Value": ["1.2.840.113619.2.55.3.67890"]},
    "00200011": {"vr": "IS", "Value": ["4"]},
    "00201209": {"vr": "IS", "Value": ["150"]}
  }
]
```

---

### 3. Search for Instances in a Series

```
GET /xapi/dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/instances
```

**Accept:** `application/dicom+json`
**Response:** JSON array of instance-level DICOM attributes

**Query Parameters:**
- `SOPInstanceUID` - SOP instance UID
- `SOPClassUID` - SOP class UID
- `InstanceNumber` - Instance number
- `limit` - Maximum results per page
- `offset` - Number of results to skip

**Example:**
```bash
# Get all instances in a series
curl -u admin:admin \
  "http://localhost:8080/xapi/dicomweb/projects/MyProject/studies/1.2.840.113619.2.55.3.12345/series/1.2.840.113619.2.55.3.67890/instances"

# Filter by instance number range with pagination
curl -u admin:admin \
  "http://localhost:8080/xapi/dicomweb/projects/MyProject/.../instances?limit=10&offset=0"
```

**Response:**
```json
[
  {
    "00080016": {"vr": "UI", "Value": ["1.2.840.10008.5.1.4.1.1.2"]},
    "00080018": {"vr": "UI", "Value": ["1.2.840.113619.2.55.3.99999"]},
    "00200013": {"vr": "IS", "Value": ["1"]},
    "00280010": {"vr": "US", "Value": [512]},
    "00280011": {"vr": "US", "Value": [512]}
  }
]
```

---

## Query Parameter Support

### Supported Matching

QIDO-RS supports the following DICOM attribute query parameters:

| Query Parameter | DICOM Tag | VR | Endpoint | Notes |
|----------------|-----------|-----|----------|-------|
| `PatientName` | `(0010,0010)` | PN | Studies | Wildcards: `*`, `?` |
| `PatientID` | `(0010,0020)` | LO | Studies | Exact match |
| `StudyDate` | `(0008,0020)` | DA | Studies | Range: `YYYYMMDD-YYYYMMDD` |
| `StudyTime` | `(0008,0030)` | TM | Studies | Range: `HHMMSS-HHMMSS` |
| `StudyInstanceUID` | `(0020,000D)` | UI | Studies | Exact match |
| `AccessionNumber` | `(0008,0050)` | SH | Studies | Exact match |
| `Modality` | `(0008,0060)` | CS | Studies, Series | Exact match |
| `SeriesDescription` | `(0008,103E)` | LO | Series | Wildcards supported |
| `SeriesInstanceUID` | `(0020,000E)` | UI | Series | Exact match |
| `SeriesNumber` | `(0020,0011)` | IS | Series | Exact match |
| `SOPInstanceUID` | `(0008,0018)` | UI | Instances | Exact match |
| `SOPClassUID` | `(0008,0016)` | UI | Instances | Exact match |
| `InstanceNumber` | `(0020,0013)` | IS | Instances | Exact match |

### Wildcards

Wildcard matching is supported for string fields:
- `*` - Matches zero or more characters
- `?` - Matches exactly one character

**Examples:**
```bash
# Match any patient name starting with "DOE"
PatientName=DOE*

# Match patient names like "SMITH A" or "SMITH B"
PatientName=SMITH%20?

# Match series descriptions containing "HEAD"
SeriesDescription=*HEAD*
```

### Date/Time Ranges

Date and time fields support range queries:

```bash
# Studies from January 2024
StudyDate=20240101-20240131

# Studies from specific time range
StudyTime=080000-170000

# Combined date and time
StudyDate=20240101-20240131&StudyTime=080000-170000
```

---

## Pagination

QIDO-RS implements pagination to manage large result sets efficiently.

### Parameters

- **`limit`** - Maximum number of results to return per page
  - Default: 100
  - Maximum: 1000
  - Invalid values fallback to default

- **`offset`** - Number of results to skip
  - Default: 0
  - Negative values fallback to 0

### Response Headers

- **`X-Total-Count`** - Total number of matching results (before pagination)

### Pagination Example

```bash
# Get first 50 results
curl -u admin:admin \
  "http://localhost:8080/xapi/dicomweb/projects/MyProject/studies?limit=50&offset=0"
# Response Header: X-Total-Count: 250

# Get next 50 results
curl -u admin:admin \
  "http://localhost:8080/xapi/dicomweb/projects/MyProject/studies?limit=50&offset=50"

# Get results 101-150
curl -u admin:admin \
  "http://localhost:8080/xapi/dicomweb/projects/MyProject/studies?limit=50&offset=100"
```

### Validation

| Scenario | Behavior |
|----------|----------|
| `limit` not specified | Use default (100) |
| `limit <= 0` | Use default (100) |
| `limit > maxPageSize` | Use maximum (1000) |
| `offset` not specified | Use default (0) |
| `offset < 0` | Use default (0) |
| `offset >= total` | Return empty array |

---

## Response Format

### Content Type

All QIDO-RS responses use:
```
Content-Type: application/dicom+json
```

This is the standard DICOMweb JSON representation defined in DICOM PS3.18.

### DICOM JSON Structure

Each DICOM attribute is represented as:
```json
{
  "GGGGEEEE": {
    "vr": "VR",
    "Value": [value1, value2, ...]
  }
}
```

Where:
- `GGGGEEEE` - DICOM tag in hexadecimal (e.g., `00100010` for PatientName)
- `vr` - Value Representation (e.g., `PN`, `DA`, `UI`)
- `Value` - Array of values (format depends on VR)

### Value Representation Examples

**Person Name (PN):**
```json
{
  "00100010": {
    "vr": "PN",
    "Value": [{"Alphabetic": "DOE^JOHN"}]
  }
}
```

**Date (DA):**
```json
{
  "00080020": {
    "vr": "DA",
    "Value": ["20241201"]
  }
}
```

**Unique Identifier (UI):**
```json
{
  "0020000D": {
    "vr": "UI",
    "Value": ["1.2.840.113619.2.55.3.12345"]
  }
}
```

**Integer String (IS):**
```json
{
  "00200011": {
    "vr": "IS",
    "Value": ["4"]
  }
}
```

**Unsigned Short (US):**
```json
{
  "00280010": {
    "vr": "US",
    "Value": [512]
  }
}
```

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    QidoRsApi (REST)                          │
│  - Parse and validate query parameters                      │
│  - Convert HTTP params to DICOM Attributes                  │
│  - Apply pagination (offset/limit)                          │
│  - Return JSON with X-Total-Count header                    │
└────────────────────────────┬────────────────────────────────┘
                             │
┌────────────────────────────▼────────────────────────────────┐
│               XnatDicomServiceImpl                           │
│  - Search XNAT database for sessions/scans                   │
│  - Match against query attributes                            │
│  - Locate DICOM files in archive/prearchive                  │
│  - Read DICOM metadata using dcm4che                         │
│  - Return complete result set (before pagination)            │
└────────────────────────────┬────────────────────────────────┘
                             │
        ┌────────────────────┴────────────────────┐
        │                                         │
┌───────▼──────────┐                    ┌────────▼─────────┐
│ XNAT Data Access │                    │  DICOM Processing│
│                  │                    │                  │
│ - XnatProjectdata│                    │ - dcm4che        │
│ - XnatImagesession│                   │ - DicomInputStream│
│ - XnatImagescandata│                  │ - Attributes     │
│ - Database query │                    │ - Tag matching   │
│ - File system    │                    │ - Wildcard match │
└──────────────────┘                    └──────────────────┘
```

## Query Processing Flow

```
1. HTTP Request
   ↓
2. Parse Query Parameters → DICOM Attributes
   ↓
3. Search XNAT Database
   ↓
4. For each matching session/scan:
   - Locate DICOM files
   - Read metadata
   - Apply DICOM attribute filtering
   ↓
5. Collect all matching results
   ↓
6. Apply pagination (offset/limit)
   ↓
7. Convert to DICOM JSON
   ↓
8. Return response with X-Total-Count
```

---

## Key Implementation Files

| File | Description | Location |
|------|-------------|----------|
| `QidoRsApi.java` | REST controller for QIDO-RS endpoints | `rest/QidoRsApi.java:126-277` |
| `XnatDicomServiceImpl.java` | Service layer with search logic | `service/impl/XnatDicomServiceImpl.java` |
| `XnatDicomService.java` | Service interface | `service/XnatDicomService.java` |
| `DicomWebUtils.java` | DICOM to JSON conversion | `utils/DicomWebUtils.java` |
| `DicomWebProperties.java` | Configuration (pagination defaults) | `config/DicomWebProperties.java` |

---

## File Access Patterns

### Search Strategy

1. **Query XNAT Database:**
   - Use `XnatProjectdata` to validate project access
   - Use `XnatImagesessiondata` to find sessions (studies)
   - Use `XnatImagescandata` to find scans (series)

2. **Locate Files:**
   - Check archive: `{xnat.home}/archive/{project}/arc001/{session}/SCANS/{seriesNumber}/...`
   - Check prearchive: `{xnat.home}/prearchive/{project}/{timestamp}/{session}/SCANS/{seriesNumber}/...`

3. **Read Metadata:**
   - Use dcm4che `DicomInputStream` for efficient parsing
   - Extract only requested attributes
   - Apply query filters

### Archive Structure

```
{xnat.home}/archive/
  {projectId}/
    arc001/
      {sessionLabel}/
        SCANS/
          {seriesNumber}/
            {scanId}/
              DICOM/
                *.dcm
```

### Prearchive Structure

```
{xnat.home}/prearchive/
  {projectId}/
    {timestamp}/
      {sessionName}/
        SCANS/
          {seriesNumber}/
            *.dcm
```

---

## Performance Considerations

### Database Queries

- **Indexed fields:** StudyInstanceUID, SeriesInstanceUID, SOPInstanceUID
- **Non-indexed fields:** May require full scan reads
- **Project filtering:** Applied at database level (efficient)

### File I/O

- **Study search:** Reads one file per series for metadata
- **Series search:** Reads one file per series in study
- **Instance search:** Reads all files in series
- **Pagination:** Applied after all files are read (not I/O optimized)

### Optimization Tips

1. **Use specific UIDs when known:**
   ```bash
   # Fast - indexed lookup
   ?StudyInstanceUID=1.2.3.4.5

   # Slower - requires file reads
   ?PatientName=DOE*
   ```

2. **Limit result sets:**
   ```bash
   # Good - limits processing
   ?limit=50

   # Avoid - processes all results
   ?limit=10000
   ```

3. **Filter early:**
   ```bash
   # Better - filters at database level
   ?Modality=CT&StudyDate=20241201

   # Slower - filters after file reads
   ?PatientName=*&limit=10
   ```

### Performance Metrics

Typical response times (local XNAT):

| Operation | Result Count | Time |
|-----------|--------------|------|
| Study search (by UID) | 1 | ~50ms |
| Study search (all) | 100 | ~2s |
| Series search | 10 | ~200ms |
| Instance search | 150 | ~1s |

---

## Configuration

Pagination settings are configurable via the preference system:

**Default Values:**
```properties
dicomweb.defaultPageSize=100
dicomweb.maxPageSize=1000
```

**Modifying via API:**
```bash
curl -u admin:admin -X POST \
  -H "Content-Type: application/json" \
  -d '{"defaultPageSize": 50, "maxPageSize": 500}' \
  "http://localhost:8080/xapi/dicomweb/prefs"
```

See `CLAUDE.md` for full preference system documentation.

---

## Testing

The plugin includes a comprehensive QIDO-RS test suite:

```bash
./test/test-qido-suite.sh
```

**Test Coverage:**

| Test | Description | Validates |
|------|-------------|-----------|
| 1 | Search all studies | Basic functionality |
| 2 | Search studies with PatientName | Wildcard matching |
| 3 | Search studies with pagination | Offset/limit |
| 4 | Search series in study | Series-level search |
| 5 | Search series with Modality filter | Attribute filtering |
| 6 | Search instances in series | Instance-level search |
| 7 | Verify X-Total-Count header | Pagination metadata |
| 8 | Invalid project (404) | Error handling |
| 9 | Invalid study UID (404) | Not found handling |
| 10 | Limit validation | Boundary conditions |

**Running Tests:**
```bash
# All QIDO-RS tests
./test/test-qido-suite.sh

# Specific test
curl -u admin:admin \
  "http://localhost:8080/xapi/dicomweb/projects/TestProject/studies?limit=10"
```

---

## Error Handling

### HTTP Status Codes

| Code | Meaning | Example |
|------|---------|---------|
| 200 | Success | Results found (may be empty array) |
| 400 | Bad Request | Invalid query parameter format |
| 401 | Unauthorized | Not authenticated |
| 403 | Forbidden | No read permission on project |
| 404 | Not Found | Project/study/series doesn't exist |
| 500 | Internal Server Error | Unexpected error |

### Error Response Format

```json
{
  "error": "ErrorCode",
  "message": "Human-readable description",
  "status": 404,
  "requestId": "unique-request-id",
  "timestamp": "2025-12-11T18:00:00.000Z",
  "path": "/xapi/dicomweb/projects/..."
}
```

### Common Error Scenarios

**Project Not Found:**
```json
{
  "error": "ProjectNotFound",
  "message": "Project 'MyProject' not found or access denied",
  "status": 404
}
```

**Invalid Pagination:**
```bash
# Non-numeric limit - fallback to default
?limit=abc
# Warning logged, returns default page size (100)

# Negative offset - fallback to 0
?offset=-5
# Warning logged, returns offset 0
```

**No Results:**
```json
[]
```
*Note: Empty results return HTTP 200 with empty array, not 404*

---

## DICOMweb Compliance

This implementation follows:

- **DICOM PS3.18** - Web Services (Section 6.7 QIDO-RS)
- **DICOMweb Standard** - QIDO-RS specification

**Compliant Features:**
- ✅ Study/series/instance level searches
- ✅ Query parameter matching
- ✅ Wildcard support (`*`, `?`)
- ✅ Date/time range queries
- ✅ DICOM JSON response format
- ✅ Pagination via `limit`/`offset`
- ✅ Proper HTTP status codes
- ✅ `X-Total-Count` response header

**Limitations:**
- Query parameters are mapped by name (not DICOM tag), e.g., `PatientName` not `00100010`
- Fuzzy matching not implemented
- includefield parameter not supported (returns full attributes)
- Search across all projects not supported (project scope required)
- Date range matching requires exact format (`YYYYMMDD-YYYYMMDD`)

**Extension:**
- `X-Total-Count` header (non-standard but widely used)
- `/projects/{projectId}` path prefix (XNAT-specific scoping)

---

## DICOM Attribute Mapping

The service maps XNAT data model to DICOM attributes:

### Study-Level Attributes

| DICOM Tag | Tag Name | VR | Source |
|-----------|----------|-----|--------|
| `(0020,000D)` | StudyInstanceUID | UI | `session.getUid()` |
| `(0008,0020)` | StudyDate | DA | `session.getDate()` |
| `(0008,0030)` | StudyTime | TM | DICOM file |
| `(0008,0050)` | AccessionNumber | SH | DICOM file |
| `(0008,1030)` | StudyDescription | LO | `session.getLabel()` |
| `(0010,0010)` | PatientName | PN | DICOM file |
| `(0010,0020)` | PatientID | LO | DICOM file |
| `(0020,1206)` | NumberOfStudyRelatedSeries | IS | Count of scans |
| `(0020,1208)` | NumberOfStudyRelatedInstances | IS | Count of files |

### Series-Level Attributes

| DICOM Tag | Tag Name | VR | Source |
|-----------|----------|-----|--------|
| `(0020,000E)` | SeriesInstanceUID | UI | `scan.getUid()` |
| `(0008,0060)` | Modality | CS | `scan.getModality()` |
| `(0008,103E)` | SeriesDescription | LO | `scan.getSeriesDescription()` |
| `(0020,0011)` | SeriesNumber | IS | DICOM file |
| `(0020,1209)` | NumberOfSeriesRelatedInstances | IS | Count of files |

### Instance-Level Attributes

| DICOM Tag | Tag Name | VR | Source |
|-----------|----------|-----|--------|
| `(0008,0018)` | SOPInstanceUID | UI | DICOM file |
| `(0008,0016)` | SOPClassUID | UI | DICOM file |
| `(0020,0013)` | InstanceNumber | IS | DICOM file |
| `(0028,0010)` | Rows | US | DICOM file |
| `(0028,0011)` | Columns | US | DICOM file |

For complete metadata, the service reads from actual DICOM files stored in XNAT's archive/prearchive.

---

## Client Integration Examples

### JavaScript (OHIF Viewer)

```javascript
// QIDO-RS study search
const response = await fetch(
  'http://localhost:8080/xapi/dicomweb/projects/MyProject/studies?limit=50',
  {
    headers: {
      'Authorization': 'Basic ' + btoa('username:password'),
      'Accept': 'application/dicom+json'
    }
  }
);

const studies = await response.json();
const totalCount = response.headers.get('X-Total-Count');

console.log(`Found ${totalCount} studies, showing ${studies.length}`);
```

### Python (pydicom)

```python
import requests
from requests.auth import HTTPBasicAuth

# Search for CT studies
response = requests.get(
    'http://localhost:8080/xapi/dicomweb/projects/MyProject/studies',
    params={'Modality': 'CT', 'limit': 100},
    auth=HTTPBasicAuth('admin', 'admin'),
    headers={'Accept': 'application/dicom+json'}
)

studies = response.json()
total_count = int(response.headers['X-Total-Count'])

for study in studies:
    study_uid = study['0020000D']['Value'][0]
    patient_name = study['00100010']['Value'][0]['Alphabetic']
    print(f"{patient_name}: {study_uid}")
```

### curl (Command Line)

```bash
#!/bin/bash
# Search studies with pagination

PROJECT="MyProject"
LIMIT=50
OFFSET=0
BASE_URL="http://localhost:8080/xapi/dicomweb/projects/$PROJECT/studies"

while true; do
  RESPONSE=$(curl -s -u admin:admin \
    -H "Accept: application/dicom+json" \
    "$BASE_URL?limit=$LIMIT&offset=$OFFSET")

  COUNT=$(echo "$RESPONSE" | jq '. | length')

  if [ "$COUNT" -eq 0 ]; then
    break
  fi

  echo "Processing $COUNT studies (offset: $OFFSET)"
  echo "$RESPONSE" | jq -r '.[] | .["0020000D"].Value[0]'

  OFFSET=$((OFFSET + LIMIT))
done
```

---

## Future Enhancements

Potential improvements for future versions:

1. **Extended Query Support**
   - `includefield` parameter for partial attribute retrieval
   - `fuzzymatching` for approximate string matching
   - Sequence attribute matching

2. **Performance Optimizations**
   - Database-level pagination (avoid reading all files)
   - Metadata caching for frequently accessed studies
   - Parallel file reading for large result sets

3. **Advanced Filtering**
   - Numeric range queries (e.g., `SeriesNumber=1-10`)
   - Regular expression matching
   - Composite queries across multiple attributes

4. **Standards Compliance**
   - Support tag-based query parameters (e.g., `00100010=DOE*`)
   - Implement `dicomKeyword` query syntax
   - Add `Warning` headers for unsupported parameters

5. **Multi-Project Search**
   - Global search endpoint: `/xapi/dicomweb/studies`
   - Cross-project result aggregation
   - Unified result ranking

---

## References

- [DICOM PS3.18 - Web Services](https://dicom.nema.org/medical/dicom/current/output/html/part18.html)
- [DICOMweb Standard - QIDO-RS](https://www.dicomstandard.org/using/dicomweb/query-qido-rs)
- [DICOM JSON Representation](https://dicom.nema.org/medical/dicom/current/output/html/part18.html#sect_F.2)
- [dcm4che DICOM Toolkit](https://github.com/dcm4che/dcm4che)
- [XNAT Documentation](https://wiki.xnat.org)

---

## See Also

- `WADO_RS_IMPLEMENTATION.md` - Retrieve implementation
- `STOW_RS_IMPLEMENTATION_PLAN.md` - Upload implementation
- `DICOMWEB_CONFORMANCE.md` - Full conformance statement
- `README.md` - Plugin overview and setup
- `CLAUDE.md` - Development guide
