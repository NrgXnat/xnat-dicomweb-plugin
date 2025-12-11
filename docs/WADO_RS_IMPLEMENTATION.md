# WADO-RS Implementation

## Status: Implemented

**Version:** 1.1.3
**Last Updated:** December 11, 2025

This document describes the WADO-RS (Web Access to DICOM Objects by RESTful Services) implementation for the XNAT DICOMweb Proxy Plugin.

## Overview

WADO-RS enables retrieval of DICOM instances, metadata, rendered images, frames, and bulk data from XNAT via RESTful HTTP GET requests. The implementation provides full DICOMweb-compliant access to stored DICOM data.

## API Endpoints

All endpoints require authentication and appropriate read permissions on the project.

### 1. Retrieve Study (Multipart DICOM)

```
GET /xapi/dicomweb/projects/{projectId}/studies/{studyUID}
```

**Accept:** `multipart/related; type="application/dicom"`
**Response:** All instances in the study as `multipart/related` with DICOM files

**Example:**
```bash
curl -u admin:admin \
  -H "Accept: multipart/related; type=application/dicom" \
  "http://localhost:8080/xapi/dicomweb/projects/MyProject/studies/1.2.3.4.5"
```

**Response:**
```
Content-Type: multipart/related; type="application/dicom"; boundary=abc123

--abc123
Content-Type: application/dicom

<DICOM binary data for instance 1>
--abc123
Content-Type: application/dicom

<DICOM binary data for instance 2>
--abc123--
```

---

### 2. Retrieve Series (Multipart DICOM)

```
GET /xapi/dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}
```

**Accept:** `multipart/related; type="application/dicom"`
**Response:** All instances in the series as `multipart/related` with DICOM files

---

### 3. Retrieve Instance (Single DICOM)

```
GET /xapi/dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}
```

**Accept:** `application/dicom`
**Response:** Single DICOM file

**Example:**
```bash
curl -u admin:admin \
  "http://localhost:8080/xapi/dicomweb/projects/MyProject/studies/1.2.3.4.5/series/1.2.3.4.6/instances/1.2.3.4.7" \
  -o instance.dcm
```

---

### 4. Retrieve Study Metadata

```
GET /xapi/dicomweb/projects/{projectId}/studies/{studyUID}/metadata
```

**Accept:** `application/dicom+json` (default) or `application/dicom+xml`
**Response:** JSON or XML array containing metadata for all instances in the study

**Response Format (JSON):**
```json
[
  {
    "00080018": {"vr": "UI", "Value": ["1.2.3.4.5.6"]},
    "00080060": {"vr": "CS", "Value": ["CT"]},
    "00100010": {"vr": "PN", "Value": [{"Alphabetic": "DOE^JOHN"}]},
    "7FE00010": {
      "vr": "OB",
      "BulkDataURI": "http://host/xapi/dicomweb/.../bulkdata/7FE00010"
    }
  }
]
```

**BulkDataURI Substitution:**
- Large binary attributes (>1KB) like PixelData are replaced with BulkDataURI references
- Enables efficient metadata retrieval without transferring large binary data
- URIs point to the bulk data retrieval endpoint

---

### 5. Retrieve Series Metadata

```
GET /xapi/dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/metadata
```

**Accept:** `application/dicom+json` or `application/dicom+xml`
**Response:** Metadata for all instances in the series

---

### 6. Retrieve Instance Metadata

```
GET /xapi/dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}/metadata
```

**Accept:** `application/dicom+json` or `application/dicom+xml`
**Response:** Metadata for a single instance

---

### 7. Retrieve Rendered Instance (JPEG/PNG/GIF)

```
GET /xapi/dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}/rendered
```

**Accept:** `image/jpeg`, `image/png`, or `image/gif`
**Query Parameters:**
- `frame` (optional): Frame number for multi-frame images (1-based)

**Response:** Rendered image in requested format

**Multi-Frame Handling:**
- **JPEG/PNG:** Returns single frame (default: middle frame)
- **GIF with multi-frame:** Returns animated GIF with all frames
- **Frame selection:** Use `?frame=N` to select specific frame

**Response Headers:**
```
Content-Type: image/jpeg
X-Frame-Count: 10
X-Frame-Number: 5
X-Frame-Rate: 30.00
X-Multi-Frame: true
```

**Supported Compression Formats:**

| Transfer Syntax | Status | Requirements |
|----------------|--------|--------------|
| Uncompressed | ✅ Fully supported | None |
| JPEG Baseline | ✅ Fully supported | None |
| JPEG Extended | ✅ Fully supported | None |
| JPEG Lossless | ✅ Fully supported | None |
| RLE Lossless | ✅ Fully supported | None |
| JPEG-LS | ⚠️ Requires native libraries | OpenCV |
| JPEG 2000 | ⚠️ Requires native libraries | OpenCV |

**Example:**
```bash
# Get rendered JPEG (default: middle frame for multi-frame)
curl -u admin:admin \
  "http://localhost:8080/xapi/dicomweb/projects/MyProject/.../rendered" \
  -o rendered.jpg

# Get specific frame
curl -u admin:admin \
  "http://localhost:8080/xapi/dicomweb/projects/MyProject/.../rendered?frame=5" \
  -o frame5.jpg

# Get animated GIF (multi-frame)
curl -u admin:admin \
  -H "Accept: image/gif" \
  "http://localhost:8080/xapi/dicomweb/projects/MyProject/.../rendered" \
  -o animated.gif
```

**Error Handling for Advanced Compression:**

If the DICOM file uses JPEG-LS or JPEG 2000 compression and OpenCV native libraries are not installed:

```json
{
  "error": "UnsupportedOperation",
  "message": "Cannot render image. This DICOM file uses compression formats (JPEG-LS or JPEG 2000) that require OpenCV native libraries. Install OpenCV (macOS: 'brew install opencv', Ubuntu: 'apt-get install libopencv-dev') or use the retrieveInstance endpoint to download the original DICOM file.",
  "status": 501,
  "requestId": "abc-123-def"
}
```

---

### 8. Retrieve Frames

```
GET /xapi/dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}/frames/{frameList}
```

**frameList:** Comma-separated list of frame numbers (1-based), e.g., `1`, `1,3,5`

**Response:**
- **Single frame:** `application/octet-stream` with raw frame data
- **Multiple frames:** `multipart/related` with multiple frame parts

**Example:**
```bash
# Get single frame
curl -u admin:admin \
  "http://localhost:8080/xapi/dicomweb/projects/MyProject/.../frames/1" \
  -o frame1.bin

# Get multiple frames
curl -u admin:admin \
  "http://localhost:8080/xapi/dicomweb/projects/MyProject/.../frames/1,3,5" \
  -o frames.multipart
```

---

### 9. Retrieve Bulk Data

```
GET /xapi/dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}/bulkdata/{tag}
```

**tag:** DICOM tag in hexadecimal format (e.g., `7FE00010` for PixelData)

**Response:** `application/octet-stream` with raw attribute data

**Common Tags:**
- `7FE00010` - PixelData
- `7FE00020` - FloatPixelData
- `7FE00030` - DoubleFloatPixelData
- `52009229` - PixelDataProviderURL (in Enhanced multi-frame)

**Example:**
```bash
# Get PixelData
curl -u admin:admin \
  "http://localhost:8080/xapi/dicomweb/projects/MyProject/.../bulkdata/7FE00010" \
  -o pixeldata.bin
```

## Response Format Summary

| Endpoint | Content-Type | Response Body |
|----------|--------------|---------------|
| Retrieve Study/Series | `multipart/related; type="application/dicom"` | DICOM files |
| Retrieve Instance | `application/dicom` | Single DICOM file |
| Retrieve Metadata | `application/dicom+json` or `application/dicom+xml` | JSON/XML array |
| Retrieve Rendered | `image/jpeg`, `image/png`, or `image/gif` | Image file |
| Retrieve Frames | `application/octet-stream` or `multipart/related` | Frame data |
| Retrieve Bulk Data | `application/octet-stream` | Raw binary data |

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    WadoRsApi (REST)                          │
│  - Validate project access                                   │
│  - Route to appropriate service method                       │
│  - Handle content negotiation (JSON/XML/image)               │
│  - Build multipart responses                                 │
└────────────────────────────┬────────────────────────────────┘
                             │
┌────────────────────────────▼────────────────────────────────┐
│               XnatDicomServiceImpl                           │
│  - Search XNAT database for sessions/scans                   │
│  - Locate DICOM files in archive/prearchive                  │
│  - Read DICOM metadata using dcm4che                         │
│  - Render images (JPEG/PNG/GIF)                              │
│  - Extract frames from multi-frame images                    │
│  - Generate BulkDataURI references                           │
└────────────────────────────┬────────────────────────────────┘
                             │
        ┌────────────────────┴────────────────────┐
        │                                         │
┌───────▼──────────┐                    ┌────────▼─────────┐
│ XNAT Data Access │                    │  DICOM Processing│
│                  │                    │                  │
│ - XnatProjectdata│                    │ - dcm4che        │
│ - XnatImagesession│                   │ - DicomInputStream│
│ - XnatImagescandata│                  │ - ImageIO        │
│ - File system    │                    │ - BufferedImage  │
│   access         │                    │ - BulkDataHandler│
└──────────────────┘                    └───────────────────┘
```

## Key Implementation Files

| File | Description |
|------|-------------|
| `WadoRsApi.java` | REST controller for all WADO-RS endpoints |
| `XnatDicomServiceImpl.java` | Service layer for DICOM operations |
| `XnatDicomService.java` | Service interface |
| `BulkDataHandler.java` | BulkDataURI substitution logic |
| `DicomWebUtils.java` | DICOM to JSON/XML conversion utilities |
| `RenderedInstanceResult.java` | Container for rendered image data |
| `ImageFormat.java` | Enum for supported image formats (JPEG/PNG/GIF) |

## BulkDataURI Substitution

To optimize metadata retrieval, large binary attributes are replaced with URI references:

**Threshold:** 1KB (configurable)

**Process:**
1. Parse DICOM file with bulk data exclusion
2. Identify large binary attributes (PixelData, OverlayData, etc.)
3. Replace with BulkDataURI pointing to bulk data endpoint
4. Return lightweight metadata

**Example:**
```json
{
  "7FE00010": {
    "vr": "OB",
    "BulkDataURI": "http://host/xapi/dicomweb/projects/P1/studies/1.2.3/series/1.2.4/instances/1.2.5/bulkdata/7FE00010"
  }
}
```

## Image Rendering Pipeline

### Single-Frame Images

```
DICOM File → DicomInputStream → ImageIO → BufferedImage → JPEG/PNG/GIF
```

### Multi-Frame Images (JPEG/PNG)

```
DICOM File → DicomInputStream → ImageIO → Select Frame → BufferedImage → JPEG/PNG
```

**Frame Selection:**
- No `frame` parameter: Middle frame (`totalFrames / 2`)
- With `frame=N`: Specific frame (1-based)
- Out of range: Fallback to middle frame

### Multi-Frame Animated GIF

```
DICOM File → DicomInputStream → ImageIO → All Frames → AnimatedGifEncoder → GIF
```

**Frame Rate Detection:**
1. Check `FrameTime (0018,1063)` - milliseconds per frame
2. Check `CineRate (0018,0040)` - frames per second
3. Check `RecommendedDisplayFrameRate (0008,2144)`
4. Default: 10 FPS

## Advanced Compression Format Support

### Overview

The plugin includes built-in support for common DICOM compression formats. Advanced formats (JPEG-LS, JPEG 2000) require additional native libraries.

### Built-in Support (No Additional Setup)

| Format | Transfer Syntax UID | Use Case |
|--------|---------------------|----------|
| Uncompressed | 1.2.840.10008.1.2.x | Legacy, archival |
| JPEG Baseline | 1.2.840.10008.1.2.4.50 | Most common CT/MRI |
| JPEG Extended | 1.2.840.10008.1.2.4.51 | CT/MRI |
| JPEG Lossless | 1.2.840.10008.1.2.4.57/70 | High-quality imaging |
| RLE Lossless | 1.2.840.10008.1.2.5 | Some MRI |

### Advanced Formats (Require OpenCV)

| Format | Transfer Syntax UID | Required Library |
|--------|---------------------|------------------|
| JPEG-LS Lossless | 1.2.840.10008.1.2.4.80 | OpenCV |
| JPEG-LS Near-lossless | 1.2.840.10008.1.2.4.81 | OpenCV |
| JPEG 2000 Lossless | 1.2.840.10008.1.2.4.90 | OpenCV |
| JPEG 2000 Lossy | 1.2.840.10008.1.2.4.91 | OpenCV |

### Graceful Degradation

When encountering advanced compression formats without native libraries:

1. **Rendered endpoint** returns HTTP 501 with installation instructions
2. **RetrieveInstance endpoint** always works (returns original DICOM file)
3. **Metadata endpoints** work normally (no rendering involved)

**Error Response Example:**
```json
{
  "error": "UnsupportedOperation",
  "message": "Cannot render image with transfer syntax: JPEG-LS Lossless. Native OpenCV libraries are not installed on the system. To enable rendering of JPEG-LS and JPEG 2000 images, install OpenCV:\n  • macOS: brew install opencv\n  • Ubuntu/Debian: sudo apt-get install libopencv-dev\n  • CentOS/RHEL: sudo yum install opencv-devel\nAlternatively, use the retrieveInstance endpoint to download the original DICOM file.",
  "status": 501
}
```

### Installation (Optional)

To enable JPEG-LS and JPEG 2000 rendering:

**macOS:**
```bash
brew install opencv
# Restart XNAT
```

**Ubuntu/Debian:**
```bash
sudo apt-get install libopencv-dev
# Restart XNAT
```

**CentOS/RHEL:**
```bash
sudo yum install opencv-devel
# Restart XNAT
```

After installation, the `rendered` and `frames` endpoints will support all compression formats.

## Error Handling

### HTTP Status Codes

| Code | Meaning | Example |
|------|---------|---------|
| 200 | Success | Successfully retrieved data |
| 404 | Not Found | Study/series/instance doesn't exist or no access |
| 400 | Bad Request | Invalid tag format in bulk data request |
| 401 | Unauthorized | Not authenticated |
| 403 | Forbidden | No read permission on project |
| 501 | Not Implemented | Missing codec for advanced compression |
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

**Study Not Found:**
```json
{
  "error": "StudyNotFound",
  "message": "Study with identifier '1.2.3.4.5' not found",
  "status": 404
}
```

**Invalid Bulk Data Tag:**
```json
{
  "error": "BadRequest",
  "message": "tag: must be a valid hexadecimal DICOM tag",
  "status": 400
}
```

**Missing Codec:**
```json
{
  "error": "UnsupportedOperation",
  "message": "Cannot render image. This DICOM file uses compression formats (JPEG-LS or JPEG 2000) that require OpenCV native libraries...",
  "status": 501
}
```

## DICOM Attribute Mapping

The service maps XNAT data model to DICOM attributes:

| XNAT Field | DICOM Tag | Tag Name | VR |
|------------|-----------|----------|-----|
| `session.getUid()` | `(0020,000D)` | StudyInstanceUID | UI |
| `scan.getUid()` | `(0020,000E)` | SeriesInstanceUID | UI |
| `session.getLabel()` | `(0008,1030)` | StudyDescription | LO |
| `scan.getSeriesDescription()` | `(0008,103E)` | SeriesDescription | LO |
| `session.getDate()` | `(0008,0020)` | StudyDate | DA |
| `scan.getModality()` | `(0008,0060)` | Modality | CS |

For complete metadata, the service reads from the actual DICOM files stored in XNAT's archive/prearchive.

## File Access Patterns

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

### Search Strategy

1. Search for session by StudyInstanceUID in XNAT database
2. Locate scan by SeriesInstanceUID
3. Check archive first, then prearchive
4. Read DICOM files from file system

## Performance Considerations

### Metadata Retrieval

- Uses streaming DICOM parsing (dcm4che)
- Bulk data substitution reduces response size
- File I/O is the primary bottleneck

### Image Rendering

- JPEG Baseline: ~50-100ms per image
- Multi-frame GIF: ~100-500ms depending on frame count
- Caching is handled by HTTP layer (ETag, Last-Modified)

### Frame Extraction

- Compressed data: Uses ImageIO decompression
- Uncompressed data: Direct byte array extraction
- Large frames may trigger disk buffering

## Testing

The plugin includes a comprehensive WADO-RS test suite:

```bash
./test/test-wadors-suite.sh
```

**Tests:**
1. Retrieve Study (multipart)
2. Retrieve Series (multipart)
3. Retrieve Instance
4. Retrieve Study Metadata
5. Retrieve Series Metadata
6. Retrieve Instance Metadata
7. Retrieve Rendered Instance (JPEG)
8. Retrieve Frames
9. Retrieve Bulk Data (PixelData)
10. Not Found Handling

All tests should pass on a properly configured XNAT instance with test data.

## DICOMweb Compliance

This implementation follows:

- **DICOM PS3.18** - Web Services
- **DICOMweb Standard** - WADO-RS specification

**Compliant Features:**
- ✅ Multipart/related responses with correct boundaries
- ✅ BulkDataURI substitution for large attributes
- ✅ Content negotiation (JSON/XML/image formats)
- ✅ Proper HTTP status codes
- ✅ Frame retrieval for multi-frame images
- ✅ Rendered image output

**Limitations:**
- Study/Series retrieval returns instances from XNAT sessions, which may span multiple actual DICOM studies/series if data was imported with session merging
- RetrieveURL points to XNAT DICOMweb endpoints (not the original source)

## Future Enhancements

Potential improvements for future versions:

1. **Query Parameter Support**
   - Quality settings for rendered images
   - Viewport specifications for region extraction

2. **Performance Optimizations**
   - Server-side caching for rendered images
   - Parallel frame extraction for multi-frame images

3. **Extended Format Support**
   - Additional image output formats (WebP, AVIF)
   - Video output for multi-frame sequences (MP4, WebM)

4. **Metadata Enhancements**
   - Configurable BulkDataURI threshold
   - Partial metadata retrieval (specific tags)

## References

- [DICOM PS3.18 - Web Services](https://dicom.nema.org/medical/dicom/current/output/html/part18.html)
- [DICOMweb Standard](https://www.dicomstandard.org/using/dicomweb)
- [dcm4che DICOM Toolkit](https://github.com/dcm4che/dcm4che)
- [XNAT Documentation](https://wiki.xnat.org)

## See Also

- `STOW_RS_IMPLEMENTATION_PLAN.md` - Upload implementation
- `QIDO_RS_IMPLEMENTATION.md` - Search implementation (if available)
- `ADVANCED_COMPRESSION_SUPPORT.md` - Codec details
- `README.md` - Plugin overview and setup
