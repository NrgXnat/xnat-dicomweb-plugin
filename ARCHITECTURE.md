# XNAT DICOMweb Plugin - Architecture Documentation

## Overview

The XNAT DICOMweb Plugin provides a standards-compliant DICOMweb REST API that exposes XNAT projects as DICOMweb endpoints. This enables third-party DICOM viewers (OHIF, VolView, etc.) to access XNAT imaging data using standard DICOMweb protocols.

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                      DICOM Viewers                               │
│              (OHIF, VolView, Weasis, etc.)                       │
└────────────────────────┬────────────────────────────────────────┘
                         │ DICOMweb Protocol
                         │ (QIDO-RS, WADO-RS)
┌────────────────────────▼────────────────────────────────────────┐
│                 XNAT DICOMweb Plugin                       │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │              REST API Layer                               │  │
│  │  ┌────────────────┐      ┌────────────────┐             │  │
│  │  │  QidoRsApi     │      │  WadoRsApi     │             │  │
│  │  │  (Search)      │      │  (Retrieve)    │             │  │
│  │  └───────┬────────┘      └────────┬───────┘             │  │
│  └──────────┼───────────────────────┼────────────────────┬─┘  │
│             │                       │                     │    │
│  ┌──────────▼───────────────────────▼──────────────┐     │    │
│  │          Service Layer                           │     │    │
│  │     XnatDicomService / XnatDicomServiceImpl     │     │    │
│  └──────────┬──────────────────────────────────────┘     │    │
│             │                                             │    │
│  ┌──────────▼─────────────────┐  ┌────────────────────┐ │    │
│  │   XNAT Data Models         │  │  DicomWebUtils     │ │    │
│  │  (Projects, Sessions,      │  │  (JSON conversion, │ │    │
│  │   Scans, Resources)        │  │   Content types)   │ │    │
│  └────────────────────────────┘  └────────────────────┘ │    │
│                                                           │    │
│  ┌───────────────────────────────────────────────────────▼──┐ │
│  │              Configuration Layer                          │ │
│  │   DicomWebConfig, CORS, Authentication                   │ │
│  └──────────────────────────────────────────────────────────┘ │
└────────────────────────┬────────────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────────────┐
│                        XNAT Core                                 │
│   (Database, File System, Security, Data Models)                │
└──────────────────────────────────────────────────────────────────┘
```

## Components

### 1. Plugin Entry Point

**File:** `DicomWebPlugin.java`

The main plugin class that registers the plugin with XNAT.

```java
@XnatPlugin(
    value = "dicomwebplugin",
    name = "DICOMweb Plugin",
    entityPackages = "org.nrg.xnat.dicomweb",
    openUrls = {"/dicomweb/**"}
)
```

**Key Features:**
- Registers plugin with XNAT framework
- Enables component scanning for Spring beans
- Declares open URLs (accessible without full authentication for OPTIONS requests)

### 2. REST API Layer

#### QidoRsApi (QIDO-RS - Query)

**File:** `rest/QidoRsApi.java`

Implements DICOMweb QIDO-RS (Query based on ID for DICOM Objects) endpoints.

**Endpoints:**
- `GET /dicomweb/projects/{projectId}/studies` - Search studies
- `GET /dicomweb/projects/{projectId}/studies/{studyUID}/series` - Search series
- `GET /dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/instances` - Search instances

**Response Format:** `application/dicom+json` (DICOM JSON)

**Key Responsibilities:**
- Endpoint routing and parameter validation
- User authentication via XNAT session
- Delegating search to service layer
- Converting DICOM attributes to JSON
- Error handling and HTTP status codes

#### WadoRsApi (WADO-RS - Retrieve)

**File:** `rest/WadoRsApi.java`

Implements DICOMweb WADO-RS (Web Access to DICOM Objects) endpoints.

**Endpoints:**
- `GET /dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}` - Retrieve instance
- `GET /dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}/metadata` - Retrieve metadata
- `GET /dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/instances/{instanceUID}/rendered` - Retrieve rendered JPEG
- `GET /dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}` - Retrieve series
- `GET /dicomweb/projects/{projectId}/studies/{studyUID}/series/{seriesUID}/metadata` - Retrieve series metadata
- `GET /dicomweb/projects/{projectId}/studies/{studyUID}` - Retrieve study

**Response Formats:**
- `application/dicom` for DICOM instances
- `application/dicom+json` for metadata
- `multipart/related` for bulk retrieval
- `image/jpeg` for rendered images

**Key Responsibilities:**
- Streaming DICOM data to clients
- Multipart response generation
- Metadata extraction and JSON conversion
- Efficient handling of large datasets

### 3. Service Layer

**Files:** `service/XnatDicomService.java`, `service/XnatDicomServiceImpl.java`

The service layer abstracts XNAT data access and provides DICOM-specific operations.

**Key Methods:**
- `searchStudies()` - Find studies in a project
- `searchSeries()` - Find series in a study
- `searchInstances()` - Find instances in a series
- `retrieveInstance()` - Get DICOM file stream
- `retrieveMetadata()` - Get DICOM attributes
- `retrieveStudy()` - Get all instances in study
- `retrieveSeries()` - Get all instances in series
- `retrieveRenderedInstance()` - Get rendered JPEG image

**XNAT Mapping:**
```
XNAT                    →  DICOM
─────────────────────────────────────
Project                 →  [Context]
ImageSession            →  Study
ImageScan               →  Series
AbstractResource/Files  →  Instances
```

**Key Features:**
- Translates XNAT data models to DICOM attributes
- Handles XNAT security and permissions
- Reads DICOM files from XNAT's file system
- Renders DICOM images to JPEG format using DCM4CHE ImageIO
- File path resolution based on XNAT archive structure

### 4. Utility Layer

**File:** `utils/DicomWebUtils.java`

Provides utility functions for DICOMweb operations.

**Key Functions:**
- `toJson(Attributes)` - Convert DICOM to JSON
- `readDicom(InputStream)` - Parse DICOM file
- `getDicomJsonContentType()` - Get content type for JSON
- `getMultipartContentType(boundary)` - Get multipart content type

**Dependencies:**
- DCM4CHE3 library for DICOM handling
- javax.json for JSON generation

### 5. Configuration Layer

**File:** `config/DicomWebConfig.java`

Configures Spring beans and application settings.

**Key Configurations:**
- **CORS Configuration**: Enables cross-origin requests for web viewers
  ```java
  allowedOrigins("*")
  allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
  ```
- **Component Scanning**: Enables Spring to discover beans
- **Bean Definitions**: Creates necessary service beans

## Data Flow

### Query Flow (QIDO-RS)

```
1. Client Request
   GET /xapi/dicomweb/projects/PROJECT/studies

2. QidoRsApi.searchStudies()
   - Authenticates user via getSessionUser()
   - Validates project access

3. XnatDicomService.searchStudies()
   - Fetches XnatProjectdata
   - Gets ImageSessions (studies)
   - Iterates through sessions

4. For each session:
   - Extracts StudyInstanceUID
   - Maps XNAT fields to DICOM tags
   - Creates Attributes object

5. DicomWebUtils.toJson()
   - Converts Attributes to DICOM JSON
   - Uses DCM4CHE3 JSONWriter

6. Response
   - Returns JSON array
   - Content-Type: application/dicom+json
```

### Retrieve Flow (WADO-RS)

```
1. Client Request
   GET /xapi/dicomweb/projects/PROJECT/studies/{uid}/series/{uid}/instances/{uid}

2. WadoRsApi.retrieveInstance()
   - Authenticates user
   - Validates parameters

3. XnatDicomService.retrieveInstance()
   - Searches for instance by UID
   - Traverses: Project → Session → Scan → Resource

4. File System Access
   - Locates DICOM file in resource directory
   - Opens FileInputStream

5. Response
   - Streams file directly to client
   - Content-Type: application/dicom
   - No buffering (efficient for large files)
```

## Security Model

### Authentication

The plugin leverages XNAT's built-in authentication:

1. **Session-based**: Users must be authenticated with XNAT
2. **getSessionUser()**: Retrieves current authenticated user
3. **XNAT Security Context**: All data access checks user permissions

### Authorization

Project-level authorization is enforced:

```java
XnatProjectdata project = XnatProjectdata.getXnatProjectdatasById(
    projectId, user, false);
```

- Returns `null` if user doesn't have access
- XNAT checks read permissions automatically
- No additional authorization logic needed

### CORS (Cross-Origin Resource Sharing)

Configured to allow web viewers from any origin:

```java
registry.addMapping("/xapi/dicomweb/**")
    .allowedOrigins("*")
    .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
```

**Note:** In production, restrict `allowedOrigins` to specific domains.

## DICOM Standards Compliance

### QIDO-RS

Compliant with **DICOM PS3.18 Section 10**: Web Services - Query

**Supported Features:**
- Study-level search
- Series-level search
- Instance-level search
- DICOM JSON response format

**Not Yet Implemented:**
- Query parameters (fuzzy matching, date ranges)
- includefield parameter
- Pagination (limit/offset)

### WADO-RS

Compliant with **DICOM PS3.18 Section 9**: Web Services - Retrieve

**Supported Features:**
- Instance retrieval
- Metadata retrieval
- Series retrieval (multipart)
- Study retrieval (multipart)
- Series metadata retrieval
- Rendered image retrieval (JPEG)
- DICOM and DICOM JSON formats

**Not Yet Implemented:**
- Frame retrieval
- Thumbnail generation
- PNG rendering

### STOW-RS (Store)

**Not Implemented:** Write operations are not currently supported.

## Performance Considerations

### Caching

- No caching currently implemented
- Future: Add Redis/Memcached for metadata caching
- Consider caching study/series lists per project

### Streaming

- DICOM files are streamed directly from disk
- No buffering for large files
- Multipart responses built in memory (consider disk-based for large series)

### Database Queries

- Each search triggers XNAT database queries
- Consider batch fetching for multiple studies
- Connection pooling handled by XNAT

### File System Access

- Direct file access via XNAT resource URIs
- No file copying or temporary files
- Ensure adequate I/O bandwidth

## Error Handling

### Error Response Strategy

1. **Not Found (404)**: Resource doesn't exist or user lacks permission
2. **Internal Server Error (500)**: Unexpected errors
3. **OK with Empty Array (200)**: Valid query with no results

### Logging

Uses SLF4J for logging:
- **INFO**: Successful operations
- **WARN**: Access denied, not found
- **ERROR**: Exceptions, system errors

## Extension Points

### Adding New Endpoints

1. Create new method in appropriate API class
2. Annotate with `@XapiRequestMapping`
3. Implement service method if needed
4. Add tests

### Supporting Additional Metadata

1. Extend `XnatDicomService` methods
2. Map XNAT fields to DICOM tags in service impl
3. Update tests

### Custom DICOM Tag Mapping

Edit `XnatDicomServiceImpl` search methods:
```java
attrs.setString(Tag.CustomTag, VR.XX, xnatValue);
```

## Dependencies

### Core Dependencies

- **XNAT Core**: 1.9.x
  - Web framework
  - Data models
  - Security

- **DCM4CHE3**: 5.29.2
  - DICOM parsing
  - JSON conversion
  - Tag definitions
  - Image rendering (dcm4che-image, dcm4che-imageio)

- **Spring Framework**: 5.x
  - REST controllers
  - Dependency injection
  - CORS support

- **Jackson**: 2.13.x
  - JSON processing

### Build Dependencies

- **Gradle**: Build system
- **JUnit**: 4.x
- **Mockito**: 4.x

## Deployment

### Installation

1. Build JAR: `./gradlew jar`
2. Copy to XNAT plugins: `$XNAT_HOME/plugins/`
3. Restart XNAT

### Configuration

Plugin auto-registers on XNAT startup. No additional configuration required.

### Verification

Check XNAT logs:
```
INFO  org.nrg.framework.services.impl.XnatPluginService - Loading plugin: dicomwebplugin
```

Test endpoint:
```bash
curl http://xnat/xapi/dicomweb/projects/PROJECT/studies
```

## Future Enhancements

### Short Term
1. Query parameter support (filtering, sorting)
2. Pagination for large result sets
3. Better error messages with DICOM error codes
4. Performance optimization (caching)
5. Thumbnail generation with configurable sizes

### Medium Term
1. WADO-URI support (legacy)
2. Frame-level retrieval
3. PNG rendering support
4. Windowing/leveling support for rendered images

### Long Term
1. STOW-RS (upload/store) support
2. UPS (Unified Procedure Step) support
3. Worklist support
4. Real-time notifications via WebSocket

## References

- **DICOM PS3.18**: Web Services Specification
  - http://dicom.nema.org/medical/dicom/current/output/html/part18.html

- **DICOMweb Standard**:
  - https://www.dicomstandard.org/using/dicomweb

- **XNAT Documentation**:
  - https://wiki.xnat.org/documentation

- **DCM4CHE**:
  - https://github.com/dcm4che/dcm4che

- **OHIF Viewer**:
  - https://ohif.org/

## Support and Contributing

For issues, feature requests, or contributions, please refer to the project's issue tracker and contribution guidelines.
