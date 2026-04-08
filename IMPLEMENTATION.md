# XNAT DICOMweb Plugin - Implementation Details

## Overview

This document provides detailed implementation information for developers working on or extending the XNAT DICOMweb Plugin.

## Project Structure

```
xnat-dicomweb-plugin/
├── build.gradle                    # Build configuration
├── settings.gradle                 # Project settings
├── gradle.properties              # Gradle properties
├── build_plugin.sh                # Build script
├── README.md                      # User documentation
├── ARCHITECTURE.md                # Architecture overview
├── TESTING.md                     # Testing guide
├── IMPLEMENTATION.md              # This file
│
├── src/main/
│   ├── java/org/nrg/xnat/dicomweb/
│   │   ├── plugin/
│   │   │   └── DicomWebPlugin.java       # Plugin entry point
│   │   ├── config/
│   │   │   └── DicomWebConfig.java            # Spring configuration
│   │   ├── rest/
│   │   │   ├── QidoRsApi.java                 # Query endpoints
│   │   │   └── WadoRsApi.java                 # Retrieve endpoints
│   │   ├── service/
│   │   │   ├── XnatDicomService.java          # Service interface
│   │   │   └── XnatDicomServiceImpl.java      # Service implementation
│   │   └── utils/
│   │       └── DicomWebUtils.java             # Utility functions
│   │
│   └── resources/
│       ├── META-INF/xnat/
│       │   └── dicomweb-plugin.properties  # Plugin metadata
│       └── config/features/
│           └── dicomweb-feature-definition.properties  # Feature config
│
└── src/test/
    ├── java/org/nrg/xnat/dicomweb/
    │   ├── rest/
    │   │   ├── QidoRsApiTest.java
    │   │   ├── WadoRsApiTest.java
    │   │   └── DicomWebIntegrationTest.java
    │   ├── service/
    │   │   └── XnatDicomServiceImplTest.java
    │   └── utils/
    │       ├── DicomWebUtilsTest.java
    │       └── TestDataFactory.java
    └── resources/
```

## Key Implementation Details

### 1. Plugin Registration

The `@XnatPlugin` annotation registers the plugin with XNAT:

```java
@XnatPlugin(
    value = "dicomwebplugin",           // Plugin ID
    name = "DICOMweb Plugin",    // Display name
    description = "...",                // Description
    entityPackages = "org.nrg.xnat.dicomweb",  // Package to scan
    openUrls = {"/dicomweb/**"}        // URLs accessible without auth
)
```

**openUrls**: Required for CORS preflight (OPTIONS) requests from web viewers.

### 2. REST API Implementation

#### Base Controller Pattern

Both API controllers extend `AbstractXapiRestController`:

```java
@XapiRestController
@Api("DICOMweb QIDO-RS API")
public class QidoRsApi extends AbstractXapiRestController {

    @Autowired
    public QidoRsApi(
        final XnatDicomService dicomService,
        final UserManagementServiceI userManagementService,
        final RoleHolder roleHolder) {
        super(userManagementService, roleHolder);
        this.dicomService = dicomService;
    }
}
```

**Key Points:**
- `@XapiRestController`: XNAT-specific REST controller annotation
- Extends `AbstractXapiRestController`: Provides `getSessionUser()` and XNAT integration
- Constructor injection for dependencies

#### Endpoint Mapping

```java
@XapiRequestMapping(
    value = "/dicomweb/projects/{projectId}/studies",
    method = RequestMethod.GET,
    produces = "application/dicom+json"
)
public ResponseEntity<String> searchStudies(@PathVariable String projectId)
```

**Key Points:**
- `@XapiRequestMapping`: XNAT-specific request mapping (prefixes with `/xapi`)
- `@PathVariable`: Extracts URL path parameters
- `produces`: Specifies response content type

### 3. XNAT Data Model Access

#### Getting Project Data

```java
XnatProjectdata project = XnatProjectdata.getXnatProjectdatasById(
    projectId,    // Project ID
    user,         // Current user (for permission check)
    false         // preload flag
);
```

**Returns:**
- `XnatProjectdata` if user has access
- `null` if project doesn't exist or user lacks permission

#### Searching for Sessions

```java
// Search by project
ArrayList sessions = XnatImagesessiondata.getXnatImagesessiondatasByField(
    "xnat:imageSessionData/project", projectId, user, false);

// Search by UID
ArrayList sessions = XnatImagesessiondata.getXnatImagesessiondatasByField(
    "xnat:imageSessionData/UID", studyUID, user, false);
```

#### Navigating the Data Model

```java
// Session → Scans (Series)
List scans = session.getScans_scan();

// Scan → Resources (Files)
List resources = scan.getFile();
```

### 4. DICOM Attribute Mapping

#### Study Level Mapping

```java
Attributes attrs = new Attributes();
attrs.setString(Tag.StudyInstanceUID, VR.UI, session.getUid());
attrs.setString(Tag.PatientName, VR.PN, session.getSubjectId());
attrs.setString(Tag.PatientID, VR.LO, session.getSubjectId());
attrs.setString(Tag.StudyDate, VR.DA, formatDate(session.getDate()));
attrs.setString(Tag.StudyDescription, VR.LO, session.getLabel());
```

#### Enhanced Study Metadata

The `retrieveStudyMetadata` endpoint provides comprehensive study-level attributes:

```java
// Study timing
attrs.setString(Tag.StudyDate, VR.DA, formatDate(session.getDate()));
attrs.setString(Tag.StudyTime, VR.TM, formatTime(session.getTime()));

// Series and instance counts
attrs.setInt(Tag.NumberOfStudyRelatedSeries, VR.IS, numberOfSeries);
attrs.setInt(Tag.NumberOfStudyRelatedInstances, VR.IS, numberOfInstances);

// Institution and modalities
attrs.setString(Tag.InstitutionName, VR.LO, session.getProject());
attrs.setString(Tag.ModalitiesInStudy, VR.CS, String.join("\\", modalities));
```

**Note:** Instance counts are calculated by reading DICOM files from each series, which may impact performance for large studies.

**Tag Constants:** From `org.dcm4che3.data.Tag`
**VR (Value Representation):** From `org.dcm4che3.data.VR`

Common VRs:
- `UI`: Unique Identifier
- `PN`: Person Name
- `LO`: Long String
- `DA`: Date
- `CS`: Code String
- `IS`: Integer String

#### Series Level Mapping

```java
attrs.setString(Tag.SeriesInstanceUID, VR.UI, scan.getUid());
attrs.setString(Tag.Modality, VR.CS, scan.getModality());
attrs.setString(Tag.SeriesNumber, VR.IS, scan.getId());
attrs.setString(Tag.SeriesDescription, VR.LO, scan.getSeriesDescription());
```

### 5. DICOM File Handling

#### Reading DICOM Files

```java
try (DicomInputStream dis = new DicomInputStream(dicomFile)) {
    Attributes attrs = dis.readDataset(-1, -1);  // Read all tags
    // Process attributes
}
```

**Parameters:**
- First `-1`: Read entire dataset (no tag limit)
- Second `-1`: Read entire dataset (no byte limit)

#### Streaming DICOM Files

```java
InputStream stream = new FileInputStream(dicomFile);
return ResponseEntity.ok()
    .headers(headers)
    .body(new InputStreamResource(stream));
```

**Benefits:**
- No memory buffering
- Efficient for large files
- Direct disk-to-network streaming

### 6. JSON Conversion

#### DICOM to JSON

```java
StringWriter sw = new StringWriter();
try (JsonGenerator gen = Json.createGenerator(sw)) {
    JSONWriter writer = new JSONWriter(gen);
    writer.write(attrs);
}
return sw.toString();
```

**Output Format:** DICOM JSON (Part 18)

Example:
```json
{
  "00100020": {
    "vr": "LO",
    "Value": ["PATIENT001"]
  },
  "0020000D": {
    "vr": "UI",
    "Value": ["1.2.840.113619.2.55.3.123"]
  }
}
```

### 7. Multipart Response Generation

```java
ByteArrayOutputStream output = new ByteArrayOutputStream();

for (InputStream stream : streams) {
    output.write(("--" + boundary + "\r\n").getBytes());
    output.write("Content-Type: application/dicom\r\n".getBytes());
    output.write("\r\n".getBytes());

    // Write DICOM data
    byte[] buffer = new byte[8192];
    int bytesRead;
    while ((bytesRead = stream.read(buffer)) != -1) {
        output.write(buffer, 0, bytesRead);
    }

    output.write("\r\n".getBytes());
    stream.close();
}

output.write(("--" + boundary + "--\r\n").getBytes());
```

**Format:** RFC 2046 multipart/related

### 8. Error Handling

#### Service Layer

```java
try {
    // XNAT operations
} catch (Exception e) {
    logger.error("Error message", e);
    return new ArrayList<>();  // Return empty list
}
```

#### API Layer

```java
try {
    // Service calls
    return ResponseEntity.ok().body(result);
} catch (Exception e) {
    logger.error("Error message", e);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
}
```

### 9. CORS Configuration

```java
@Bean
public WebMvcConfigurer corsConfigurer() {
    return new WebMvcConfigurer() {
        @Override
        public void addCorsMappings(CorsRegistry registry) {
            registry.addMapping("/xapi/dicomweb/**")
                .allowedOrigins("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Content-Type", "Content-Length")
                .maxAge(3600);
        }
    };
}
```

**Production Recommendation:** Restrict `allowedOrigins` to specific domains.

## Testing Implementation

### Unit Test Pattern

```java
@RunWith(MockitoJUnitRunner.class)
public class ServiceTest {

    @Mock
    private Dependency dependency;

    @InjectMocks
    private ServiceImpl service;

    @Before
    public void setUp() {
        // Setup
    }

    @Test
    public void testMethod() {
        // Given
        when(dependency.method()).thenReturn(value);

        // When
        Result result = service.method();

        // Then
        assertEquals(expected, result);
        verify(dependency).method();
    }
}
```

### Mocking Static Methods (XNAT Data Access)

```java
try (MockedStatic<XnatProjectdata> mockedStatic =
        mockStatic(XnatProjectdata.class)) {

    mockedStatic.when(() ->
        XnatProjectdata.getXnatProjectdatasById(anyString(), any(), anyBoolean()))
        .thenReturn(mockProject);

    // Test code
}
```

**Requires:** Mockito 3.4.0+

### Spying on REST Controllers

```java
QidoRsApi spyApi = spy(qidoRsApi);
doReturn(mockUser).when(spyApi).getSessionUser();

ResponseEntity<String> response = spyApi.searchStudies(projectId);
```

**Reason:** `getSessionUser()` accesses XNAT session context not available in unit tests.

## Build Configuration

### Gradle Dependencies

```groovy
compileOnly "org.nrg.xnat:web:${vXnat}"
compileOnly "org.nrg.xnat:xnat-data-models:${vXnat}"
compileOnly "org.dcm4che:dcm4che-core:5.29.2"
compileOnly "org.dcm4che:dcm4che-json:5.29.2"
compileOnly "org.dcm4che:dcm4che-image:5.29.2"
compileOnly "org.dcm4che:dcm4che-imageio:5.29.2"
```

**compileOnly:** Dependencies provided by XNAT at runtime.
**Note:** DCM4CHE image libraries are required for rendering DICOM to JPEG.

### Plugin Metadata

`src/main/resources/META-INF/xnat/dicomweb-plugin.properties`:

```properties
id=dicomwebplugin
class=org.nrg.xnat.dicomweb.plugin.DicomWebPlugin
name=DICOMweb Plugin
description=Exposes XNAT projects as DICOMweb endpoints
beanName=dicomWebPlugin
entityPackages=org.nrg.xnat.dicomweb
openUrls=/dicomweb, /dicomweb/**
```

## Common Pitfalls and Solutions

### 1. NullPointerException on XNAT Data Access

**Problem:** `XnatProjectdata.getXnatProjectdatasById()` returns `null`

**Causes:**
- Project doesn't exist
- User lacks permission
- Incorrect project ID

**Solution:** Always check for `null`:
```java
if (project == null) {
    logger.warn("Project not found: {}", projectId);
    return Collections.emptyList();
}
```

### 2. JSON Parsing Errors

**Problem:** DCM4CHE JSON conversion fails

**Causes:**
- Missing DICOM tags
- Invalid VR for tag
- Null values

**Solution:** Validate attributes before conversion:
```java
if (value != null && !value.isEmpty()) {
    attrs.setString(tag, vr, value);
}
```

### 3. File Not Found

**Problem:** DICOM file doesn't exist at constructed path

**Causes:**
- Incorrect XNAT archive path
- Non-standard archive structure
- File moved/deleted

**Solution:** Verify and configure archive path:
```java
String baseArchive = System.getProperty("xnat.archive", "/data/xnat/archive");
// Construct path based on XNAT conventions
String path = baseArchive + "/" + projectId + "/arc001/" +
             sessionLabel + "/SCANS/" + scanId + "/" + resourceLabel;

File catalogDir = new File(path);
if (!catalogDir.exists() || !catalogDir.isDirectory()) {
    logger.warn("Resource directory not found: {}", path);
    return null;
}
```

### 4. Memory Issues with Large Series

**Problem:** OutOfMemoryError when retrieving large series

**Cause:** Building entire multipart response in memory

**Solution:** Stream directly or use disk-based buffer:
```java
// Use temporary file for large responses
File tempFile = File.createTempFile("dicom-", ".multipart");
try (FileOutputStream fos = new FileOutputStream(tempFile)) {
    // Write multipart to file
    // Then stream file to response
}
```

### 5. CORS Preflight Failures

**Problem:** Browser blocks requests with CORS error

**Causes:**
- OPTIONS request not handled
- Missing CORS headers
- URL not in `openUrls`

**Solution:**
1. Add URL to `openUrls` in plugin annotation
2. Verify CORS configuration
3. Check browser network tab for details

## Performance Optimization

### 1. Caching Study Lists

```java
// Add caching to service layer
@Cacheable(value = "studies", key = "#projectId")
public List<Attributes> searchStudies(UserI user, String projectId, Attributes query) {
    // Implementation
}
```

### 2. Batch Database Queries

```java
// Instead of iterating and querying individually
List<String> uids = sessions.stream()
    .map(XnatImagesessiondata::getUid)
    .collect(Collectors.toList());

// Batch query by UIDs
```

### 3. Parallel File Reading

```java
List<InputStream> streams = instances.parallelStream()
    .map(attrs -> retrieveInstance(...))
    .filter(Objects::nonNull)
    .collect(Collectors.toList());
```

### 4. Connection Pooling

XNAT handles connection pooling. Ensure adequate pool size in XNAT configuration.

## Security Considerations

### 1. Input Validation

```java
if (projectId == null || projectId.isEmpty()) {
    throw new BadRequestException("Project ID required");
}

if (!isValidUID(studyUID)) {
    throw new BadRequestException("Invalid Study UID");
}
```

### 2. Path Traversal Prevention

```java
// Validate file paths
File file = new File(resourceUri).getCanonicalFile();
if (!file.getPath().startsWith(expectedBasePath)) {
    throw new SecurityException("Invalid file path");
}
```

### 3. Rate Limiting

Consider implementing rate limiting for public-facing deployments:
```java
@RateLimited(requestsPerMinute = 100)
public ResponseEntity<String> searchStudies(...)
```

## Logging Best Practices

```java
// Use parameterized logging
logger.info("Searching studies in project: {}", projectId);

// Log exceptions with context
logger.error("Error retrieving instance: {} in series: {}",
             instanceUID, seriesUID, exception);

// Use appropriate levels
logger.debug("Processing {} instances", count);  // Verbose
logger.info("Study search completed");           // General info
logger.warn("No instances found for series");    // Warning
logger.error("Failed to read DICOM file", e);   // Error
```

## Extension Examples

### Adding Custom DICOM Tag

```java
// In XnatDicomServiceImpl.createStudyAttributes()
// Map additional XNAT fields to DICOM attributes
Object modalityObj = session.getModality();
if (modalityObj != null) {
    attrs.setString(Tag.Modality, VR.CS, modalityObj.toString());
}
```

### Supporting Additional Query Parameters

```java
@XapiRequestMapping(value = "/dicomweb/projects/{projectId}/studies")
public ResponseEntity<String> searchStudies(
    @PathVariable String projectId,
    @RequestParam(required = false) String patientName) {

    // Filter by patient name if provided
}
```

### Custom Content Type Support

```java
@XapiRequestMapping(
    value = "/...",
    produces = {"application/dicom+json", "application/json"}
)
public ResponseEntity<String> search(...) {
    // Support both DICOM JSON and regular JSON
}
```

## Debugging Tips

### 1. Enable Debug Logging

Add to XNAT's `log4j.properties`:
```properties
log4j.logger.org.nrg.xnat.dicomweb=DEBUG
```

### 2. Test with cURL

```bash
# Verbose output
curl -v -u user:pass \
  "http://localhost:8080/xapi/dicomweb/projects/PROJECT/studies"

# Save response
curl -u user:pass \
  "http://localhost:8080/xapi/dicomweb/projects/PROJECT/studies" \
  > response.json
```

### 3. DICOM File Validation

```bash
# Validate DICOM file
dcmdump /path/to/file.dcm

# Check specific tags
dcmdump +P StudyInstanceUID /path/to/file.dcm
```

### 4. Monitor XNAT Database

```sql
-- Check imaging sessions
SELECT * FROM xnat_imagesessiondata WHERE project = 'PROJECT_ID';

-- Check scans
SELECT * FROM xnat_imagescandata WHERE image_session_id = 'SESSION_ID';
```

## References

- [XNAT Plugin Development](https://wiki.xnat.org/documentation/xnat-developer-documentation/xnat-plugin-development)
- [DCM4CHE3 Documentation](https://github.com/dcm4che/dcm4che)
- [Spring Framework Reference](https://docs.spring.io/spring-framework/docs/current/reference/html/)
- [DICOM Standard Part 18](http://dicom.nema.org/medical/dicom/current/output/html/part18.html)
